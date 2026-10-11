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
from test_colab import ROOT, DECISION, EXPRESSION_REPLY, FakeVllm, controller, notebook_function, snapshot


class InteractiveControllerTest(unittest.IsolatedAsyncioTestCase):
    async def test_bow_decisions_cross_notebook_relay_and_guarded_tasks_without_rewriting(self):
        await self.integration(ranged=True)

    async def test_follow_decisions_cross_notebook_relay_and_guarded_tasks_without_rewriting(self):
        await self.integration(follow=True)

    async def test_crossbow_decision_omits_bow_charge_ticks_through_guarded_submission(self):
        await self.integration(ranged=True, crossbow=True)

    async def test_offhand_interaction_reaches_notebook_and_guarded_task_unchanged(self):
        await self.integration(interaction=True)

    async def test_craft_station_parameters_reach_notebook_relay_and_guarded_task(self):
        await self.integration(craft=True)

    async def test_block_placement_reaches_notebook_relay_and_guarded_task_unchanged(self):
        await self.integration(placement=True)

    async def test_blueprint_reaches_notebook_relay_and_guarded_task_unchanged(self):
        await self.integration(placement=True, build=True)

    async def integration(self, vision=False, cancel_first=False, malformed_first=False, query_first=False, ranged=False, follow=False, crossbow=False, interaction=False, craft=False, placement=False, build=False):
        submitted, observed_profiles, reports, queries = [], [], [], []
        terminal = asyncio.Event()
        holder = {}
        model = FakeVllm()
        bow_decision = {"action": "combat.ranged", "parameters": {"entity_id": "418f10e9-bc78-4d9c-bb26-6a9cfa352b69", "charge_ticks": 20}, "reason": "shoot once"}
        if crossbow:bow_decision["parameters"].pop("charge_ticks")
        if ranged:
            model.content = json.dumps(bow_decision)
        follow_decision = {"action": "move.follow", "parameters": {"entity_id": bow_decision["parameters"]["entity_id"], "ticks": 100, "distance": 3}, "reason": "follow five seconds"}
        if follow:
            model.content = json.dumps(follow_decision)
        interaction_decision = {"action": "world.interact", "parameters": {"entity_id": bow_decision["parameters"]["entity_id"], "hand": "off"}, "reason": "milk the cow"}
        if interaction:
            model.content = json.dumps(interaction_decision)
        craft_decision = {"action": "craft.execute", "parameters": {"item": "minecraft:wooden_pickaxe", "count": 1, "auto_station": False, "station_position": {"x": 0, "y": 56, "z": 2}}, "reason": "craft at observed table"}
        if craft:
            model.content = json.dumps(craft_decision)
        placement_decision = {"action": "build.execute" if build else "block.place", "parameters":
                              {"origin": {"x": 0, "y": 56, "z": 2}, "rotation": 90, "replace": False, "blocks": [{"x": 0, "y": 0, "z": 0, "type": "minecraft:oak_log", "properties": {"axis": "y"}}]} if build else
                              {"position": {"x": 0, "y": 56, "z": 2}, "block": "minecraft:oak_log", "properties": {"axis": "y"}}, "reason": "place observed material"}
        if placement:
            model.content = json.dumps(placement_decision)
        if malformed_first:
            def reply():
                model.content = EXPRESSION_REPLY if len(model.payloads) == 1 else json.dumps(DECISION)

            model.before_reply = reply
        if query_first:
            def reply_with_query():
                model.content = json.dumps({"action": "query", "parameters": {"operation": "craft.plan", "arguments": {"item": "minecraft:stick", "count": 4}}, "reason": "check recipe"}) if len(model.payloads) % 2 else json.dumps(DECISION)
            model.before_reply = reply_with_query
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
            return {"goal_id": identifier, "goal": "milk the cow" if interaction else "follow five seconds" if follow else "shoot once" if ranged else "look east", "steps": 2 if query_first else 1, "vision": vision,
                    "state": "queued", "step": 0}

        status = goal("first")
        capabilities = {"actions": ["craft.execute"] if craft else ["world.interact"] if interaction else ["move.follow"] if follow else ["combat.ranged"] if ranged else ["look.at"], "actor_id": "test-actor", "armed": True,
                        "model_control": True, "observation_profiles": ["full", "compact"]}
        if placement:
            capabilities['actions'] = [placement_decision['action']]
            capabilities['action_features'] = {placement_decision['action']: ['confirmed_placement_v1']}

        if query_first:
            capabilities["queries"] = ["craft.plan"]

        def observation():
            state = {**snapshot(), "control_epoch": 7}
            if ranged or follow:
                state["actor"]["main_hand"] = {"type": "minecraft:crossbow" if crossbow else "minecraft:bow", "count": 1, "damage": 0}
                if crossbow:state["actor"]["main_hand"]["crossbow"] = {"charged": False, "projectiles": []}
                state["entities"] = [{"entity_id": bow_decision["parameters"]["entity_id"], "type": "minecraft:pig", "position": {"x": 0, "y": 64, "z": 8}, "visible": True}]
            if interaction:
                state["actor"]["off_hand"] = {"type": "minecraft:bucket", "count": 1, "damage": 0}
                state["entities"] = [{"entity_id": interaction_decision["parameters"]["entity_id"], "type": "minecraft:cow", "kind": "living", "position": {"x": 0, "y": 64, "z": 2}, "visible": True}]
            return state

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
                elif operation == "model.query":
                    self.assertEqual(data["model_goal_id"], status["goal_id"])
                    self.assertEqual(data["expected_control_epoch"], 7)
                    self.assertEqual(data["operation"], "craft.plan")
                    queries.append(dict(data))
                    value = {"executable": False, "missing": {"minecraft:oak_log": 1}, "stages": []}
                elif operation == "task.submit":
                    self.assertEqual(status["state"], "executing")
                    self.assertEqual(data["model_goal_id"], status["goal_id"])
                    self.assertEqual(data["expected_control_epoch"], 7)
                    self.assertEqual(data["timeout_ticks"], 600)
                    self.assertEqual(data["max_actions"], 17)
                    submitted.append(dict(data))
                    value = {"status": "accepted", "task_id": "task-" + status["goal_id"]}
                elif operation == "task.status":
                    value = {"state": "succeeded", "task_id": data["task_id"], "result": {}, "confirmed_changes": {"interactions": 1} if interaction else {"follow_ticks": 100} if follow else {"shots": 1, "hits": 1, "damage": 6} if ranged else {}}
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
                    "--relay-config", str(Path(directory) / "relay.json"), "--model-timeout", "5",
                    "--task-ticks", "600", "--max-actions", "17"])
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
                        worker = asyncio.create_task(asyncio.to_thread(decide, 4 if query_first else 2, stop_on_wait=False))
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
            self.assertEqual(len(model.payloads), 4 if query_first else 3 if malformed_first else 2)
            for payload in model.payloads:
                user_content = payload["messages"][1]["content"]
                user_content = user_content[-1]["text"] if isinstance(user_content, list) else user_content
                self.assertEqual(json.loads(user_content)["task_limits"], {"timeout_ticks": 600, "max_actions": 17})
            self.assertEqual([item["model_goal_id"] for item in submitted], ["second"] if cancel_first else ["first", "second"])
            if query_first:
                self.assertEqual(len(queries), 2)
                for payload in model.payloads[1::2]:
                    prior = json.loads(payload["messages"][1]["content"])["recent_results"][0]
                    self.assertEqual(prior["state"], "queried")
                    self.assertEqual(prior["result"]["missing"], {"minecraft:oak_log": 1})
            if ranged:
                for item in submitted:
                    self.assertEqual(item["action"], bow_decision["action"])
                    self.assertEqual(item["parameters"], bow_decision["parameters"])
            if follow:
                for item in submitted:
                    self.assertEqual(item["action"], follow_decision["action"])
                    self.assertEqual(item["parameters"], follow_decision["parameters"])
            if interaction:
                for item in submitted:
                    self.assertEqual(item["action"], interaction_decision["action"])
                    self.assertEqual(item["parameters"], interaction_decision["parameters"])
            if craft:
                for item in submitted:
                    self.assertEqual(item["action"], craft_decision["action"])
                    self.assertEqual(item["parameters"], craft_decision["parameters"])
            if placement:
                for item in submitted:
                    self.assertEqual(item['action'], placement_decision['action'])
                    self.assertEqual(item['parameters'], placement_decision['parameters'])
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

    async def test_planning_query_reaches_notebook_then_one_guarded_game_task_per_goal(self):
        await self.integration(query_first=True)

    async def test_two_owner_goals_share_controller_and_notebook_session(self):
        await self.integration()

    async def test_invalid_json_is_regenerated_before_one_task_per_owner_goal(self):
        await self.integration(malformed_first=True)

    async def test_game_window_vision_option_requests_compact_snapshot_and_preserves_png(self):
        await self.integration(vision=True)

    async def test_manual_stop_rejects_slow_reply_then_processes_new_owner_goal(self):
        await self.integration(cancel_first=True)
