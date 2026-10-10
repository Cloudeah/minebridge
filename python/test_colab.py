import ast
import asyncio
import contextlib
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import io
import json
import math
from pathlib import Path
import struct
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

import requests
from websockets.asyncio.server import serve
from colab_relay import RelayServer, load_relay_config

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("ollama_controller", ROOT / "examples/ollama_colab.py")
controller = importlib.util.module_from_spec(spec)
spec.loader.exec_module(controller)
TOKEN = "test-relay-token-" + "x" * 32
DECISION = {"action": "look.at", "parameters": {"target": {"x": 3, "y": 65, "z": 0}}, "reason": "test fixture"}


class RelayTest(unittest.TestCase):
    def setUp(self):
        self.relay = RelayServer(TOKEN, 0)
        self.relay.start()
        self.url = f"http://127.0.0.1:{self.relay.port}"
        self.session = requests.Session()
        self.session.trust_env = False
        self.session.headers["Authorization"] = "Bearer " + TOKEN

    def tearDown(self):
        self.session.close()
        self.relay.close()

    def test_authorization_required_for_observations_and_results(self):
        session = requests.Session()
        session.trust_env = False
        with session:
            for path in ["/health", "/job"]:
                self.assertEqual(session.get(self.url + path, timeout=2).status_code, 401)
            self.assertEqual(session.post(self.url + "/result", json={}, timeout=2).status_code, 401)
        self.assertEqual(self.session.get(self.url + "/health", timeout=2).json()["status"], "ready")

    def test_job_round_trip_and_duplicate_delivery_do_not_create_another_job(self):
        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(self.relay.broker.infer, {"messages": []}, 2)
            job = self.session.get(self.url + "/job", timeout=2).json()
            value = {"job_id": job["job_id"], "decision": DECISION}
            self.assertEqual(self.session.post(self.url + "/result", json=value, timeout=2).status_code, 200)
            self.assertEqual(future.result(timeout=2), DECISION)
            self.assertEqual(self.session.post(self.url + "/result", json=value, timeout=2).status_code, 200)
            changed = {**value, "decision": {**DECISION, "reason": "changed"}}
            self.assertEqual(self.session.post(self.url + "/result", json=changed, timeout=2).status_code, 409)

    def test_expired_job_rejects_late_result(self):
        with self.assertRaises(TimeoutError):
            self.relay.broker.infer({"messages": []}, timeout=.01)
        response = self.session.post(self.url + "/result", json={"job_id": "expired", "decision": DECISION}, timeout=2)
        self.assertEqual(response.status_code, 409)

    def test_model_error_is_reported_to_controller(self):
        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(self.relay.broker.infer, {}, 2)
            job = self.session.get(self.url + "/job", timeout=2).json()
            response = self.session.post(self.url + "/result", json={"job_id": job["job_id"], "error": "model unavailable"}, timeout=2)
            self.assertEqual(response.status_code, 200)
            with self.assertRaisesRegex(RuntimeError, "model unavailable"):
                future.result(timeout=2)


class DecisionTest(unittest.TestCase):
    def test_unavailable_action_and_nonfinite_coordinates_are_rejected(self):
        with self.assertRaises(ValueError):
            controller.validate_decision({**DECISION, "action": "arbitrary.command"}, ["look.at"])
        with self.assertRaises(ValueError):
            controller.validate_decision({**DECISION, "parameters": {"x": math.nan}}, ["look.at"])

    def test_relay_token_is_reused_without_becoming_model_input(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "relay.json"
            first = load_relay_config(path)
            self.assertEqual(first, load_relay_config(path))
            self.assertGreaterEqual(len(first["token"]), 32)
            self.assertNotIn(first["token"], json.dumps(controller.model_payload("look east", snapshot(), ["look.at"], [])))


def snapshot():
    return {"actor_id": "test-actor", "dimension": "minecraft:overworld", "revision": 1, "game_tick": 1,
            "actor": {"position": {"x": 0, "y": 64, "z": 0}, "health": 20},
            "inventory": [], "blocks": [], "entities": [],
            "context": {"menu_id": 0, "menu_revision": 0, "menu_slots": [], "cursor": {"type": "minecraft:air", "count": 0}}}


class FakeOllama:
    def __init__(self):
        self.payloads = []
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                value = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                owner.payloads.append(value)
                data = json.dumps({"message": {"role": "assistant", "content": json.dumps(DECISION)}}).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)


def notebook_function(namespace):
    notebook = json.loads((ROOT / "examples/ollama_colab.ipynb").read_text())
    source = next("".join(c["source"]) for c in notebook["cells"] if c["cell_type"] == "code" and "def process_decisions" in "".join(c["source"]))
    function = next(n for n in ast.parse(source).body if isinstance(n, ast.FunctionDef))
    module = ast.Module(body=[function], type_ignores=[])
    exec(compile(module, "notebook-decision-cell", "exec"), namespace)
    return namespace["process_decisions"]


class ControllerIntegrationTest(unittest.IsolatedAsyncioTestCase):
    async def integration(self, execute=False, vision=False):
        submitted = []
        capabilities = {"actions": ["look.at"], "actor_id": "test-actor", "armed": True}

        async def bridge_handler(socket):
            async for raw in socket:
                message = json.loads(raw)
                operation = message["type"]
                if operation == "session.hello":
                    value = {"session_id": "test-session"}
                elif operation == "session.authenticate":
                    value = {"capabilities": capabilities}
                elif operation == "session.capabilities":
                    value = capabilities
                elif operation == "observe.snapshot":
                    value = snapshot()
                elif operation == "vision.capture":
                    value = {"status": "accepted", "request_id": message["request_id"]}
                elif operation == "task.submit":
                    submitted.append(message["data"])
                    value = {"status": "accepted", "task_id": "fixture-task"}
                elif operation == "task.status":
                    value = {"task_id": "fixture-task", "state": "succeeded", "result": {}}
                else:
                    raise AssertionError(operation)
                await socket.send(json.dumps({"type": "response", "request_id": message["request_id"], "data": value}))
                if operation == "vision.capture":
                    png = (ROOT / "docs/media/native-frame.png").read_bytes()
                    meta = {"request_id": message["request_id"], "observation": snapshot(),
                            "rgb": {"bytes": len(png)}, "depth": {"bytes": 4, "width": 1, "height": 1}}
                    encoded = json.dumps(meta).encode()
                    await socket.send(b"MBV1" + struct.pack(">I", len(encoded)) + encoded + png + struct.pack("<f", 2.5))

        with tempfile.TemporaryDirectory() as directory:
            owner = Path(directory) / "owner.json"
            owner.write_text(json.dumps({"token": "fixture-owner-token", "port": 8765}))
            args = controller.parser().parse_args(["--config", str(owner), "--relay-config", str(Path(directory) / "relay.json"),
                                                   "--relay-port", "0", "--goal", "look east", "--model-timeout", "5"])
            args.execute, args.vision = execute, vision
            async with serve(bridge_handler, "127.0.0.1", 0) as bridge_server:
                args.url = f"ws://127.0.0.1:{bridge_server.sockets[0].getsockname()[1]}/bridge"
                ready = asyncio.Event()
                holder = {}
                actual_relay = controller.RelayServer

                def create_relay(token, port):
                    relay = actual_relay(token, port)
                    holder.update(relay=relay, token=token)
                    ready.set()
                    return relay

                ollama = FakeOllama()
                session = requests.Session()
                session.trust_env = False
                try:
                    with patch.object(controller, "RelayServer", create_relay), contextlib.redirect_stdout(io.StringIO()):
                        task = asyncio.create_task(controller.run(args))
                        await asyncio.wait_for(ready.wait(), 3)
                        session.headers["Authorization"] = "Bearer " + holder["token"]
                        namespace = {"requests": requests, "json": json, "time": time,
                                     "MODEL": "fixture-model", "OLLAMA_URL": ollama.url,
                                     "RELAY_URL": f"http://127.0.0.1:{holder['relay'].port}", "relay_session": session}
                        worker = notebook_function(namespace)
                        with patch.dict("os.environ", {"NO_PROXY": "127.0.0.1,localhost"}):
                            await asyncio.wait_for(asyncio.gather(task, asyncio.to_thread(worker, 1)), 15)
                    self.assertEqual(len(ollama.payloads), 1)
                    self.assertEqual(ollama.payloads[0]["model"], "fixture-model")
                    self.assertEqual(ollama.payloads[0]["format"]["properties"]["action"]["enum"], ["look.at", "wait", "finish"])
                    self.assertEqual(len(submitted), 1 if execute else 0)
                    if execute:
                        self.assertEqual(submitted[0]["action"], "look.at")
                        self.assertEqual(submitted[0]["parameters"], DECISION["parameters"])
                    if vision:
                        self.assertIn("images", ollama.payloads[0]["messages"][1])
                finally:
                    session.close()
                    ollama.close()

    async def test_preview_calls_notebook_and_ollama_without_submitting_game_action(self):
        await self.integration()

    async def test_execution_submits_decision_and_reads_terminal_result(self):
        await self.integration(execute=True)

    async def test_vision_sends_native_png_to_notebook_model_call(self):
        await self.integration(vision=True)

    async def test_interrupt_before_colab_connects_releases_startup_waiter(self):
        with tempfile.TemporaryDirectory() as directory:
            owner = Path(directory) / "owner.json"
            owner.write_text(json.dumps({"token": "fixture-owner-token", "port": 8765}))
            args = controller.parser().parse_args(["--config", str(owner), "--relay-config", str(Path(directory) / "relay.json"),
                                                   "--relay-port", "0", "--goal", "look east"])
            relay = RelayServer(TOKEN, 0)
            with patch.object(controller, "RelayServer", return_value=relay), contextlib.redirect_stdout(io.StringIO()):
                task = asyncio.create_task(controller.run(args))
                await asyncio.sleep(.05)
                task.cancel()
                with self.assertRaises(asyncio.CancelledError):
                    await asyncio.wait_for(task, 3)
            self.assertTrue(relay.broker.closed)
            self.assertTrue(relay.broker.worker_ready.is_set())


if __name__ == "__main__":
    unittest.main()
