#!/usr/bin/env python3
"""Counts non-blank, non-comment lines per frame codec function (encX/decX/decodeAdvert) in CodecA/B/C."""
import re
import sys
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent / "src/commonMain/kotlin/meshlink/proto/codec"
FUN = re.compile(r"^\s*(?:private |override )*fun (?:KompactCursor\.)?(\w+)\(")


def functions(path):
    lines = path.read_text().splitlines()
    out, name, depth, count, total = {}, None, 0, 0, 0
    for line in lines:
        s = line.strip()
        code = s and not s.startswith("//") and not s.startswith("*") and not s.startswith("/*")
        if code:
            total += 1
        m = FUN.match(line)
        if name is None and m:
            name, depth, count = m.group(1), 0, 0
        if name is not None:
            if code:
                count += 1
            depth += line.count("{") - line.count("}")
            if depth <= 0 and ("{" in line or "}" in line or "=" in line):
                out[name] = count
                name = None
    return out, total


def frame_of(fn):
    if fn == "decodeAdvert":
        return "Advert"
    m = re.match(r"(enc|dec)(\w+)", fn)
    return m.group(2) if m and m.group(2) else None


def main():
    per = {}
    totals = {}
    for codec in ("A", "B", "C"):
        fns, total = functions(SRC / f"Codec{codec}.kt")
        totals[codec] = total
        for fn, n in fns.items():
            frame = frame_of(fn)
            if frame and fn not in ("encode", "decode"):
                per.setdefault(frame, {}).setdefault(codec, 0)
                per[frame][codec] += n
    print("frame,A_loc,B_loc,C_loc")
    for frame in sorted(per):
        row = per[frame]
        print(f"{frame},{row.get('A', 0)},{row.get('B', 0)},{row.get('C', 0)}")
    print(f"file_total,{totals['A']},{totals['B']},{totals['C']}")


if __name__ == "__main__":
    sys.exit(main())
