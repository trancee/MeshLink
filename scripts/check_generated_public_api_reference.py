#!/usr/bin/env python3

from __future__ import annotations

import difflib
import importlib.util
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = ROOT / "scripts" / "generate_public_api_reference.py"
OUTPUT_PATH = ROOT / "docs" / "reference" / "generated-public-api.md"


def load_generator_module():
    os.chdir(ROOT)
    spec = importlib.util.spec_from_file_location("meshlink_public_api_generator", GENERATOR_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Could not load generator module from {GENERATOR_PATH}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def main() -> int:
    module = load_generator_module()
    rendered = module.render(module.parse_types())
    current = OUTPUT_PATH.read_text(encoding="utf-8")

    if rendered == current:
        print("generated public API reference is in sync")
        return 0

    print("generated public API reference is out of sync")
    diff = difflib.unified_diff(
        current.splitlines(),
        rendered.splitlines(),
        fromfile=str(OUTPUT_PATH),
        tofile="expected",
        lineterm="",
    )
    for index, line in enumerate(diff):
        if index >= 200:
            print("... diff truncated ...")
            break
        print(line)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
