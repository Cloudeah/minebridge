import json
import math
import struct
import unittest
import tempfile
from pathlib import Path

from minebridge_client import BridgeClient, decode_frame


class ActorConfigurationTest(unittest.TestCase):
    def test_companion_uses_distinct_port_and_keeps_relay_port_free(self):
        with tempfile.TemporaryDirectory() as folder:
            file = Path(folder) / "owner.json"
            file.write_text(json.dumps({"token": "fixture", "port": 8765}))
            human = BridgeClient.from_owner_file(file)
            companion = BridgeClient.from_owner_file(file, actor_mode="companion")
            self.assertEqual(human.url, "ws://127.0.0.1:8765/bridge")
            self.assertEqual(companion.url, "ws://127.0.0.1:8767/bridge")
            self.assertEqual(companion.actor_mode, "companion")
            explicit = BridgeClient.from_owner_file(file, "ws://127.0.0.1:9999/bridge", actor_mode="companion")
            self.assertEqual(explicit.url, "ws://127.0.0.1:9999/bridge")
            file.write_text(json.dumps({"token": "fixture", "port": 65534}))
            with self.assertRaises(ValueError):
                BridgeClient.from_owner_file(file, actor_mode="companion")

    def test_unknown_actor_mode_is_rejected_before_network_access(self):
        with self.assertRaises(ValueError):
            BridgeClient("fixture", actor_mode="unknown")


class FrameTest(unittest.TestCase):
    def packet(self):
        metadata = {"rgb": {"bytes": 3}, "depth": {"bytes": 8, "width": 2, "height": 1}}
        raw = json.dumps(metadata).encode()
        return b"MBV1" + struct.pack(">I", len(raw)) + raw + b"PNG" + struct.pack("<ff", 2.5, float("nan"))

    def test_exact_binary_layout(self):
        metadata, png, depth = decode_frame(self.packet())
        self.assertEqual(png, b"PNG")
        self.assertEqual(depth[0], 2.5)
        self.assertTrue(math.isnan(depth[1]))

    def test_truncated_payload_rejected(self):
        with self.assertRaises(ValueError):
            decode_frame(self.packet()[:-1])

    def test_unknown_magic_rejected(self):
        with self.assertRaises(ValueError):
            decode_frame(b"BAD!" + self.packet()[4:])


if __name__ == "__main__":
    unittest.main()
