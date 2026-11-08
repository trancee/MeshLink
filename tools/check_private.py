#!/usr/bin/env python3
"""Fail if tracked files contain private lab or personal identifiers.

Lab scripts and evidence record device aliases (for example `pixel6`, `ip15`),
never device serials, iOS UDIDs, Apple team IDs, LAN addresses, home paths or
personal email addresses (docs/agents/lessons.md, "Device lab").

Usage: python3 tools/check_private.py   (from the repository root; stdlib only)
"""
import re
import subprocess
import sys

CHECKS = [
    ("home path", re.compile(r"/(?:Users|home)/[A-Za-z0-9._-]+/")),
    ("private IPv4", re.compile(r"(?<![\d.§])(?:192\.168|172\.(?:1[6-9]|2\d|3[01]))\.\d{1,3}\.\d{1,3}(?![\d.])")),
    ("private IPv4 with port", re.compile(r"(?<![\d.§])10\.\d{1,3}\.\d{1,3}\.\d{1,3}:\d+")),
    ("iOS UDID", re.compile(r"(?<![0-9A-Fa-f])[0-9A-F]{8}-[0-9A-F]{16}(?![0-9A-Fa-f])")),
    ("Apple team ID", re.compile(r'"team"\s*:\s*"[A-Z0-9]{10}"')),
    ("email address", re.compile(r"[A-Za-z0-9._%+-]+@(?!users\.noreply\.github\.com|example\.(?:com|org))[A-Za-z0-9-]+\.[A-Za-z0-9.-]*[A-Za-z]")),
]
# A device field must hold an alias: lowercase, at least one letter g-z, optional :port.
DEVICE_FIELD = re.compile(r'"(serial|udid|ecid)"\s*:\s*"([^"]*)"')
ALIAS = re.compile(r"(?=[a-z0-9-]*[g-z])[a-z0-9][a-z0-9-]*(?::\d+)?")
# Placeholders that document a pattern rather than leak a value.
ALLOWED = {"<udid>", "<serial>"}


def main():
    files = subprocess.check_output(["git", "ls-files", "-z"]).decode().split("\0")
    problems = []
    for path in filter(None, files):
        try:
            data = open(path, "rb").read()
        except OSError:
            continue
        if b"\0" in data:
            continue
        text = data.decode("utf-8", errors="replace")
        for lineno, line in enumerate(text.splitlines(), 1):
            for name, rx in CHECKS:
                for m in rx.finditer(line):
                    problems.append(f"{path}:{lineno}: {name}: {m.group(0)}")
            for m in DEVICE_FIELD.finditer(line):
                if m.group(2) not in ALLOWED and not ALIAS.fullmatch(m.group(2)):
                    problems.append(f"{path}:{lineno}: device {m.group(1)} is not an alias: {m.group(2)}")
    for p in problems:
        print(p)
    if problems:
        print(f"{len(problems)} private identifier(s) found; use lab aliases (docs/agents/lessons.md).", file=sys.stderr)
        return 1
    print(f"privacy OK: {len([f for f in files if f])} tracked files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
