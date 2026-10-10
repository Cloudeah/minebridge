#!/usr/bin/env python3
"""Windows controller for the interactive vLLM TPU Colab notebook."""
import asyncio
import sys

from ollama_colab import parser as common_parser, run
from minebridge_client import BridgeError


def parser():
    result = common_parser()
    result.description = __doc__
    result.set_defaults(compact_observations=True, model_timeout=900)
    return result


if __name__ == "__main__":
    try:
        asyncio.run(run(parser().parse_args()))
    except KeyboardInterrupt:
        print("Контроллер остановлен.")
    except (BridgeError, OSError, ValueError, RuntimeError, TimeoutError) as error:
        print(f"Ошибка: {error}", file=sys.stderr)
        sys.exit(1)
