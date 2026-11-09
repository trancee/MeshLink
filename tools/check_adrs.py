#!/usr/bin/env python3
"""ADR header check: the Version and amendment dates in each ADR's Status line match its Amendments section.

Convention (docs/adr/*.md):
  - The Status line carries `Version: N`, where N = 1 + the number of amendment blocks.
  - An amendment block is a line under `## Amendments` that starts with `YYYY-MM-DD, `.
  - With amendments, the Status line says `(amended <date>, ...; <date>, ...)` and names every
    block date; it names no date that has no block.

Usage: python3 tools/check_adrs.py [ADR_DIR]   (default docs/adr; exit 1 on any mismatch)
"""
import pathlib, re, sys

DATE = re.compile(r"\d{4}-\d{2}-\d{2}")
BLOCK = re.compile(r"^(\d{4}-\d{2}-\d{2}), ")


def check(path):
    lines = path.read_text(encoding="utf-8").splitlines()
    status = next((l for l in lines if l.startswith("Status:")), None)
    if status is None:
        return [f"{path}: no Status line"]
    version = re.search(r"Version: (\d+)", status)
    if version is None:
        return [f"{path}: Status line has no 'Version: N'"]
    blocks, in_amendments = [], False
    for line in lines:
        if line.startswith("## "):
            in_amendments = line.strip() == "## Amendments"
        elif in_amendments and BLOCK.match(line):
            blocks.append(BLOCK.match(line).group(1))
    problems = []
    if int(version.group(1)) != 1 + len(blocks):
        problems.append(f"{path}: Version {version.group(1)}, but {len(blocks)} amendment block(s) need Version {1 + len(blocks)}")
    amended = re.search(r"\(amended (.*)\)", status[version.end():])
    named = set(DATE.findall(amended.group(1))) if amended else set()
    for date in sorted(set(blocks) - named):
        problems.append(f"{path}: amendment block {date} is missing from the Status line's '(amended ...)'")
    for date in sorted(named - set(blocks)):
        problems.append(f"{path}: Status line names {date}, but no amendment block has that date")
    return problems


def main():
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "docs/adr")
    adrs = sorted(root.glob("[0-9][0-9][0-9][0-9]-*.md"))
    problems = [p for adr in adrs for p in check(adr)]
    print("\n".join(problems) if problems else f"adrs OK: {len(adrs)} ADRs")
    return 1 if problems or not adrs else 0


if __name__ == "__main__":
    sys.exit(main())
