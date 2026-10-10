"""In-game goal lifecycle through the actual controller, notebook and HTTP/WebSocket APIs."""
import asyncio
import base64
import contextlib
import io
import json
from pathlib import Path
import struct
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

import requests
from websockets.asyncio.server import serve
from test_colab import ROOT, DECISION, FakeVllm, controller, notebook_function, snapshot


class InteractiveControllerTest(unittest.IsolatedAsyncioTestCase):
    async def integration(self, vision=False, cancel_first=False):
        submitted, observed_profiles, reports = [], [], []
        terminal = asyncio.Event()
        holder = {}
        model = FakeVllm()
        reached_model, release_model = threading.Event(), threading.Event()
        release_model.set()
        if cancel_first:
            release_model.clear()

            def delay_first_reply():
                if len(model.payloads) == 1:
                    reached_model.set()
                    release_model.wait(5)

            model.before_reply = delay_first_reply

        def goal(identifier):
            return {"goal_id": identifier, "goal": "look east", "steps": 1, "vision": vision,
                    "state": "queued", "step": 0}

        status = goal("first")
        capabilities = {"actions": ["look.at"], "actor_id": "test-actor", "armed": True,
                        "model_control": True, "observation_profiles": ["full", "compact"]}

        def observation():
            return {**snapshot(), "control_epoch": 7}

        async def handler(socket):
            holder["socket"] = socket
            async for raw in socket:
                message = json.loads(raw)
                operation, data = message["type"], message["data"]
                if operation == "session.hello":
                    value = {"session_id": "test-session"}
                elif operation == "session.authenticate":
                    value = {"capabilities": capabilities}
                elif operation == "session.capabilities":
                    value = capabilities
                elif operation == "observe.snapshot":
                    observed_profiles.append(data.get("profile", "full"))
                    value = observation()
                elif operation == "model.status":
                    value = dict(status)
                elif operation == "model.claim":
                    self.assertEqual(data["goal_id"], status["goal_id"])
                    self.assertEqual(status["state"], "queued")
                    status["state"] = "thinking"
                    value = dict(status)
                elif operation == "model.report":
                    self.assertEqual(data["goal_id"], status["goal_id"])
                    self.assertNotEqual(status["state"], "cancelled")
                    reports.append(dict(data))
                    status.update(data)
                    value = dict(status)
                    if data["state"] in ("completed", "waiting", "failed"):
                        if status["goal_id"] == "first":
                            status.clear()
                            status.update(goal("second"))
                        else:
                            terminal.set()
                elif operation == "task.submit":
                    self.assertEqual(status["state"], "executing")
                    self.assertEqual(data["model_goal_id"], status["goal_id"])
                    self.assertEqual(data["expected_control_epoch"], 7)
                    submitted.append(dict(data))
                    value = {"status": "accepted", "task_id": "task-" + status["goal_id"]}
                elif operation == "task.status":
                    value = {"state": "succeeded", "task_id": data["task_id"], "result": {}}
                elif operation == "vision.capture":
                    self.assertEqual(data["observation_profile"], "compact")
                    value = {"status": "accepted", "request_id": message["request_id"]}
                elif operation == "control.stop":
                    status["state"] = "cancelled"
                    value = {"status": "stopped"}
                else:
                    raise AssertionError(operation)
                await socket.send(json.dumps({"type": "response", "request_id": message["request_id"], "data": value}))
                if operation == "vision.capture":
                    png = (ROOT / "docs/media/native-frame.png").read_bytes()
                    metadata = {"request_id": message["request_id"], "observation": observation(),
                                "rgb": {"bytes": len(png)}, "depth": {"bytes": 4, "width": 1, "height": 1}}
                    encoded = json.dumps(metadata).encode()
                    await socket.send(b"MBV1" + struct.pack(">I", len(encoded)) + encoded + png + struct.pack("<f", 2.5))

        task = worker = None
        session = requests.Session()
        session.trust_env = False
        model_session = requests.Session()
        model_session.trust_env = False
        try:
            with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()):
                owner = Path(directory) / "owner.json"
                owner.write_text(json.dumps({"token": "fixture-owner-token", "port": 8765}))
                args = controller.parser().parse_args(["--config", str(owner), "--interactive", "--relay-port", "0",
                    "--relay-config", str(Path(directory) / "relay.json"), "--model-timeout", "5"])
                args.compact_observations = True
                actual_relay = controller.RelayServer

                def create_relay(token, port):
                    relay = actual_relay(token, port)
                    holder.update(relay=relay, token=token)
                    return relay

                async with serve(handler, "127.0.0.1", 0) as server:
                    args.url = f"ws://127.0.0.1:{server.sockets[0].getsockname()[1]}/bridge"
                    with patch.object(controller, "RelayServer", create_relay):
                        task = asyncio.create_task(controller.run(args))
                        while "relay" not in holder:
                            await asyncio.sleep(.01)
                        session.headers["Authorization"] = "Bearer " + holder["token"]
                        namespace = {"requests": requests, "json": json, "time": time, "base64": base64,
                            "MODEL": "fixture-model", "MODEL_URL": model.url, "MAX_MODEL_LEN": 2048,
                            "MAX_NEW_TOKENS": 256, "MODEL_TIMEOUT": 5, "ENABLE_VISION": vision,
                            "USE_STRUCTURED_OUTPUTS": False, "model_session": model_session,
                            "RELAY_URL": f"http://127.0.0.1:{holder['relay'].port}", "relay_session": session}
                        decide = notebook_function(namespace, "vllm_tpu_colab.ipynb")
                        worker = asyncio.create_task(asyncio.to_thread(decide, 2, stop_on_wait=False))
                        if cancel_first:
                            self.assertTrue(await asyncio.to_thread(reached_model.wait, 3))
                            status["state"] = "cancelled"
                            await holder["socket"].send(json.dumps({"type": "control.manual_override", "data": {}}))
                            async with asyncio.timeout(2):
                                while holder["relay"].broker.pending is not None:
                                    await asyncio.sleep(.01)
                            self.assertFalse(submitted)
                            status.clear()
                            status.update(goal("second"))
                            release_model.set()
                        await asyncio.wait_for(terminal.wait(), 8)
                        await asyncio.wait_for(worker, 2)
                        self.assertFalse(task.done())  # The controller remains connected for more owner goals.
                        task.cancel()
                        with self.assertRaises(asyncio.CancelledError):
                            await task
            self.assertEqual(len(model.payloads), 2)
            self.assertEqual([item["model_goal_id"] for item in submitted], ["second"] if cancel_first else ["first", "second"])
            self.assertIn("compact", observed_profiles)
            if vision:
                image = model.payloads[0]["messages"][1]["content"][0]["image_url"]["url"]
                self.assertEqual(base64.b64decode(image.split(",", 1)[1]), (ROOT / "docs/media/native-frame.png").read_bytes())
            self.assertTrue(holder["relay"].broker.closed)
        finally:
            release_model.set()
            for pending in (task, worker):
                if pending is not None and not pending.done():
                    pending.cancel()
                    with contextlib.suppress(asyncio.CancelledError, Exception):
                        await pending
            session.close()
            model_session.close()
            model.close()

    async def test_two_owner_goals_share_controller_and_notebook_session(self):
        await self.integration()

    async def test_game_window_vision_option_requests_compact_snapshot_and_preserves_png(self):
        await self.integration(vision=True)

    async def test_manual_stop_rejects_slow_reply_then_processes_new_owner_goal(self):
        await self.integration(cancel_first=True)
