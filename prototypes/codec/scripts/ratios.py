#!/usr/bin/env python3
"""Throughput of C relative to B (= B_ns / C_ns, so 0.5 means C runs at half B's speed) per bench CSV."""
import sys
from pathlib import Path


def load(path):
    rows = {}
    for line in Path(path).read_text().splitlines():
        parts = line.split(",")
        if len(parts) == 5 and parts[2] in ("decode", "encode"):
            rows[(parts[0], parts[2], parts[1][0])] = float(parts[3])
    return rows


def main(paths):
    print("file,sample,op,A_ns,B_ns,C_ns,C_throughput_vs_B,A_throughput_vs_B")
    for p in paths:
        rows = load(p)
        worst = {}
        for (sample, op, codec), ns in sorted(rows.items()):
            if codec != "B":
                continue
            a, b, c = rows[(sample, op, "A")], ns, rows[(sample, op, "C")]
            print(f"{Path(p).name},{sample},{op},{a},{b},{c},{b / c:.2f},{b / a:.2f}")
            worst[op] = min(worst.get(op, 9.0), b / c)
        print(f"{Path(p).name},WORST,,,,,decode={worst['decode']:.2f};encode={worst['encode']:.2f},")


if __name__ == "__main__":
    main(sys.argv[1:])
