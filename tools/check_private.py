#!/usr/bin/env python3
"""Fail if repository content contains private lab or personal identifiers.

Lab scripts and evidence record device aliases (for example `pixel6`, `ip15`),
never device or battery serials, iOS UDIDs, Apple team IDs, LAN addresses, home
paths or personal email addresses (docs/agents/lessons.md, "Device lab").
Bluetooth addresses in evidence are lowercase keyed pseudonyms; uppercase ones are real captures.
Gzipped files are decompressed and scanned; other binary files are skipped.

Usage (from the repository root; stdlib only):
  python3 tools/check_private.py                  # tracked files in the worktree
  python3 tools/check_private.py --history        # every blob reachable from any ref
  python3 tools/check_private.py --history --rev 'b:*' # local branches only
  python3 tools/check_private.py --history --rev SHA   # one commit's history (.githooks/pre-push)
  python3 tools/check_private.py --inventory PATH # also match exact serials from a local inventory
"""
import argparse
import gzip
import re
import subprocess
import sys
import zlib

CHECKS = [
    ("home path", re.compile(r"/(?:Users|home)/[A-Za-z0-9._-]+/")),
    ("private IPv4", re.compile(r"(?<![\d.§])(?:192\.168|172\.(?:1[6-9]|2\d|3[01]))\.\d{1,3}\.\d{1,3}(?![\d.])")),
    ("private IPv4 with port", re.compile(r"(?<![\d.§])10\.\d{1,3}\.\d{1,3}\.\d{1,3}:\d+")),
    ("iOS UDID", re.compile(r"(?<![0-9A-Fa-f])[0-9A-F]{8}-[0-9A-F]{16}(?![0-9A-Fa-f])")),
    ("Apple team ID", re.compile(r'"team"\s*:\s*"[A-Z0-9]{10}"')),
    # Kotlin labels (`this@Outer.x`) and HAL names (`name@1.0-service`) are not addresses.
    ("email address", re.compile(r"(?<![A-Za-z0-9._%+-])(?!this@)[A-Za-z0-9._%+-]+@(?!users\.noreply\.github\.com|example\.(?:com|org))[A-Za-z][A-Za-z0-9-]*\.[A-Za-z0-9.-]*[A-Za-z]")),
    # Android and battery serials: 10-20 uppercase letters and digits, at least one digit and two letters.
    ("serial-like token", re.compile(r"(?<![A-Za-z0-9_./-])(?=[A-Z0-9]*\d)(?=(?:[0-9]*[A-Z]){2})[A-Z0-9]{10,20}(?![A-Za-z0-9_-])")),
    # Captured Bluetooth addresses are uppercase; committed evidence uses lowercase keyed pseudonyms.
    ("Bluetooth address", re.compile(r"(?<![0-9A-Fa-f:])(?=[0-9:]*[A-F])(?:[0-9A-F]{2}:){5}[0-9A-F]{2}(?![0-9A-Fa-f:])")),
]
# A device field must hold an alias: lowercase, at least one letter g-z, optional :port.
DEVICE_FIELD = re.compile(r'"(serial|udid|ecid)"\s*:\s*"([^"]*)"|<key>(Serial|SerialNumber|UDID|ECID)</key>\s*<string>([^<]*)</string>')
ALIAS = re.compile(r"(?=[a-z0-9-]*[g-z])[a-z0-9][a-z0-9-]*(?::\d+)?")
# Placeholders that document a pattern rather than leak a value.
ALLOWED = {"<udid>", "<serial>"}
# Public model numbers, firmware builds and API names that look like serials.
ALLOWED_TOKENS = {"23053RN02A", "G390FXXU6CTG3", "CBL2CAPPSM", "MJWSD06MMC"}


def load_inventory(path):
    """Serials from a local device inventory: first cell of Markdown table rows, or one per line."""
    serials = set()
    for line in open(path, encoding="utf-8"):
        cell = line.split("|")[1] if line.lstrip().startswith("|") else line
        cell = cell.strip().strip("`")
        if re.fullmatch(r"[A-Za-z0-9]{6,}", cell) and re.search(r"\d", cell):
            serials.add(cell)
    if not serials:
        return None
    return re.compile(r"(?<![A-Za-z0-9])(?:" + "|".join(map(re.escape, sorted(serials))) + r")(?![A-Za-z0-9])")


def scan(name, path, data, inventory, problems):
    if path.endswith(".gz"):
        try:
            data = gzip.decompress(data)
        except (OSError, EOFError, zlib.error):
            return
    if b"\0" in data:
        return
    text = data.decode("utf-8", errors="replace")
    checks = CHECKS + ([("inventory serial", inventory)] if inventory else [])

    def report(pos, what):
        problems.append(f"{name}:{text.count(chr(10), 0, pos) + 1}: {what}")

    for label, rx in checks:
        for m in rx.finditer(text):
            if label == "serial-like token" and m.group(0) in ALLOWED_TOKENS:
                continue
            report(m.start(), f"{label}: {m.group(0)}")
    for m in DEVICE_FIELD.finditer(text):
        field, value = (m.group(1), m.group(2)) if m.group(1) else (m.group(3), m.group(4))
        if value not in ALLOWED and not ALIAS.fullmatch(value):
            report(m.start(), f"device {field} is not an alias: {value}")


def tracked():
    for path in filter(None, subprocess.check_output(["git", "ls-files", "-z"]).decode().split("\0")):
        try:
            yield path, path, open(path, "rb").read()
        except OSError:
            continue


def history(revs):
    seen = set()
    args = [a for r in revs for a in (["--branches=" + r[2:]] if r.startswith("b:") else [r])] if revs else ["--all"]
    for line in subprocess.check_output(["git", "rev-list", "--objects", *args]).decode().splitlines():
        sha, _, path = line.partition(" ")
        if path and sha not in seen:
            seen.add(sha)
            yield sha, path


def blobs(entries):
    cat = subprocess.Popen(["git", "cat-file", "--batch"], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    for sha, path in entries:
        cat.stdin.write(sha.encode() + b"\n")
        cat.stdin.flush()
        kind, size = cat.stdout.readline().split()[1:3]
        data = cat.stdout.read(int(size))
        cat.stdout.read(1)
        if kind == b"blob":
            yield f"{path}@{sha[:7]}", path, data
    cat.stdin.close()
    cat.wait()


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--history", action="store_true", help="scan every blob reachable from any ref")
    ap.add_argument("--rev", action="append", metavar="REV", help="with --history: scan these revisions instead of all refs; b:GLOB selects local branches")
    ap.add_argument("--inventory", metavar="PATH", help="local device inventory with exact serials (never committed)")
    args = ap.parse_args()
    inventory = load_inventory(args.inventory) if args.inventory else None
    problems, count = [], 0
    for name, path, data in blobs(history(args.rev)) if args.history else tracked():
        count += 1
        scan(name, path, data, inventory, problems)
    for p in problems:
        print(p)
    if problems:
        print(f"{len(problems)} private identifier(s) found; use lab aliases (docs/agents/lessons.md).", file=sys.stderr)
        return 1
    print(f"privacy OK: {count} {'blobs' if args.history else 'tracked files'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
