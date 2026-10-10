"""Async client for Minecraft AI Bridge protocol 1.0. No model provider is required."""
from __future__ import annotations

import asyncio
import json
import struct
import sys
import uuid
from array import array
from pathlib import Path

from websockets.asyncio.client import connect


class BridgeError(RuntimeError):
    def __init__(self, data):
        self.code = data["code"]
        self.retryable = data.get("retryable", False)
        super().__init__(f'{self.code}: {data.get("message", "")}')


def decode_frame(packet: bytes):
    if len(packet) < 8 or packet[:4] != b"MBV1":
        raise ValueError("Invalid vision packet")
    size = struct.unpack_from(">I", packet, 4)[0]
    if size > 2_000_000 or 8 + size > len(packet):
        raise ValueError("Invalid metadata size")
    metadata = json.loads(packet[8:8 + size])
    rgb_size, depth_size = metadata["rgb"]["bytes"], metadata["depth"]["bytes"]
    if rgb_size < 0 or depth_size < 0 or len(packet) != 8 + size + rgb_size + depth_size:
        raise ValueError("Invalid payload size")
    if depth_size != metadata["depth"]["width"] * metadata["depth"]["height"] * 4:
        raise ValueError("Invalid depth dimensions")
    png = packet[8 + size:8 + size + rgb_size]
    depth = array("f")
    depth.frombytes(packet[8 + size + rgb_size:])
    if sys.byteorder != "little":
        depth.byteswap()
    return metadata, png, depth


class BridgeClient:
    def __init__(self, token: str, url="ws://127.0.0.1:8765/bridge"):
        self.token, self.url = token, url
        self.session_id = None
        self.actor_id = None
        self.last_sequence = 0
        self.events = asyncio.Queue(maxsize=1024)
        self.frames = asyncio.Queue(maxsize=1)
        self._pending = {}
        self._socket = self._reader = None

    @classmethod
    def from_owner_file(cls, filename, url=None):
        config = json.loads(Path(filename).read_text())
        return cls(config["token"], url or f'ws://127.0.0.1:{config["port"]}/bridge')

    async def __aenter__(self):
        await self.open()
        return self

    async def __aexit__(self, *exc):
        await self.close()

    async def open(self):
        previous = self.session_id
        self._socket = await connect(self.url, max_size=8 * 1024 * 1024, proxy=None)
        self._reader = asyncio.create_task(self._read())
        hello = await self.request("session.hello", {"actor_mode": "existing_player"})
        self.session_id = hello["session_id"]
        auth = await self.request("session.authenticate", {"token": self.token})
        self.actor_id = auth["capabilities"]["actor_id"]
        if previous == self.session_id:
            return await self.request("session.resume", {"after_sequence": self.last_sequence})
        self.last_sequence = 0
        return await self.request("observe.snapshot")

    async def close(self):
        if self._socket:
            await self._socket.close()
        if self._reader:
            self._reader.cancel()
            try:
                await self._reader
            except asyncio.CancelledError:
                pass
        self._socket = self._reader = None

    async def _read(self):
        error = None
        try:
            async for raw in self._socket:
                if isinstance(raw, bytes):
                    if self.frames.full():
                        self.frames.get_nowait()
                    self.frames.put_nowait(decode_frame(raw))
                    continue
                message = json.loads(raw)
                request = message.get("request_id")
                if message["type"] in ("response", "error") and request in self._pending:
                    future = self._pending.pop(request)
                    if not future.done():
                        if message["type"] == "error":
                            future.set_exception(BridgeError(message["data"]))
                        else:
                            future.set_result(message["data"])
                else:
                    self.last_sequence = max(self.last_sequence, message.get("sequence", 0))
                    # Results must never disappear silently when the caller stops consuming.
                    if self.events.full():
                        raise RuntimeError("Event consumer too slow; reconnect and session.resume")
                    self.events.put_nowait(message)
        except Exception as exc:
            error = exc
        finally:
            for future in self._pending.values():
                if not future.done():
                    future.set_exception(error or ConnectionError("Bridge disconnected"))
            self._pending.clear()

    async def request(self, operation, data=None, *, request_id=None, expected_revision=None, timeout=10):
        request_id = request_id or str(uuid.uuid4())
        if request_id in self._pending:
            raise ValueError("Request already pending")
        message = {
            "protocol_version": "1.0", "session_id": self.session_id,
            "message_id": str(uuid.uuid4()), "request_id": request_id,
            "actor_id": self.actor_id, "type": operation, "data": data or {},
        }
        if expected_revision is not None:
            message["expected_revision"] = expected_revision
        future = asyncio.get_running_loop().create_future()
        self._pending[request_id] = future
        try:
            await self._socket.send(json.dumps(message))
            return await asyncio.wait_for(future, timeout)
        finally:
            self._pending.pop(request_id, None)

    async def submit(self, action, parameters, **limits):
        return await self.request("task.submit", {"action": action, "parameters": parameters, **limits})

    async def wait_task(self, task_id, timeout=70):
        async with asyncio.timeout(timeout):
            while True:
                task = await self.request("task.status", {"task_id": task_id})
                if task["state"] in ("succeeded", "failed", "cancelled"):
                    return task
                await asyncio.sleep(0.25)

    async def capture(self, width=640, height=360):
        # Drop a prior stream frame before requesting a specific new capture.
        while not self.frames.empty():
            self.frames.get_nowait()
        response = await self.request("vision.capture", {"width": width, "height": height})
        async with asyncio.timeout(10):
            while True:
                frame = await self.frames.get()
                if frame[0].get("request_id") == response["request_id"]:
                    return frame
