import json
import math
import struct
import unittest

from minebridge_client import decode_frame


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
