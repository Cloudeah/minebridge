import ast
import asyncio
import base64
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

    def test_compact_observation_preserves_inventory_and_menu_slot_ids(self):
        state = snapshot()
        state["blocks"] = [{"x": i, "y": 64, "z": 0, "type": "minecraft:stone"} for i in range(20, 0, -1)]
        state["entities"] = [{"id": str(i)} for i in range(10)]
        state["inventory"] = [{"slot": 17, "type": "minecraft:stick", "count": 2}]
        state["context"].update(menu_id=7, menu_revision=42)
        state["context"]["menu_slots"] = [
            {"slot": i, "type": "minecraft:stick" if i in (8, 50) else "minecraft:air",
             "count": 2 if i in (8, 50) else 0, "player_inventory": i >= 27}
            for i in range(60)]
        payload = controller.model_payload("look east", state, ["look.at"], [1, 2, 3], compact=True)
        user = json.loads(payload["messages"][1]["content"])
        observed = user["observation"]
        self.assertEqual([b["x"] for b in observed["blocks"]], [1, 2, 3, 4, 5, 6])
        self.assertEqual(len(observed["entities"]), 4)
        self.assertEqual([s["slot"] for s in observed["menu_slots"]], [0, 1, 2, 3, 8, 50])
        self.assertEqual(observed["inventory"][0]["slot"], 17)
        self.assertEqual(observed["context"]["menu_id"], 7)
        self.assertEqual(observed["context"]["menu_revision"], 42)
        self.assertIn("Missing data is unknown", observed["observation_limits"])
        self.assertEqual(user["recent_results"], [3])


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


class FakeVllm:
    def __init__(self, error=False):
        self.payloads, self.token_requests, self.headers = [], [], []
        self.count = 100
        self.content = json.dumps(DECISION)
        self.finish_reason = "stop"
        self.before_reply = None
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                value = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                if self.path == "/tokenize":
                    owner.token_requests.append(value)
                    reply = {"count": owner.count, "max_model_len": 2048, "tokens": []}
                    status = 200
                elif self.path == "/v1/chat/completions":
                    owner.payloads.append(value)
                    owner.headers.append(dict(self.headers))
                    if owner.before_reply is not None:
                        owner.before_reply()
                    reply = {"choices": [{"finish_reason": owner.finish_reason,
                                          "message": {"role": "assistant", "content": owner.content}}]}
                    status = 503 if error else 200
                else:
                    reply, status = {}, 404
                data = json.dumps(reply).encode()
                self.send_response(status)
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


def notebook_function(namespace, filename="ollama_colab.ipynb"):
    notebook = json.loads((ROOT / "examples" / filename).read_text())
    functions = [node for cell in notebook["cells"] if cell["cell_type"] == "code"
                 for node in ast.parse("".join(cell["source"])).body if isinstance(node, ast.FunctionDef)]
    module = ast.Module(body=functions, type_ignores=[])
    exec(compile(module, "notebook-decision-cell", "exec"), namespace)
    return namespace["process_decisions"]


class VllmAdapterTest(unittest.TestCase):
    def setUp(self):
        self.model = FakeVllm()
        self.session = requests.Session()
        self.session.trust_env = False
        self.namespace = {"requests": requests, "json": json, "time": time, "base64": base64,
                          "MODEL": "fixture-model", "MODEL_URL": self.model.url,
                          "MAX_MODEL_LEN": 2048, "MAX_NEW_TOKENS": 256, "MODEL_TIMEOUT": 5,
                          "ENABLE_VISION": False, "USE_STRUCTURED_OUTPUTS": False,
                          "model_session": self.session}
        self.worker = notebook_function(self.namespace, "vllm_tpu_colab.ipynb")
        self.payload = controller.model_payload("look east", snapshot(), ["look.at"], [], compact=True)

    def tearDown(self):
        self.session.close()
        self.model.close()

    def test_oversized_context_is_rejected_before_generation(self):
        self.model.count = 1793
        with self.assertRaisesRegex(ValueError, "Контекст слишком длинный"):
            self.namespace["infer_decision"](self.payload)
        self.assertEqual(len(self.model.token_requests), 1)
        self.assertFalse(self.model.payloads)

    def test_invalid_or_truncated_model_answers_are_rejected(self):
        cases = [
            (json.dumps({**DECISION, "action": "arbitrary.command"}), "stop"),
            (json.dumps({**DECISION, "parameters": {"x": math.nan}}), "stop"),
            (json.dumps({**DECISION, "reason": "x" * 501}), "stop"),
            (json.dumps({**DECISION, "parameters": []}), "stop"),
            (json.dumps({**DECISION, "extra": "unexpected"}), "stop"),
            (json.dumps(DECISION), "length"),
            ("not JSON", "stop"),
        ]
        for content, finish in cases:
            with self.subTest(content=content, finish=finish):
                self.model.content, self.model.finish_reason = content, finish
                with self.assertRaises(ValueError):
                    self.namespace["infer_decision"](self.payload)

    def test_optional_structured_output_uses_openai_schema_format(self):
        self.namespace["USE_STRUCTURED_OUTPUTS"] = True
        self.assertEqual(self.namespace["infer_decision"](self.payload), DECISION)
        posted = self.model.payloads[0]
        self.assertEqual(posted["response_format"]["json_schema"]["schema"], self.payload["format"])
        self.assertEqual(posted["max_tokens"], 256)
        self.assertEqual(posted["chat_template_kwargs"], {"enable_thinking": False})
        self.assertNotIn("options", posted)
        self.assertNotIn("format", posted)

    def test_disabled_vision_is_rejected_before_request(self):
        png = (ROOT / "docs/media/native-frame.png").read_bytes()
        payload = controller.model_payload("look east", snapshot(), ["look.at"], [], png, compact=True)
        with self.assertRaisesRegex(ValueError, "ENABLE_VISION"):
            self.namespace["infer_decision"](payload)
        self.assertFalse(self.model.token_requests)
        self.assertFalse(self.model.payloads)

    def test_delivery_retry_keeps_job_id_and_does_not_regenerate(self):
        relay = RelayServer(TOKEN, 0)
        relay.start()
        session = requests.Session()
        session.trust_env = False
        session.headers["Authorization"] = "Bearer " + TOKEN
        self.namespace.update(RELAY_URL=f"http://127.0.0.1:{relay.port}", relay_session=session)
        deliveries = []
        original_post = session.post

        def unreliable_delivery(url, **kwargs):
            deliveries.append(kwargs["json"])
            response = original_post(url, **kwargs)
            if len(deliveries) == 1:
                raise requests.ConnectionError("fixture: response lost after acceptance")
            return response

        try:
            with ThreadPoolExecutor(max_workers=1) as pool, patch.object(session, "post", unreliable_delivery):
                future = pool.submit(relay.broker.infer, self.payload, 5)
                with contextlib.redirect_stdout(io.StringIO()):
                    self.worker(1)
                self.assertEqual(future.result(timeout=2), DECISION)
            self.assertEqual(len(deliveries), 2)
            self.assertEqual(deliveries[0], deliveries[1])
            self.assertEqual(len(self.model.payloads), 1)
            self.assertNotIn("Authorization", self.model.headers[0])
        finally:
            session.close()
            relay.close()

    def test_expired_job_cannot_accept_a_slow_model_answer(self):
        relay = RelayServer(TOKEN, 0)
        relay.start()
        session = requests.Session()
        session.trust_env = False
        session.headers["Authorization"] = "Bearer " + TOKEN
        self.namespace.update(RELAY_URL=f"http://127.0.0.1:{relay.port}", relay_session=session)
        reached_model, release_model = threading.Event(), threading.Event()

        def delay_reply():
            reached_model.set()
            release_model.wait(3)

        self.model.before_reply = delay_reply
        try:
            with ThreadPoolExecutor(max_workers=2) as pool, contextlib.redirect_stdout(io.StringIO()) as output:
                future = pool.submit(relay.broker.infer, self.payload, .3)
                worker = pool.submit(self.worker, 1)
                self.assertTrue(reached_model.wait(2))
                with self.assertRaises(TimeoutError):
                    future.result(timeout=2)
                release_model.set()
                worker.result(timeout=3)
            self.assertIn("не было принято", output.getvalue())
            self.assertIsNone(relay.broker.pending)
            self.assertIsNone(relay.broker.completed)
        finally:
            release_model.set()
            session.close()
            relay.close()


class ControllerIntegrationTest(unittest.IsolatedAsyncioTestCase):
    async def integration(self, execute=False, vision=False, vllm=False, model_error=False):
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
            args.compact_observations = vllm
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

                model = FakeVllm(error=model_error) if vllm else FakeOllama()
                session = requests.Session()
                session.trust_env = False
                try:
                    with patch.object(controller, "RelayServer", create_relay), contextlib.redirect_stdout(io.StringIO()):
                        task = asyncio.create_task(controller.run(args))
                        await asyncio.wait_for(ready.wait(), 3)
                        session.headers["Authorization"] = "Bearer " + holder["token"]
                        with requests.Session() as model_session:
                            model_session.trust_env = False
                            namespace = {"requests": requests, "json": json, "time": time, "base64": base64,
                                         "MODEL": "fixture-model", "OLLAMA_URL": model.url, "MODEL_URL": model.url,
                                         "MAX_MODEL_LEN": 2048, "MAX_NEW_TOKENS": 256, "MODEL_TIMEOUT": 5,
                                         "ENABLE_VISION": vision, "USE_STRUCTURED_OUTPUTS": False,
                                         "model_session": model_session,
                                         "RELAY_URL": f"http://127.0.0.1:{holder['relay'].port}", "relay_session": session}
                            worker = notebook_function(namespace, "vllm_tpu_colab.ipynb" if vllm else "ollama_colab.ipynb")
                            with patch.dict("os.environ", {"NO_PROXY": "127.0.0.1,localhost"}):
                                outcomes = await asyncio.wait_for(asyncio.gather(
                                    task, asyncio.to_thread(worker, 1), return_exceptions=model_error), 15)
                            if model_error:
                                self.assertIsInstance(outcomes[0], RuntimeError)
                                self.assertIn("vLLM", str(outcomes[0]))
                    self.assertEqual(len(model.payloads), 1)
                    self.assertEqual(model.payloads[0]["model"], "fixture-model")
                    if vllm:
                        self.assertEqual(len(model.token_requests), 1)
                        self.assertNotIn("Authorization", model.headers[0])
                        self.assertNotIn(holder["token"], json.dumps(model.payloads[0]))
                        self.assertIn('"look.at","wait","finish"', model.payloads[0]["messages"][0]["content"])
                    else:
                        self.assertEqual(model.payloads[0]["format"]["properties"]["action"]["enum"], ["look.at", "wait", "finish"])
                    self.assertEqual(len(submitted), 1 if execute and not model_error else 0)
                    if execute and not model_error:
                        self.assertEqual(submitted[0]["action"], "look.at")
                        self.assertEqual(submitted[0]["parameters"], DECISION["parameters"])
                    if vision:
                        if vllm:
                            image = model.payloads[0]["messages"][1]["content"][0]
                            self.assertEqual(image["type"], "image_url")
                            encoded = image["image_url"]["url"].removeprefix("data:image/png;base64,")
                            self.assertEqual(base64.b64decode(encoded), (ROOT / "docs/media/native-frame.png").read_bytes())
                        else:
                            self.assertIn("images", model.payloads[0]["messages"][1])
                finally:
                    session.close()
                    model.close()

    async def test_preview_calls_notebook_and_ollama_without_submitting_game_action(self):
        await self.integration()

    async def test_execution_submits_decision_and_reads_terminal_result(self):
        await self.integration(execute=True)

    async def test_vision_sends_native_png_to_notebook_model_call(self):
        await self.integration(vision=True)

    async def test_vllm_preview_uses_openai_api_without_submitting_game_action(self):
        await self.integration(vllm=True)

    async def test_vllm_execution_submits_decision_and_reads_result(self):
        await self.integration(execute=True, vllm=True)

    async def test_vllm_vision_preserves_png_in_openai_image_message(self):
        await self.integration(vision=True, vllm=True)

    async def test_vllm_failure_never_submits_game_action(self):
        await self.integration(execute=True, vllm=True, model_error=True)

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
