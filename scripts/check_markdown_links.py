#!/usr/bin/env python3

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MARKDOWN_LINK_PATTERN = re.compile(r"\[[^\]]+\]\(([^)#]+)")
IGNORED_PARTS = {".git", "build", ".gradle"}


def markdown_files() -> list[Path]:
    files: list[Path] = []
    for path in ROOT.rglob("*.md"):
        if any(part in IGNORED_PARTS for part in path.parts):
            continue
        files.append(path)
    return files


def main() -> int:
    failures: list[tuple[Path, str]] = []

    for markdown_path in markdown_files():
        content = markdown_path.read_text(encoding="utf-8")
        for target in MARKDOWN_LINK_PATTERN.findall(content):
            if not target or "://" in target or target.startswith("mailto:"):
                continue
            resolved = (markdown_path.parent / target).resolve()
            if not resolved.exists():
                failures.append((markdown_path.relative_to(ROOT), target))

    if not failures:
        print("markdown links are valid")
        return 0

    print("broken markdown links detected:")
    for markdown_path, target in failures:
        print(f"- {markdown_path}: {target}")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
