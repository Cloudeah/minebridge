#!/usr/bin/env python3
"""Examples: snapshot, stop, move 10 64 20, vision scene, craft-plan minecraft:stick 4."""
import argparse
import asyncio
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "python"))
from minebridge_client import BridgeClient


async def main(args):
    async with BridgeClient.from_owner_file(args.config, args.url) as bridge:
        if args.command == "snapshot":
            result = await bridge.request("observe.snapshot")
        elif args.command == "stop":
            result = await bridge.request("control.stop")
        elif args.command == "move":
            x, y, z = map(float, args.values)
            accepted = await bridge.submit("move.to", {"target": {"x": x, "y": y, "z": z}})
            result = await bridge.wait_task(accepted["task_id"])
        elif args.command == "craft-plan":
            item, count = args.values
            result = await bridge.request("craft.plan", {"item": item, "count": int(count)})
        elif args.command == "vision":
            metadata, png, depth = await bridge.capture()
            target = Path(args.values[0])
            target.with_suffix(".png").write_bytes(png)
            # Preserve the on-wire little-endian format on every Python host.
            if sys.byteorder != "little":
                depth.byteswap()
            target.with_suffix(".depth.f32").write_bytes(depth.tobytes())
            target.with_suffix(".json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2))
            result = {"saved": str(target), "frame_id": metadata["frame_id"]}
        else:
            raise ValueError("Unknown command")
        print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", default="mod/run/config/minebridge/bridge-owner.json")
    parser.add_argument("--url", default=None)
    parser.add_argument("command", choices=["snapshot", "stop", "move", "vision", "craft-plan"])
    parser.add_argument("values", nargs="*")
    asyncio.run(main(parser.parse_args()))
