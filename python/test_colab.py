import ast
import asyncio
import base64
import contextlib
from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
import importlib.util
import io
import json
import math
import os
from pathlib import Path
import struct
from types import ModuleType, SimpleNamespace
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import MagicMock, patch

import requests
from websockets.asyncio.server import serve
from colab_relay import RelayServer, load_relay_config

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("vllm_controller", ROOT / "examples/vllm_colab.py")
controller = importlib.util.module_from_spec(spec)
spec.loader.exec_module(controller)
TOKEN = "test-relay-token-" + "x" * 32
DECISION = {"action": "look.at", "parameters": {"target": {"x": 3, "y": 65, "z": 0}}, "reason": "test fixture"}
EXPRESSION_REPLY = '{"action":"look.at","parameters":{"target":{"x":0 + 3,"y":65,"z":0}},"reason":"east"}'


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

    def test_owner_cancellation_rejects_late_reply_and_relay_accepts_next_goal(self):
        with ThreadPoolExecutor(max_workers=1) as pool:
            first = pool.submit(self.relay.broker.infer, {}, 5)
            old = self.session.get(self.url + "/job", timeout=2).json()
            self.relay.broker.cancel_pending()
            with self.assertRaisesRegex(RuntimeError, "cancelled by owner"):
                first.result(timeout=1)
            self.assertEqual(self.session.post(self.url + "/result", json={"job_id": old["job_id"], "decision": DECISION}, timeout=2).status_code, 409)
            second = pool.submit(self.relay.broker.infer, {}, 5)
            new = self.session.get(self.url + "/job", timeout=2).json()
            self.assertNotEqual(old["job_id"], new["job_id"])
            self.assertEqual(self.session.post(self.url + "/result", json={"job_id": new["job_id"], "decision": DECISION}, timeout=2).status_code, 200)
            self.assertEqual(second.result(timeout=1), DECISION)


class DecisionTest(unittest.TestCase):
    def test_companion_build_count_reaches_next_model_step(self):
        result = {"action": "build.execute", "state": "succeeded", "result": {"blocks": 2, "placed": 2},
                  "confirmed_changes": {"placed_blocks": 2}}
        original = deepcopy(result)
        payload = controller.model_payload("Continue exploring", snapshot(), ["build.execute"],
                                           [{"result": result}], compact=True)
        recent = json.loads(payload["messages"][1]["content"])["recent_results"]
        self.assertEqual(recent[0]["result"]["blocks"], 2)
        self.assertEqual(recent[0]["confirmed_changes"]["placed_blocks"], 2)
        self.assertEqual(result, original)

    def test_compact_prompt_keeps_goal_totals_and_initial_view_when_recent_history_is_truncated(self):
        initial_actor = {"position": {"x": 0, "y": 64, "z": 0}, "yaw": 90, "pitch": 10, "health": 20}
        current = snapshot()
        current["actor"].update(position={"x": 8, "y": 64, "z": 0}, yaw=-90, pitch=-10)
        first = {"result": {"task_id": "jump-1", "state": "succeeded", "confirmed_changes": {"jumps": 3}}}
        second = {"result": {"task_id": "jump-2", "state": "succeeded", "confirmed_changes": {"jumps": 4}}}
        history = [first, second, second]
        payload = controller.model_payload("Прыгни десять раз", current, ["move.jump"], history,
                                           compact=True, initial_actor=initial_actor)
        user = json.loads(payload["messages"][1]["content"])
        self.assertEqual(user["recent_results"], [{"state": "succeeded", "confirmed_changes": {"jumps": 4}}])
        self.assertEqual(user["goal_progress"], {
            "initial_actor": {"position": {"x": 0, "y": 64, "z": 0}, "yaw": 90, "pitch": 10},
            "completed_steps": 2, "confirmed_jumps": 7})
        self.assertEqual(user["observation"]["actor"]["position"]["x"], 8)

    def test_goal_progress_uses_server_confirmations_even_after_partial_failure(self):
        history = [
            {"decision": {"parameters": {"count": 30}}, "result": {
                "task_id": "partial", "state": "failed", "confirmed_changes": {"jumps": 2}}},
            {"result": {"task_id": "unconfirmed", "state": "failed", "result": {"completed": 30}}},
            {"result": {"task_id": "invalid", "state": "failed", "confirmed_changes": {"jumps": "30"}}},
        ]
        progress = controller.goal_progress(snapshot(), history)
        self.assertEqual(progress["confirmed_jumps"], 2)
        self.assertEqual(progress["completed_steps"], 0)

    def test_item_goal_totals_keep_partial_confirmations_without_double_counting(self):
        partial = {"result": {"task_id": "partial-drop", "state": "cancelled", "confirmed_changes": {"drop": {"item": "minecraft:stone", "count": 2}}}}
        history = [partial, partial,
                   {"result": {"task_id": "pickup", "state": "failed", "confirmed_changes": {"pickup": {"item": "minecraft:apple", "count": 3}}}},
                   {"result": {"task_id": "unconfirmed", "state": "failed", "result": {"collected": 10}}},
                   {"result": {"task_id": "invalid", "state": "failed", "confirmed_changes": {"drop": {"item": "minecraft:stone", "count": True}}}}]
        progress = controller.goal_progress(snapshot(), history)
        self.assertEqual(progress["dropped_items"], {"minecraft:stone": 2})
        self.assertEqual(progress["picked_up_items"], {"minecraft:apple": 3})
        self.assertEqual(progress["completed_steps"], 0)

    def test_compact_result_preserves_confirmation_and_failure_without_task_boilerplate(self):
        history = [{"decision": {"action": "move.jump", "parameters": {"count": 10}, "reason": "repeat me"},
                    "result": {"task_id": "actual-task", "action": "move.jump", "priority": 99,
                               "depends_on": [], "deadline_tick": 1000, "actions": 3, "state": "failed",
                               "error": "timeout", "detail": "Deadline exceeded",
                               "result": {"completed": 3, "source": "integrated_server"},
                               "confirmed_changes": {"jumps": 3}}}]
        original = json.dumps(history, sort_keys=True)
        payload = controller.model_payload("Прыгни десять раз", snapshot(), ["move.jump"], history, compact=True)
        user = json.loads(payload["messages"][1]["content"])
        self.assertEqual(user["recent_results"], [{
            "action": "move.jump", "parameters": {"count": 10}, "state": "failed",
            "error": "timeout", "detail": "Deadline exceeded",
            "result": {"completed": 3, "source": "integrated_server"}, "confirmed_changes": {"jumps": 3}}])
        self.assertEqual(user["goal_progress"]["confirmed_jumps"], 3)
        self.assertEqual(json.dumps(history, sort_keys=True), original)
        user["recent_results"][0]["confirmed_changes"]["jumps"] = 99
        self.assertEqual(history[0]["result"]["confirmed_changes"]["jumps"], 3)

    def test_owner_allowlist_filters_model_actions_and_empty_list_keeps_supported_actions(self):
        capabilities = {"actions": ["look.at", "move.jump", "unknown.action"],
                        "profile": {"allowed_actions": ["move.jump"]}}
        self.assertEqual(controller.available_actions(capabilities), ["move.jump"])
        capabilities["profile"]["allowed_actions"] = []
        self.assertEqual(controller.available_actions(capabilities), ["look.at", "move.jump"])
        capabilities["profile"]["allowed_actions"] = ["combat.attack"]
        self.assertEqual(controller.available_actions(capabilities), [])

    def test_legacy_melee_is_not_offered_as_a_bounded_attack(self):
        caps = {"actions": ["combat.attack", "look.at"]}
        self.assertEqual(controller.available_actions(caps), ["look.at"])
        caps['action_features'] = {"combat.attack": ["confirmed_melee_v1"]}
        self.assertEqual(controller.available_actions(caps), ["combat.attack", "look.at"])
        caps['profile'] = {"allowed_actions": ["look.at"]}
        self.assertEqual(controller.available_actions(caps), ["look.at"])
        for features in (None, [], {"combat.attack": "confirmed_melee_v1"}, {"combat.attack": []}):
            caps.pop('profile', None);caps['action_features'] = features
            self.assertEqual(controller.available_actions(caps), ["look.at"])

    def test_owner_epoch_is_checked_and_passed_to_mod_without_requiring_stable_world_revision(self):
        observed = {**snapshot(), "control_epoch": 7}
        current = {**observed, "revision": 99, "game_tick": 999}
        self.assertEqual(controller.decision_context(observed, current), {"expected_control_epoch": 7})
        with self.assertRaisesRegex(RuntimeError, "Owner control changed"):
            controller.decision_context(observed, {**current, "control_epoch": 8})
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
        state["entities"] = [{"id": str(i), "position": {"x": i, "y": 64, "z": 0}} for i in range(9, -1, -1)]
        state["inventory"] = [{"slot": 17, "type": "minecraft:stick", "count": 2}]
        state["context"].update(menu_id=7, menu_revision=42)
        state["context"]["menu_slots"] = [
            {"slot": i, "type": "minecraft:stick" if i in (8, 50) else "minecraft:air",
             "count": 2 if i in (8, 50) else 0, "player_inventory": i >= 27}
            for i in range(60)]
        history = [{"decision": {"action": "look.at", "parameters": {}},
                    "result": {"state": "succeeded", "result": {"step": step}}} for step in (1, 2, 3)]
        payload = controller.model_payload("look east", state, ["look.at"], history, compact=True)
        user = json.loads(payload["messages"][1]["content"])
        observed = user["observation"]
        self.assertEqual([b["x"] for b in observed["blocks"]], [1, 2, 3, 4, 5, 6])
        self.assertEqual(len(observed["entities"]), 4)
        self.assertEqual([e["id"] for e in observed["entities"]], ["0", "1", "2", "3"])
        self.assertEqual([s["slot"] for s in observed["menu_slots"]], [0, 1, 2, 3, 8, 50])
        self.assertEqual(observed["inventory"][0]["slot"], 17)
        self.assertEqual(observed["context"]["menu_id"], 7)
        self.assertEqual(observed["context"]["menu_revision"], 42)
        self.assertIn("Missing data is unknown", observed["observation_limits"])
        self.assertEqual(user["recent_results"], [{"action": "look.at", "parameters": {},
                                                 "state": "succeeded", "result": {"step": 3}}])


    def test_compact_entities_preserve_living_and_items_despite_projectile_noise(self):
        state = snapshot()
        def entity(identifier, kind, distance):
            return {"entity_id": identifier, "kind": kind, "position": {"x": distance, "y": 64, "z": 0}}
        state["entities"] = [entity(f"arrow{i}", "projectile", .1 * i) for i in range(8)]
        state["entities"] += [entity("cow", "living", 6), entity("sheep", "living", 7), entity("item", "item", 8), entity("pig", "living", 9)]
        original = deepcopy(state)
        observed = controller.compact_snapshot(state, entity_limit=4)
        self.assertEqual([e["entity_id"] for e in observed["entities"]], ["cow", "sheep", "pig", "item"])
        self.assertEqual(state, original)

    def test_compact_pins_only_an_observed_active_target(self):
        state = snapshot()
        state["entities"] = [{"entity_id": str(i), "kind": "living", "position": {"x": i + 1, "y": 64, "z": 0}} for i in range(8)]
        state["active_task"] = {"parameters": {"entity_id": "7"}}
        self.assertEqual(controller.compact_snapshot(state, entity_limit=4)["entities"][0]["entity_id"], "7")
        state["active_task"]["parameters"]["entity_id"] = "unknown"
        self.assertNotIn("unknown", [e["entity_id"] for e in controller.compact_snapshot(state, entity_limit=4)["entities"]])


class ColabRelaySettingsTest(unittest.TestCase):
    """Run the actual notebook connection cell against an authenticated relay."""
    def setUp(self):
        notebook = json.loads((ROOT / "examples/vllm_tpu_colab.ipynb").read_text())
        self.source = next("".join(cell["source"]) for cell in notebook["cells"]
                           if cell["cell_type"] == "code" and "def relay_setting(" in "".join(cell["source"]))
        self.relay = RelayServer(TOKEN, 0)
        self.relay.start()
        self.local_url = f"http://127.0.0.1:{self.relay.port}"
        self.public_url = "https://fixture.ngrok-free.dev"
        self.sessions = []

    def tearDown(self):
        for session in self.sessions:
            session.close()
        self.relay.close()

    def connect(self, secrets, *, namespace=None):
        namespace = namespace if namespace is not None else {"requests": requests}
        userdata = SimpleNamespace(get=MagicMock(side_effect=lambda name: secrets[name]))
        google, colab = ModuleType("google"), ModuleType("google.colab")
        google.colab, colab.userdata = colab, userdata
        session_class = requests.Session

        def new_session():
            session = session_class()
            session.trust_env = False
            original_get = session.get
            # The notebook requires HTTPS; map the tunnel URL onto the real local test relay.
            session.get = MagicMock(side_effect=lambda url, **kwargs: original_get(
                url.replace(self.public_url, self.local_url, 1), **kwargs))
            self.sessions.append(session)
            return session

        output = io.StringIO()
        error = None
        with patch.dict("sys.modules", {"google": google, "google.colab": colab}), \
                patch("builtins.input", return_value=self.public_url) as manual_url, \
                patch("getpass.getpass", return_value=TOKEN) as manual_token, \
                patch.object(requests, "Session", side_effect=new_session), contextlib.redirect_stdout(output):
            try:
                exec(compile(self.source, "notebook-relay-settings", "exec"), namespace)
            except ValueError as caught:
                error = caught
        self.assertNotIn(TOKEN, output.getvalue())
        self.assertNotIn(TOKEN, str(error))
        return namespace, manual_url, manual_token, error

    def test_stored_secrets_connect_again_without_prompting_or_printing_token(self):
        secrets = {"MINEBRIDGE_RELAY_URL": " " + self.public_url + "/ ",
                   "MINEBRIDGE_RELAY_TOKEN": " " + TOKEN + " "}
        namespace, manual_url, manual_token, error = self.connect(secrets)
        self.assertIsNone(error)
        manual_url.assert_not_called()
        manual_token.assert_not_called()
        self.assertEqual(namespace["relay_session"].headers["Authorization"], "Bearer " + TOKEN)
        self.assertEqual(namespace["relay_session"].get.call_args.args[0], self.public_url + "/health")
        first_session = namespace["relay_session"]
        with patch.object(first_session, "close", wraps=first_session.close) as close:
            namespace, manual_url, manual_token, error = self.connect(secrets, namespace=namespace)
            close.assert_called_once()
        self.assertIsNone(error)
        manual_url.assert_not_called()
        manual_token.assert_not_called()
        self.assertIsNot(namespace["relay_session"], first_session)

    def test_missing_token_uses_hidden_input_and_keeps_saved_url(self):
        namespace, manual_url, manual_token, error = self.connect({"MINEBRIDGE_RELAY_URL": self.public_url})
        self.assertIsNone(error)
        manual_url.assert_not_called()
        manual_token.assert_called_once()
        self.assertEqual(namespace["relay_session"].headers["Authorization"], "Bearer " + TOKEN)

    def test_wrong_saved_token_reports_relay_file_without_exposing_credentials(self):
        wrong_token = TOKEN + "-wrong"
        _, manual_url, manual_token, error = self.connect({"MINEBRIDGE_RELAY_URL": self.public_url,
                                                          "MINEBRIDGE_RELAY_TOKEN": wrong_token})
        manual_url.assert_not_called()
        manual_token.assert_not_called()
        self.assertIsInstance(error, ValueError)
        self.assertIn("Windows-контроллера", str(error))
        self.assertIn("relay-файла", str(error))
        self.assertNotIn(wrong_token, str(error))


def snapshot():
    return {"actor_id": "test-actor", "dimension": "minecraft:overworld", "revision": 1, "game_tick": 1,
            "actor": {"position": {"x": 0, "y": 64, "z": 0}, "health": 20},
            "inventory": [], "blocks": [], "entities": [],
            "context": {"menu_id": 0, "menu_revision": 0, "menu_slots": [], "cursor": {"type": "minecraft:air", "count": 0}}}


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


def notebook_function(namespace, filename="vllm_tpu_colab.ipynb"):
    notebook = json.loads((ROOT / "examples" / filename).read_text())
    functions = [node for cell in notebook["cells"] if cell["cell_type"] == "code"
                 for node in ast.parse("".join(cell["source"])).body if isinstance(node, ast.FunctionDef)]
    module = ast.Module(body=functions, type_ignores=[])
    exec(compile(module, "notebook-decision-cell", "exec"), namespace)
    return namespace["process_decisions"]


class VllmStartupTest(unittest.TestCase):
    def setUp(self):
        notebook = json.loads((ROOT / "examples/vllm_tpu_colab.ipynb").read_text())
        sources = ["".join(cell["source"]) for cell in notebook["cells"] if cell["cell_type"] == "code"]
        self.probe_source = next(source for source in sources if "server_environment =" in source)
        self.server_source = next(source for source in sources if "def stop_vllm():" in source)
        self.backends = {
            "tpu": [SimpleNamespace(platform="tpu", device_kind="TPU v5e")],
            "cpu": [SimpleNamespace(platform="cpu", device_kind="cpu")],
        }
        self.environment = {}
        self.device_queries = []

    def devices(self, platform=None):
        # Simulate JAX's backend restriction and default order from JAX_PLATFORMS.
        enabled = self.environment["JAX_PLATFORMS"].split(",")
        platform = platform or enabled[0]
        self.device_queries.append(platform)
        if platform not in enabled or platform not in self.backends:
            raise RuntimeError(f"Unknown backend {platform}. Available backends are {enabled}")
        return self.backends[platform]

    def run_probe(self, command, *, env, check, timeout):
        self.environment = env
        jax = SimpleNamespace(devices=self.devices,
                              default_backend=lambda: self.environment["JAX_PLATFORMS"].split(",")[0])
        with patch.dict("sys.modules", {"jax": jax}), contextlib.redirect_stdout(io.StringIO()):
            exec(compile(command[2], "notebook-tpu-probe", "exec"), {})

    def execute_probe(self):
        namespace = {"os": os, "subprocess": SimpleNamespace(run=self.run_probe),
                     "VENV_PYTHON": Path("/fixture/bin/python")}
        with patch.dict(os.environ, {"JAX_PLATFORMS": "cpu"}):
            exec(compile(self.probe_source, "notebook-tpu-setup", "exec"), namespace)
        return namespace

    def test_probe_enables_cpu_weight_loading_and_keeps_tpu_default(self):
        self.execute_probe()
        self.assertIn("cpu", self.device_queries)
        self.assertEqual(self.devices()[0].platform, "tpu")
        # Reproduce the loader's jax.devices('cpu') call from the uploaded traceback.
        self.assertTrue(self.devices("cpu"))

    def test_probe_rejects_missing_cpu_before_model_start(self):
        for cpu_devices in (None, []):
            with self.subTest(cpu_devices=cpu_devices):
                if cpu_devices is None:
                    self.backends.pop("cpu")
                else:
                    self.backends["cpu"] = cpu_devices
                with self.assertRaisesRegex(RuntimeError, "cpu|CPU"):
                    self.execute_probe()

    def test_probe_rejects_unavailable_or_multiple_tpus(self):
        for tpu_devices in (None, [], self.backends["cpu"], self.backends["tpu"] * 2):
            with self.subTest(tpu_devices=tpu_devices):
                if tpu_devices is None:
                    self.backends.pop("tpu")
                else:
                    self.backends["tpu"] = tpu_devices
                with self.assertRaises(RuntimeError):
                    self.execute_probe()

    def test_server_receives_checked_backends_and_disables_chunked_images(self):
        namespace = self.execute_probe()
        check_session = MagicMock()
        check_session.__enter__.return_value = check_session
        check_session.get.side_effect = requests.ConnectionError("no existing server")
        model_session = MagicMock()
        model_session.get.return_value = SimpleNamespace(
            ok=True, json=lambda: {"data": [{"id": "google/gemma-4-E2B-it"}]})
        sessions = MagicMock(side_effect=[check_session, model_session])
        process = MagicMock()
        process.poll.return_value = None

        def start_server(command, **kwargs):
            self.environment = kwargs["env"]
            self.assertTrue(self.devices("cpu"))  # CPU staging must also work in the server.
            self.assertEqual(self.devices()[0].platform, "tpu")
            self.assertIn("--disable-chunked-mm-input", command)
            return process

        popen = MagicMock(side_effect=start_server)
        namespace.update({"VENV": Path("/fixture"), "Path": Path, "json": json, "time": time,
                          "requests": SimpleNamespace(Session=sessions, RequestException=requests.RequestException),
                          "subprocess": SimpleNamespace(Popen=popen, STDOUT=-2)})
        log = io.BytesIO()
        try:
            with patch.object(Path, "open", return_value=log), contextlib.redirect_stdout(io.StringIO()):
                exec(compile(self.server_source, "notebook-vllm-startup", "exec"), namespace)
            self.assertEqual(popen.call_count, 1)
            self.assertIs(popen.call_args.kwargs["env"], namespace["server_environment"])
            self.assertTrue(popen.call_args.kwargs["start_new_session"])
        finally:
            log.close()


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

    def test_continuous_worker_passes_thirty_decisions_and_remains_interruptible(self):
        relay = MagicMock()
        response = MagicMock(status_code=200)
        response.json.return_value = {"job_id": "job", "payload": self.payload}
        relay.get.side_effect = [response] * 32 + [KeyboardInterrupt()]
        relay.post.return_value.status_code = 200
        self.namespace.update(relay_session=relay, RELAY_URL="http://fixture")
        infer = MagicMock(return_value={"action": "wait", "parameters": {}, "reason": "ready"})
        self.namespace["infer_decision"] = infer
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(KeyboardInterrupt):
            self.worker(0, stop_on_wait=False)
        self.assertEqual(infer.call_count, 32)
        self.assertEqual(relay.post.call_count, 32)
        for invalid in (-1, 31, True, 1.5):
            with self.assertRaises(ValueError):
                self.worker(invalid)

    def test_large_inventory_is_reduced_before_generation_without_changing_goal_totals(self):
        state = snapshot()
        state["inventory"] = [{"slot": i, "type": "minecraft:stone", "count": 64} for i in range(36)]
        self.payload = controller.model_payload("look east", state, ["look.at"], [], compact=True)
        original = self.session.post
        def counted_post(url, **kwargs):
            if url.endswith("/tokenize"):
                data = json.loads(kwargs["json"]["messages"][1]["content"])
                self.model.count = 1900 if len(data["observation"]["inventory"]) > 4 else 1000
            return original(url, **kwargs)
        with patch.object(self.session, "post", side_effect=counted_post):
            self.assertEqual(self.namespace["infer_decision"](self.payload), DECISION)
        self.assertEqual(len(self.model.payloads), 1)
        data = json.loads(self.model.payloads[0]["messages"][1]["content"])
        self.assertEqual(data["goal"], "look east")
        self.assertEqual(data["goal_progress"], json.loads(self.payload["messages"][1]["content"])["goal_progress"])
        self.assertTrue(data["observation"]["inventory_truncated"])
        self.assertGreater(data["observation"]["inventory_omitted"], 0)
        self.assertLessEqual(len(data["observation"]["inventory"]), 4)
        self.assertEqual(len(state["inventory"]), 36)

    def test_query_and_semantic_item_selection_use_native_json_schema(self):
        for decision in [
            {"action": "query", "parameters": {"operation": "map.nearest", "arguments": {"x": 0, "y": 64, "z": 0, "block": "minecraft:oak_log"}}, "reason": "find wood"},
            {"action": "inventory.select_item", "parameters": {"item": "minecraft:oak_log", "hotbar_slot": 2}, "reason": "prepare"},
        ]:
            with self.subTest(action=decision["action"]):
                self.model.content = json.dumps(decision)
                payload = controller.model_payload("prepare wood", snapshot(), [decision["action"]], [], compact=True, queries=["map.nearest"])
                self.assertEqual(self.namespace["infer_decision"](payload), decision)

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
            (EXPRESSION_REPLY, "stop"),
            (json.dumps({**DECISION, "parameters": {"target": {"x": "0 + 3", "y": 65, "z": 0}}}), "stop"),
            (json.dumps({**DECISION, "parameters": {"target": {"x": True, "y": 65, "z": 0}}}), "stop"),
        ]
        for content, finish in cases:
            with self.subTest(content=content, finish=finish):
                self.model.content, self.model.finish_reason = content, finish
                with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(ValueError):
                    self.namespace["infer_decision"](self.payload)

    def test_expression_reply_is_retried_with_same_observation_and_numeric_coordinates(self):
        self.payload = controller.model_payload(
            "Посмотри на точку на три блока восточнее текущей позиции игрока",
            snapshot(), ["look.at"], [], compact=True)
        original = json.dumps(self.payload, ensure_ascii=False)

        def reply():
            self.model.content = EXPRESSION_REPLY if len(self.model.payloads) == 1 else json.dumps(DECISION)

        self.model.before_reply = reply
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(self.namespace["infer_decision"](self.payload), DECISION)
        self.assertEqual(len(self.model.payloads), 2)
        self.assertEqual(len(self.model.token_requests), 2)
        first, second = self.model.payloads
        self.assertEqual(first["messages"][1], second["messages"][1])
        self.assertNotEqual(first["messages"][0], second["messages"][0])
        self.assertIn("Expecting ',' delimiter", output.getvalue())
        self.assertIn(EXPRESSION_REPLY, output.getvalue())
        self.assertEqual(self.namespace["LAST_MODEL_REPLY"], json.dumps(DECISION))
        self.assertEqual(json.dumps(self.payload, ensure_ascii=False), original)

    def test_repair_context_is_checked_before_second_generation(self):
        self.model.content = EXPRESSION_REPLY

        def increase_context_count():
            self.model.count = 1793

        self.model.before_reply = increase_context_count
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaisesRegex(ValueError, "Контекст слишком длинный"):
            self.namespace["infer_decision"](self.payload)
        self.assertEqual(len(self.model.payloads), 1)
        self.assertEqual(len(self.model.token_requests), 2)

    def test_retry_uses_remaining_time_and_stops_when_budget_is_exhausted(self):
        self.model.content = EXPRESSION_REPLY
        clock = MagicMock(side_effect=[100, 100, 101, 102, 106])
        self.namespace["time"] = SimpleNamespace(monotonic=clock)
        with contextlib.redirect_stdout(io.StringIO()), patch.object(self.session, "post", wraps=self.session.post) as post, \
                self.assertRaisesRegex(ValueError, "Истекло время"):
            self.namespace["infer_decision"](self.payload)
        self.assertEqual([call.kwargs["timeout"] for call in post.call_args_list], [5, 4])
        self.assertEqual(len(self.model.payloads), 1)
        self.assertEqual(len(self.model.token_requests), 1)

    def test_persistent_invalid_json_delivers_error_without_a_decision(self):
        relay = RelayServer(TOKEN, 0)
        relay.start()
        self.model.content = EXPRESSION_REPLY
        session = requests.Session()
        session.trust_env = False
        session.headers["Authorization"] = "Bearer " + TOKEN
        self.namespace.update(RELAY_URL=f"http://127.0.0.1:{relay.port}", relay_session=session)
        try:
            with ThreadPoolExecutor(max_workers=1) as pool, contextlib.redirect_stdout(io.StringIO()):
                future = pool.submit(relay.broker.infer, self.payload, 5)
                self.worker(1)
                with self.assertRaisesRegex(RuntimeError, "2 попытки"):
                    future.result(timeout=2)
            self.assertEqual(len(self.model.payloads), 2)
            self.assertEqual(len(self.model.token_requests), 2)
            self.assertNotIn("decision", relay.broker.completed[1])
            self.assertIn("error", relay.broker.completed[1])
        finally:
            session.close()
            relay.close()

    def test_optional_structured_output_uses_openai_schema_format(self):
        self.namespace["USE_STRUCTURED_OUTPUTS"] = True
        self.assertEqual(self.namespace["infer_decision"](self.payload), DECISION)
        posted = self.model.payloads[0]
        self.assertEqual(posted["response_format"]["json_schema"]["schema"], self.payload["format"])
        self.assertEqual(posted["max_tokens"], 256)
        self.assertEqual(posted["chat_template_kwargs"], {"enable_thinking": False})
        self.assertNotIn("options", posted)
        self.assertNotIn("format", posted)

    def test_jump_count_must_be_an_integer_within_bounds(self):
        payload = controller.model_payload("Прыгни десять раз", snapshot(), ["move.jump"], [], compact=True)
        for count in ("10", True, 0, 31, 1.5):
            self.model.content = json.dumps({"action":"move.jump","parameters":{"count":count},"reason":"jump"})
            with contextlib.redirect_stdout(io.StringIO()), self.assertRaisesRegex(ValueError, "count"):
                self.namespace["infer_decision"](payload)
        decision = {"action":"move.jump","parameters":{"count":10},"reason":"jump"}
        self.model.content = json.dumps(decision)
        self.assertEqual(self.namespace["infer_decision"](payload), decision)

    def test_new_game_actions_pass_through_vllm_without_parameter_rewriting(self):
        cases = [
            ("move.relative", {"forward": 2, "right": -1, "crouch": True}),
            ("move.crouch", {"ticks": 40}),
            ("look.turn", {"delta_yaw": 90, "delta_pitch": -15}),
            ("item.drop", {"count": 5, "expected_item": "minecraft:stone"}),
            ("item.pickup", {"entity_id": "418f10e9-bc78-4d9c-bb26-6a9cfa352b69"}),
            ("item.use", {"hand": "off", "duration_ticks": 40, "target": {"x": 3, "y": 65, "z": 0}}),
        ]
        for action, parameters in cases:
            with self.subTest(action=action):
                payload = controller.model_payload("test new game action", snapshot(), [action], [], compact=True)
                decision = {"action": action, "parameters": parameters, "reason": "documented action"}
                self.model.content = json.dumps(decision)
                self.assertEqual(self.namespace["infer_decision"](payload), decision)

    def test_bow_parameters_reject_unsupported_modes_before_game_execution(self):
        target = "418f10e9-bc78-4d9c-bb26-6a9cfa352b69"
        allowed = ["combat.ranged"]
        schema = controller.model_payload("shoot once", snapshot(), allowed, [], compact=True)["format"]
        invalid = [{}, {"entity_id": "fake"}]
        invalid += [{"entity_id": target, key: value} for key, values in (
            ("charge_ticks", [19, 61, True, "20", 20.5]),
            ("wait_ticks", [9, 101, False]), ("max_distance", [0, 33, True, "24"]),
            ("hand", ["off"]), ("shots", [2]), ("count", [1]), ("chase", [False])
        ) for value in values]
        for parameters in invalid:
            decision = {"action": "combat.ranged", "parameters": parameters, "reason": "shoot"}
            response = {"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(decision)}}]}
            with self.subTest(parameters=parameters):
                with self.assertRaises(ValueError):
                    controller.validate_decision(decision, allowed)
                with self.assertRaises(ValueError):
                    self.namespace["parse_decision"](response, schema)
        for parameters in ({"entity_id": target}, {"entity_id": target, "charge_ticks": 60, "wait_ticks": 100, "max_distance": 32}):
            decision = {"action": "combat.ranged", "parameters": parameters, "reason": "shoot"}
            self.model.content = json.dumps(decision)
            self.assertEqual(self.namespace["infer_decision"](controller.model_payload("shoot once", snapshot(), allowed, [], compact=True)), decision)

    def test_combat_goal_progress_counts_receipts_once_and_keeps_misses_distinct(self):
        miss = {"result": {"task_id": "one", "state": "failed", "confirmed_changes": {"shots": 1}}}
        hit = {"result": {"task_id": "two", "state": "succeeded", "confirmed_changes": {"shots": 1, "hits": 1, "damage": 3.5}}}
        unknown = {"result": {"task_id": "three", "state": "cancelled", "confirmed_changes": {"attack_requests": 1}}}
        progress = controller.goal_progress(snapshot(), [miss, hit, hit, unknown])
        self.assertEqual(progress["confirmed_shots"], 2)
        self.assertEqual(progress["confirmed_hits"], 1)
        self.assertEqual(progress["confirmed_damage"], 3.5)
        self.assertEqual(progress["completed_steps"], 1)

    def test_crossbow_load_receipt_is_separate_from_a_shot_and_not_counted_twice(self):
        loaded = {"result": {"task_id": "loaded", "state": "failed", "confirmed_changes": {"crossbow_loads": 1}}}
        fired = {"result": {"task_id": "fired", "state": "succeeded", "confirmed_changes": {"shots": 1}}}
        invalid = {"result": {"task_id": "invalid", "confirmed_changes": {"crossbow_loads": True}}}
        progress = controller.goal_progress(snapshot(), [loaded, loaded, fired, invalid])
        self.assertEqual(progress["confirmed_crossbow_loads"], 1)
        self.assertEqual(progress["confirmed_shots"], 1)

    def test_interaction_parameters_are_identical_in_notebook_and_controller(self):
        namespace = {}
        notebook_function(namespace)
        validate = namespace["validate_item_parameters"]
        entity_id = "418f10e9-bc78-4d9c-bb26-6a9cfa352b69"
        for action in ("world.interact", "container.open"):
            for function in (validate, controller.validate_item_parameters):
                function(action, {"entity_id": entity_id, "hand": "off"})
                function(action, {"position": {"x": 1, "y": 64, "z": -2}})
                for bad in ({}, {"entity_id": entity_id, "position": {"x": 1, "y": 64, "z": 2}},
                            {"entity_id": entity_id, "hand": "both"}, {"entity_id": "invented"},
                            {"position": {"x": 1.5, "y": 64, "z": 2}}, {"position": {"x": True, "y": 64, "z": 2}}):
                    with self.assertRaises(ValueError):
                        function(action, bad)

    def test_confirmed_interactions_are_deduplicated_and_booleans_are_rejected(self):
        result = {"result": {"task_id": "fed", "state": "succeeded", "confirmed_changes": {"interactions": 1}}}
        invalid = {"result": {"task_id": "invalid", "confirmed_changes": {"interactions": True}}}
        progress = controller.goal_progress(snapshot(), [result, result, invalid])
        self.assertEqual(progress["confirmed_interactions"], 1)
        self.assertEqual(progress["completed_steps"], 1)

    def test_compact_interaction_proof_is_kept_once_without_changing_the_full_result(self):
        receipt = {"outcome": "entity_changed", "target": "entity:observed", "source": "integrated_server_interaction"}
        result = {"state": "failed", "error": "interaction_unconfirmed", "result": receipt,
                  "confirmed_changes": {"interactions": 1, "interaction": receipt}}
        original = deepcopy(result)
        compact = controller.compact_result(result)
        self.assertEqual(compact["confirmed_changes"], {"interactions": 1})
        self.assertEqual(compact["result"], receipt)
        self.assertEqual(result, original)

    def test_follow_parameters_are_identical_in_controller_and_notebook(self):
        target = "418f10e9-bc78-4d9c-bb26-6a9cfa352b69"
        schema = controller.model_payload("follow", snapshot(), ["move.follow"], [], compact=True)["format"]
        invalid = [{}, {"entity_id": "unknown"}]
        invalid += [{"entity_id": target, key: value} for key, values in (
            ("ticks", [0, 1201, True, "20", 20.5]), ("distance", [.3, 17, True, "3"]),
            ("sprint", [1]), ("crouch", ["true"])
        ) for value in values]
        invalid += [{"entity_id": target, "sprint": True, "crouch": True}]
        for parameters in invalid:
            decision = {"action": "move.follow", "parameters": parameters, "reason": "follow"}
            response = {"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(decision)}}]}
            with self.subTest(parameters=parameters):
                with self.assertRaises(ValueError):controller.validate_decision(decision, ["move.follow"])
                with self.assertRaises(ValueError):self.namespace["parse_decision"](response, schema)
        decision = {"action": "move.follow", "parameters": {"entity_id": target, "ticks": 100, "distance": 3}, "reason": "follow"}
        self.model.content = json.dumps(decision)
        self.assertEqual(self.namespace["infer_decision"](controller.model_payload("follow", snapshot(), ["move.follow"], [], compact=True)), decision)

    def test_follow_progress_preserves_partial_receipts_without_double_counting(self):
        partial = {"result": {"task_id": "partial", "state": "cancelled", "confirmed_changes": {"follow_ticks": 20}}}
        history = [partial, partial, {"result": {"task_id": "done", "state": "succeeded", "confirmed_changes": {"follow_ticks": 40}}},
                   {"result": {"task_id": "unknown", "state": "failed", "result": {"confirmed_follow_ticks": 200}}},
                   {"result": {"task_id": "invalid", "state": "failed", "confirmed_changes": {"follow_ticks": True}}}]
        progress = controller.goal_progress(snapshot(), history)
        self.assertEqual(progress["confirmed_follow_ticks"], 60)
        self.assertEqual(progress["completed_steps"], 1)

    def test_item_parameters_are_rejected_in_both_notebook_and_controller(self):
        cases = [("item.drop", {"count": count}) for count in (0, 65, True, "5", 1.5)]
        cases += [("item.drop", {"expected_item": "stone;command"}), ("item.pickup", {}),
                  ("item.drop", {"hand": "off"}), ("item.pickup", {"entity_id": "418f10e9-bc78-4d9c-bb26-6a9cfa352b69", "count": 1}),
                  ("item.pickup", {"entity_id": "invented-target"}), ("item.pickup", {"entity_id": 3}),
                  ("item.use", {"hand": "left"}), ("item.use", {"duration_ticks": True}),
                  ("item.use", {"duration_ticks": 1201}), ("item.use", {"target": {"x": "1+3", "y": 64, "z": 0}})]
        schema = controller.model_payload("items", snapshot(), ["item.drop", "item.pickup", "item.use"], [], compact=True)["format"]
        for action, parameters in cases:
            decision = {"action": action, "parameters": parameters, "reason": "items"}
            response = {"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(decision)}}]}
            with self.subTest(action=action, parameters=parameters):
                with self.assertRaises(ValueError):
                    self.namespace["parse_decision"](response, schema)
                with self.assertRaises(ValueError):
                    controller.validate_decision(decision, schema["properties"]["action"]["enum"])

    def test_relative_movement_rejects_invalid_numbers_flags_and_distances(self):
        cases = [
            {}, {"forward": "3"}, {"forward": True}, {"forward": 17},
            {"forward": 12, "right": 12}, {"forward": 1, "sprint": "true"},
            {"forward": 1, "crouch": 1}, {"forward": 1, "sprint": True, "crouch": True},
        ]
        schema = controller.model_payload("move", snapshot(), ["move.relative"], [], compact=True)["format"]
        for parameters in cases:
            with self.subTest(parameters=parameters):
                value = {"action": "move.relative", "parameters": parameters, "reason": "move"}
                response = {"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(value)}}]}
                with self.assertRaises(ValueError):
                    self.namespace["parse_decision"](response, schema)

    def test_crouch_duration_and_relative_turn_are_strictly_bounded(self):
        cases = [
            ("move.crouch", {"ticks": "20"}), ("move.crouch", {"ticks": True}),
            ("move.crouch", {"ticks": 0}), ("move.crouch", {"ticks": 201}),
            ("move.crouch", {"ticks": 1.5}), ("look.turn", {}),
            ("look.turn", {"delta_yaw": "90"}), ("look.turn", {"delta_pitch": False}),
            ("look.turn", {"delta_yaw": 181}), ("look.turn", {"delta_pitch": -91}),
        ]
        schema = controller.model_payload("turn/crouch", snapshot(), ["move.crouch", "look.turn"], [], compact=True)["format"]
        for action, parameters in cases:
            with self.subTest(action=action, parameters=parameters):
                value = {"action": action, "parameters": parameters, "reason": "bounded action"}
                response = {"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(value)}}]}
                with self.assertRaises(ValueError):
                    self.namespace["parse_decision"](response, schema)

    def test_move_to_rejects_invalid_crouch_sprint_combination(self):
        target = {"x": 1, "y": 64, "z": 0}
        schema = controller.model_payload("walk", snapshot(), ["move.to"], [], compact=True)["format"]
        for flags in ({"crouch": "true"}, {"sprint": 1}, {"sprint": True, "crouch": True}):
            with self.subTest(flags=flags):
                value = {"action": "move.to", "parameters": {"target": target, **flags}, "reason": "walk"}
                response = {"choices": [{"finish_reason": "stop", "message": {"content": json.dumps(value)}}]}
                with self.assertRaises(ValueError):
                    self.namespace["parse_decision"](response, schema)

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
    async def integration(self, execute=False, vision=False, model_error=False, decision=DECISION, task_ticks=200, max_actions=4096, owner_restrictions=False):
        submitted = []
        capabilities = {"actions": [decision["action"]], "actor_id": "test-actor", "armed": True}
        if decision["action"] == "combat.attack":
            capabilities["action_features"] = {"combat.attack": ["confirmed_melee_v1"]}
        if owner_restrictions:
            capabilities["actions"].append("combat.attack")
            capabilities["profile"] = {"allowed_actions": [decision["action"]]}

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
            args.task_ticks, args.max_actions = task_ticks, max_actions
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

                model = FakeVllm(error=model_error)
                model.content = json.dumps(decision)
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
                                         "MODEL": "fixture-model", "MODEL_URL": model.url,
                                         "MAX_MODEL_LEN": 2048, "MAX_NEW_TOKENS": 256, "MODEL_TIMEOUT": 5,
                                         "ENABLE_VISION": vision, "USE_STRUCTURED_OUTPUTS": False,
                                         "model_session": model_session,
                                         "RELAY_URL": f"http://127.0.0.1:{holder['relay'].port}", "relay_session": session}
                            worker = notebook_function(namespace)
                            with patch.dict("os.environ", {"NO_PROXY": "127.0.0.1,localhost"}):
                                outcomes = await asyncio.wait_for(asyncio.gather(
                                    task, asyncio.to_thread(worker, 1), return_exceptions=model_error), 15)
                            if model_error:
                                self.assertIsInstance(outcomes[0], RuntimeError)
                                self.assertIn("vLLM", str(outcomes[0]))
                    self.assertEqual(len(model.payloads), 1)
                    self.assertEqual(model.payloads[0]["model"], "fixture-model")
                    self.assertEqual(len(model.token_requests), 1)
                    self.assertNotIn("Authorization", model.headers[0])
                    self.assertNotIn(holder["token"], json.dumps(model.payloads[0]))
                    self.assertIn(json.dumps([decision["action"], "wait", "finish"], separators=(",", ":")), model.payloads[0]["messages"][0]["content"])
                    model_input = json.loads(model.payloads[0]["messages"][1]["content"]) if not vision else json.loads(model.payloads[0]["messages"][1]["content"][-1]["text"])
                    self.assertEqual(model_input["task_limits"], {"timeout_ticks": task_ticks, "max_actions": max_actions})
                    self.assertEqual(len(submitted), 1 if execute and not model_error else 0)
                    if execute and not model_error:
                        self.assertEqual(submitted[0]["action"], decision["action"])
                        self.assertEqual(submitted[0]["parameters"], decision["parameters"])
                        self.assertEqual(submitted[0]["timeout_ticks"], task_ticks)
                        self.assertEqual(submitted[0]["max_actions"], max_actions)
                    if vision:
                        image = model.payloads[0]["messages"][1]["content"][0]
                        self.assertEqual(image["type"], "image_url")
                        encoded = image["image_url"]["url"].removeprefix("data:image/png;base64,")
                        self.assertEqual(base64.b64decode(encoded), (ROOT / "docs/media/native-frame.png").read_bytes())
                finally:
                    session.close()
                    model.close()

    async def test_vllm_preview_uses_openai_api_without_submitting_game_action(self):
        await self.integration()

    async def test_vllm_mining_flags_cross_notebook_relay_and_bridge_unchanged(self):
        await self.integration(execute=True, decision={"action": "block.break", "parameters": {"position": {"x": 0, "y": 56, "z": 2}, "auto_tool": True, "require_tool": True}, "reason": "mine the observed block"})

    async def test_vllm_bounded_melee_crosses_notebook_relay_and_bridge_unchanged(self):
        await self.integration(execute=True, decision={"action": "combat.attack", "parameters": {"entity_id": "418f10e9-bc78-4d9c-bb26-6a9cfa352b69", "hits": 1, "chase": True, "max_chase_distance": 12, "stop_health": 6}, "reason": "approach and hit once"})

    async def test_vllm_named_equipment_crosses_notebook_relay_and_bridge_unchanged(self):
        await self.integration(execute=True, decision={"action": "inventory.equip", "parameters": {"from_slot": 12, "equipment_slot": "head"}, "reason": "equip helmet"})

    async def test_vllm_whole_stack_swap_crosses_notebook_relay_and_bridge_unchanged(self):
        await self.integration(execute=True, decision={"action": "inventory.swap", "parameters": {"from_slot": 5, "to_slot": 12}, "reason": "remove helmet"})

    async def test_vllm_execution_submits_decision_and_reads_result(self):
        await self.integration(execute=True)

    async def test_owner_restrictions_are_applied_to_the_model_action_schema(self):
        await self.integration(execute=True, owner_restrictions=True)

    async def test_explicit_task_budget_is_sent_to_model_and_preserved_on_submission(self):
        await self.integration(execute=True, task_ticks=60, max_actions=1)

    async def test_vllm_jump_count_is_submitted_as_one_task(self):
        await self.integration(execute=True,decision={
            "action":"move.jump","parameters":{"count":10},"reason":"Jump ten times"})

    async def test_vllm_vision_preserves_png_in_openai_image_message(self):
        await self.integration(vision=True)

    async def test_vllm_failure_never_submits_game_action(self):
        await self.integration(execute=True, model_error=True)

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


class InventoryDecisionTest(unittest.TestCase):
    def test_context_shrinking_keeps_recent_plan_and_station_intact(self):
        notebook = json.loads((ROOT / 'examples/vllm_tpu_colab.ipynb').read_text())
        source = next(''.join(c['source']) for c in notebook['cells'] if 'def shrink_observation(' in ''.join(c['source']))
        function = next(n for n in ast.parse(source).body if isinstance(n, ast.FunctionDef) and n.name == 'shrink_observation')
        namespace = {'json': json}
        exec(compile(ast.Module(body=[function], type_ignores=[]), '<notebook shrink>', 'exec'), namespace)
        recent = [{"operation": "craft.plan", "result": {"station": {"position": {"x": 0, "y": 56, "z": 2}}, "stages": [{"ingredients": ["minecraft:oak_log"]}]*8}}]
        data = {"goal": "craft pickaxe", "goal_progress": {"confirmed_crafts": 3}, "recent_results": recent, "observation": {"blocks": [1, 2]}}
        body = {"messages": [{"role": "user", "content": json.dumps(data)}]}
        self.assertTrue(namespace['shrink_observation'](body))
        reduced = json.loads(body['messages'][0]['content'])
        self.assertEqual(reduced['recent_results'], recent)
        self.assertEqual(reduced['goal_progress'], data['goal_progress'])
        self.assertFalse(namespace['shrink_observation'](body))

    def test_query_history_preserves_operation_for_filtered_compaction(self):
        history = [{"decision": {"action": "query", "parameters": {"operation": "craft.plan", "arguments": {"item": "minecraft:stick"}}},
                    "result": {"operation": "craft.plan", "state": "queried", "result": {"station_open_actions": 1}}}]
        self.assertEqual(controller.compact_recent_results(history)[0]['operation'], 'craft.plan')

    def test_craft_progress_retains_confirmed_outputs_across_partial_results(self):
        history = [{"result": {"task_id": state, "state": state, "confirmed_changes": {"crafts_confirmed": count}}}
                   for state, count in (("failed", 1), ("succeeded", 4), ("cancelled", True))]
        history.append(deepcopy(history[0]))
        self.assertEqual(controller.goal_progress(snapshot(), history)["confirmed_crafts"], 5)

    def test_craft_history_keeps_counter_last_receipt_and_preserves_full_api_result(self):
        receipts = [{"stage": n, "item": "minecraft:oak_planks", "output": 4, "source": "integrated_server"} for n in range(16)]
        full = {"action": "craft.execute", "state": "cancelled", "result": {"stages": [{"ingredients": ["minecraft:oak_log"]}]*8},
                "confirmed_changes": {"crafts_confirmed": 16, "craft_stages": receipts}}
        original = deepcopy(full)
        compact = controller.compact_result(full)
        self.assertEqual(compact["confirmed_changes"], {"crafts_confirmed": 16, "last_craft": receipts[-1], "craft_stages_omitted": 15})
        self.assertEqual(compact["result"], {"planned_stage_count": 8})
        self.assertEqual(full, original)

    def setUp(self):
        notebook = json.loads((ROOT / 'examples/vllm_tpu_colab.ipynb').read_text())
        source = next(''.join(cell['source']) for cell in notebook['cells'] if cell['cell_type'] == 'code' and 'def validate_item_parameters(' in ''.join(cell['source']))
        tree = ast.parse(source)
        function = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == 'validate_item_parameters')
        namespace = {}
        exec(compile(ast.Module(body=[function], type_ignores=[]), '<notebook inventory validator>', 'exec'), namespace)
        self.validators = [controller.validate_item_parameters, namespace['validate_item_parameters']]

    def test_craft_station_and_plan_parameters_match_in_notebook_and_controller(self):
        valid = {"item": "minecraft:wooden_pickaxe", "count": 1, "auto_station": False, "station_position": {"x": 0, "y": 56, "z": 2}}
        invalid = ({}, {**valid, "item": "pickaxe"}, {**valid, "count": True}, {**valid, "count": 4097},
                   {**valid, "auto_station": "true"}, {**valid, "station_position": {"x": 0, "y": 56}},
                   {**valid, "station_position": {"x": True, "y": 56, "z": 2}},
                   {**valid, "station_position": {"x": 0, "y": 2049, "z": 2}})
        for validate in self.validators:
            for action, parameters in (("craft.execute", valid), ("query", {"operation": "craft.plan", "arguments": valid})):
                original = deepcopy(parameters)
                validate(action, parameters)
                self.assertEqual(parameters, original)
            for parameters in invalid:
                with self.subTest(parameters=parameters), self.assertRaises(ValueError):
                    validate("craft.execute", parameters)
                with self.subTest(query=parameters), self.assertRaises(ValueError):
                    validate("query", {"operation": "craft.plan", "arguments": parameters})

    def test_inventory_proof_is_compacted_once_with_counter_and_full_source_untouched(self):
        proof = {"source": "integrated_server", "source_slot": {"slot": 9, "type": "minecraft:iron_helmet", "count": 1}, "target_slot": {"slot": 5, "type": "minecraft:diamond_helmet", "count": 1}}
        for key, counters in (("inventory_swap", {"inventory_swaps": 1, "equips": 1}), ("transfer", {"transfer_count": 1})):
            full = {"result": proof, "confirmed_changes": {**counters, key: proof}}
            original = deepcopy(full)
            compact = controller.compact_result(full)
            self.assertEqual(compact["result"], proof)
            self.assertEqual(compact["confirmed_changes"], counters)
            self.assertEqual(full, original)

    def test_filtered_inventory_query_keeps_source_separate_from_five_equipment_cells(self):
        gear = [{"slot": 5+i, "equipment_slot": name, "role": "armor" if i<4 else "offhand", "type": "minecraft:air", "count": 0}
                for i, name in enumerate(('head', 'chest', 'legs', 'feet', 'offhand'))]
        source = {"slot": 34, "inventory_slot": 34, "role": "main", "type": "minecraft:diamond_helmet", "count": 1}
        query = {"operation": "inventory.inspect", "state": "queried", "result": {"inventory": [], "menu": {"menu_id": 0, "menu_revision": 42, "menu_slots": gear+[source], "item_filter": "minecraft:diamond_helmet"}}}
        original = deepcopy(query)
        compact = controller.compact_result(query)
        menu = compact['result']['menu']
        self.assertEqual(menu['menu_slots'], [source])
        self.assertEqual(set(menu['equipment_slots']), {'head','chest','legs','feet','offhand'})
        self.assertEqual(menu['equipment_slots']['offhand']['slot'], 9)
        self.assertEqual(query, original)

    def test_context_shrinking_preserves_equipment_cells_and_can_reduce_other_collections(self):
        notebook = json.loads((ROOT / 'examples/vllm_tpu_colab.ipynb').read_text())
        source = next(''.join(cell['source']) for cell in notebook['cells'] if 'def shrink_observation(' in ''.join(cell['source']))
        function = next(node for node in ast.parse(source).body if isinstance(node, ast.FunctionDef) and node.name == 'shrink_observation')
        namespace = {"json": json}
        exec(compile(ast.Module(body=[function], type_ignores=[]), '<context shrinking>', 'exec'), namespace)
        gear = [{"slot": 5+i, "role": "armor" if i<4 else "offhand", "count": 0} for i in range(5)]
        slots = gear + [{"slot": 9+i, "role": "main", "type": "minecraft:stone", "count": 64} for i in range(36)]
        data = {"goal": "equip helmet", "goal_progress": {"confirmed_equips": 1}, "recent_results": [], "observation": {"menu_slots": slots}}
        body = {"messages": [{"role": "user", "content": json.dumps(data)}]}
        for _ in range(6):
            changed = namespace['shrink_observation'](body)
            fitted = json.loads(body['messages'][0]['content'])
            self.assertEqual([slot for slot in fitted['observation']['menu_slots'] if slot['role'] in ('armor','offhand')], gear)
            self.assertEqual(fitted['goal_progress'], data['goal_progress'])
            self.assertEqual(fitted['goal'], data['goal'])
            if not changed:break
        self.assertFalse(namespace['shrink_observation'](body))
        fitted['observation']['blocks'] = [{"x": i} for i in range(8)]
        body['messages'][0]['content'] = json.dumps(fitted)
        self.assertTrue(namespace['shrink_observation'](body))
        self.assertEqual(len(json.loads(body['messages'][0]['content'])['observation']['blocks']), 4)

    def test_melee_policies_are_identical_and_preserve_explicit_hit_count(self):
        target = "418f10e9-bc78-4d9c-bb26-6a9cfa352b69"
        good = {"entity_id": target, "hits": 1, "chase": True, "max_chase_distance": 12, "stop_health": 6}
        invalid = [{}, {"entity_id": "unknown"}, {"entity_id": target.replace("-", "")}]
        invalid += [{**good, key: value} for key, values in (
            ("hits", [0, 65, True, 1.5, "1"]), ("chase", [1]), ("sprint", ["true"]), ("crouch", [1]),
            ("max_chase_distance", [0, 33, True, float('nan')]), ("stop_health", [-1, 1025, True, float('inf')]),
            ("hand", ["off"]), ("count", [1]), ("shots", [1])
        ) for value in values]
        invalid += [{**good, "sprint": True, "crouch": True}]
        for validate in self.validators:
            original = deepcopy(good)
            validate('combat.attack', good)
            validate('combat.attack', {"entity_id": target})
            self.assertEqual(good, original)
            for parameters in invalid:
                with self.subTest(parameters=parameters), self.assertRaises(ValueError):
                    validate('combat.attack', parameters)

    def test_mining_parameters_are_checked_identically_without_rewriting(self):
        good = {"position": {"x": 0, "y": 56, "z": 2}, "auto_tool": True, "require_tool": True}
        for validate in self.validators:
            original = deepcopy(good)
            validate('block.break', good)
            self.assertEqual(good, original)
            for parameters in ({}, {"position": {"x": .5, "y": 56, "z": 2}}, {"position": {"x": True, "y": 56, "z": 2}},
                               {**good, "auto_tool": 1}, {**good, "require_tool": "true"}):
                with self.subTest(parameters=parameters), self.assertRaises(ValueError):
                    validate('block.break', parameters)

    def test_mining_progress_counts_server_facts_even_in_partial_terminal_tasks(self):
        fact = {"task_id": "mined", "state": "cancelled", "confirmed_changes": {"mined_blocks": 1}}
        history = [{"result": fact}, {"result": fact}, {"result": {"task_id": "missing", "state": "succeeded", "confirmed_changes": {"mined_blocks": True}}}]
        self.assertEqual(controller.goal_progress(snapshot(), history)['confirmed_mined_blocks'], 1)

    def test_mining_proof_is_compacted_once_without_claiming_collected_items(self):
        proof = {"source": "integrated_server_block_break", "position": {"x": 0, "y": 56, "z": 2}, "drops": "not_confirmed"}
        full = {"result": proof, "confirmed_changes": {"mined_blocks": 1, "mining": proof}}
        compact = controller.compact_result(full)
        self.assertEqual(compact['result'], proof)
        self.assertEqual(compact['confirmed_changes'], {"mined_blocks": 1})
        self.assertIn('mining', full['confirmed_changes'])

    def test_placement_actions_require_their_server_receipt_feature(self):
        caps = {"actions": ["block.place", "build.execute", "look.at"]}
        self.assertEqual(controller.available_actions(caps), ['look.at'])
        caps['action_features'] = {"block.place": ['confirmed_placement_v1']}
        self.assertEqual(controller.available_actions(caps), ['block.place', 'look.at'])
        caps['action_features']['build.execute'] = ['confirmed_placement_v1']
        self.assertEqual(controller.available_actions(caps), ['block.place', 'look.at'])
        caps['action_features']['build.execute'].append('multiblock_blueprint_v1')
        self.assertEqual(controller.available_actions(caps), ['block.place', 'build.execute', 'look.at'])
        for features in (None, [], {'block.place': 'confirmed_placement_v1'}, {'build.execute': True}):
            caps['action_features'] = features
            self.assertEqual(controller.available_actions(caps), ['look.at'])

    def test_placement_parameters_match_in_notebook_and_controller(self):
        good = {'position': {'x': 0, 'y': 56, 'z': 2}, 'block': 'minecraft:oak_log', 'properties': {'axis': 'y'}}
        invalid = ({}, {**good, 'block': 'oak_log'}, {**good, 'properties': []}, {**good, 'properties': {'lit': False}},
                   {**good, 'position': {'x': True, 'y': 56, 'z': 2}}, {**good, 'position': {'x': 0, 'y': 56.5, 'z': 2}},
                   {**good, 'position': {'x': 0, 'y': 2049, 'z': 2}})
        for validate in self.validators:
            original = deepcopy(good)
            validate('block.place', good)
            self.assertEqual(good, original)
            for parameters in invalid:
                with self.subTest(parameters=parameters), self.assertRaises(ValueError):
                    validate('block.place', parameters)

    def test_build_progress_distinguishes_placed_cells_from_matching_blueprint_cells(self):
        partial = {'task_id': 'partial', 'state': 'failed', 'confirmed_changes': {'blocks': 3, 'placed_blocks': 2, 'matched_blocks': 1}}
        existing = {'task_id': 'existing', 'state': 'succeeded', 'confirmed_changes': {'blocks': 20, 'matched_blocks': 20}}
        invalid = {'task_id': 'invalid', 'state': 'cancelled', 'confirmed_changes': {'placed_blocks': True, 'matched_blocks': -1}}
        progress = controller.goal_progress(snapshot(), [{'result': r} for r in (partial, partial, existing, invalid)])
        self.assertEqual(progress['confirmed_placed_blocks'], 2)
        self.assertEqual(progress['confirmed_matched_blocks'], 21)
        self.assertEqual(progress['completed_steps'], 1)
        self.assertNotIn('confirmed_blocks', progress)

    def test_placement_history_keeps_one_proof_and_partial_counters_without_mutating_api_result(self):
        proof = {'source': 'integrated_server_block_place', 'cells': [{'position': {'x': 0, 'y': 56, 'z': 2}, 'before': {'type': 'minecraft:air'}, 'after': {'type': 'minecraft:oak_door'}}]}
        full = {'result': proof, 'confirmed_changes': {'placement': proof, 'placed_blocks': 2, 'matched_blocks': 1}}
        original = deepcopy(full)
        compact = controller.compact_result(full)
        self.assertEqual(compact['result'], proof)
        self.assertEqual(compact['confirmed_changes'], {'placed_blocks': 2, 'matched_blocks': 1})
        self.assertEqual(full, original)

    def test_multi_cell_placement_compaction_preserves_secondary_effect_without_duplicate_primary(self):
        primary = {'position': {'x': 0, 'y': 56, 'z': 2}, 'before': {'type': 'minecraft:air'}, 'after': {'type': 'minecraft:oak_door'}}
        secondary = {**deepcopy(primary), 'position': {'x': 0, 'y': 57, 'z': 2}}
        proof = {**primary, 'cells': [primary, secondary], 'source': 'integrated_server_block_place', 'receipt_id': 'fixture-receipt'}
        full = {'result': proof, 'confirmed_changes': {'placement': proof, 'placed_blocks': 2, 'placed': primary['position']}}
        original = deepcopy(full)
        compact = controller.compact_result(full)
        self.assertNotIn('cells', compact['result'])
        self.assertNotIn('receipt_id', compact['result'])
        self.assertEqual(compact['result']['additional_cells'], [secondary])
        self.assertEqual(compact['result']['changed_cells'], 2)
        for key in ('position', 'before', 'after', 'source'):
            self.assertEqual(compact['result'][key], proof[key])
        self.assertEqual(compact['confirmed_changes'], {'placed_blocks': 2})
        self.assertEqual(full, original)

    def test_named_equipment_and_whole_stack_swaps_keep_observed_slot_numbers(self):
        cases = [('inventory.equip', {'from_slot': 12, 'equipment_slot': 'head', 'menu_id': 0, 'menu_revision': 42}),
                 ('inventory.equip', {'from_slot': 17, 'equipment_slot': 'offhand'}),
                 ('inventory.swap', {'from_slot': 5, 'to_slot': 12}),
                 ('inventory.transfer', {'from_slot': 12, 'to_slot': 13, 'count': 3}),
                 ('inventory.equip', {'from_slot': 36, 'to_slot': 5, 'count': 1})]
        for action, parameters in cases:
            for validate in self.validators:
                with self.subTest(action=action, parameters=parameters):
                    original = deepcopy(parameters)
                    validate(action, parameters)
                    self.assertEqual(parameters, original)

    def test_ambiguous_equipment_and_invalid_slots_rejected_in_both_workers(self):
        cases = [('inventory.equip', {'from_slot': 12, 'equipment_slot': 'head', 'to_slot': 5}),
                 ('inventory.equip', {'from_slot': 12, 'equipment_slot': 'head', 'count': 1}),
                 ('inventory.equip', {'from_slot': 12, 'equipment_slot': 'body'}),
                 ('inventory.equip', {'equipment_slot': 'head'}),
                 ('inventory.swap', {'from_slot': 12, 'to_slot': 13, 'count': 1}),
                 ('inventory.swap', {'from_slot': 12, 'to_slot': 12}),
                 ('inventory.swap', {'from_slot': False, 'to_slot': 13}),
                 ('inventory.swap', {'from_slot': '12', 'to_slot': 13}),
                 ('inventory.swap', {'from_slot': 12, 'to_slot': -1}),
                 ('inventory.swap', {'from_slot': 12, 'to_slot': 13, 'menu_revision': True}),
                 ('inventory.transfer', {'from_slot': 12, 'to_slot': 13, 'count': 1.5})]
        for action, parameters in cases:
            for validate in self.validators:
                with self.subTest(action=action, parameters=parameters), self.assertRaises(ValueError):
                    validate(action, parameters)

    def test_empty_equipment_cells_survive_compaction_without_spending_general_slot_budget(self):
        state = snapshot()
        gear = [{'slot': 5+i, 'type': 'minecraft:air', 'count': 0, 'role': 'armor' if i<4 else 'offhand',
                 'inventory_slot': 39-i if i<4 else 40, 'equipment_slot': name}
                for i, name in enumerate(('head', 'chest', 'legs', 'feet', 'offhand'))]
        ordinary = [{'slot': 9+i, 'type': 'minecraft:air', 'count': 0, 'role': 'main'} for i in range(8)]
        state['context']['menu_slots'] = gear + ordinary
        state['context']['menu_slots'].insert(0, {'slot': 0, 'type': 'minecraft:air', 'count': 0, 'role': 'craft_output'})
        original = deepcopy(state)
        compact = controller.compact_snapshot(state, compact_menu=True)
        self.assertEqual([cell['equipment_slot'] for cell in compact['menu_slots'] if 'equipment_slot' in cell], ['head', 'chest', 'legs', 'feet', 'offhand'])
        self.assertEqual([cell['slot'] for cell in compact['menu_slots'] if cell['role']=='main'], [9, 10, 11, 12])
        self.assertFalse(any(cell['slot']==0 for cell in compact['menu_slots']))
        self.assertEqual(state, original)

    def test_equipment_progress_counts_receipts_once_and_rejects_boolean_counts(self):
        fact = {'task_id': 'equipped', 'state': 'succeeded', 'confirmed_changes': {'inventory_swaps': 1, 'equips': 1}}
        history = [{'result': fact}, {'result': fact}, {'result': {'task_id': 'bad', 'confirmed_changes': {'inventory_swaps': True, 'equips': True}}}]
        progress = controller.goal_progress(snapshot(), history)
        self.assertEqual(progress['confirmed_inventory_swaps'], 1)
        self.assertEqual(progress['confirmed_equips'], 1)


if __name__ == "__main__":
    unittest.main()
