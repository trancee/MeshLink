#!/usr/bin/env python3
"""Summarise bake-off JSONL into per-pair tables (Python 3 stdlib only; raw JSONL is never modified).

Usage:
  python3 host/summarize.py host/results/<run> [host/results/<run> ...] [--markdown out.md] [--csv out.csv]

Rows group central-side records by (central device, peripheral device, requested phy, test, bearer).
Attempt-level success rate comes from gatt_connect/l2cap_connect; per-test rows report ok/total and
median/p90/min/max of the test's headline metric across attempts.
"""
import argparse
import csv
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

HEADLINE = [
    ("first_app_byte", "ms to first app byte"),
    ("kbytes_per_s", "KB/s"),
    ("median_ms", "ms (per-attempt median)"),
    ("count", "count"),
]
SKIP = {"log", "attempt_end", "central_summary", "done", "ios_bt_authorization"}


def device_label(info):
    if not info:
        return "?"
    return f"{info.get('model', '?')} ({info.get('os', '?')})"


def percentile(values, q):
    ordered = sorted(values)
    if not ordered:
        return None
    index = min(len(ordered) - 1, max(0, round(q * (len(ordered) - 1))))
    return ordered[index]


def requested_phy(run_dir):
    meta = run_dir / "run.json"
    if meta.exists():
        try:
            return json.loads(meta.read_text()).get("phy", "?")
        except json.JSONDecodeError:
            return "?"
    return "?"


def load(run_dirs):
    rows = []
    for run_dir in run_dirs:
        for path in sorted(Path(run_dir).glob("*-central.jsonl")) + sorted(Path(run_dir).glob("*-hub.jsonl")) + sorted(Path(run_dir).glob("*-dual.jsonl")):
            for line in path.read_text(errors="replace").splitlines():
                try:
                    record = json.loads(line)
                except json.JSONDecodeError:
                    continue
                record["_run_dir"] = str(run_dir)
                record["_phy_requested"] = requested_phy(Path(run_dir))
                rows.append(record)
    return rows


def summarise(records):
    groups = defaultdict(list)
    for record in records:
        test = record.get("test")
        if test in SKIP or test is None:
            continue
        key = (
            device_label(record.get("central")),
            device_label(record.get("peripheral")),
            record.get("_phy_requested", "?"),
            test,
            record.get("bearer") or "-",
        )
        groups[key].append(record)
    table = []
    for key, items in sorted(groups.items()):
        ok = [r for r in items if r.get("outcome") == "ok"]
        metric_name, values = None, []
        for name, _ in HEADLINE:
            values = [r["timings"][name] for r in ok if isinstance(r.get("timings"), dict) and isinstance(r["timings"].get(name), (int, float))]
            if values:
                metric_name = name
                break
        mtus = sorted({r.get("mtu") for r in ok if r.get("mtu")})
        phys = sorted({r.get("phy") for r in ok if r.get("phy")})
        errors = sorted({(r.get("error") or "")[:80] for r in items if r.get("outcome") != "ok"})
        table.append({
            "central": key[0], "peripheral": key[1], "phy_requested": key[2], "test": key[3], "bearer": key[4],
            "ok": len(ok), "total": len(items), "success_rate": round(len(ok) / len(items), 3) if items else None,
            "metric": metric_name or "-",
            "median": round(statistics.median(values), 1) if values else None,
            "p90": round(percentile(values, 0.9), 1) if values else None,
            "min": round(min(values), 1) if values else None,
            "max": round(max(values), 1) if values else None,
            "mtu": "/".join(map(str, mtus)), "phy": " | ".join(phys)[:60],
            "errors": " ; ".join(e for e in errors if e)[:240],
            "runs": ",".join(sorted({Path(r["_run_dir"]).name for r in items})),
        })
    return table


def to_markdown(table):
    cols = ["central", "peripheral", "phy_requested", "test", "bearer", "ok", "total", "success_rate", "metric", "median", "p90", "min", "max", "mtu", "phy", "errors"]
    lines = ["| " + " | ".join(cols) + " |", "|" + "---|" * len(cols)]
    for row in table:
        lines.append("| " + " | ".join("" if row[c] is None else str(row[c]).replace("|", "/") for c in cols) + " |")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("runs", nargs="+", type=Path)
    parser.add_argument("--markdown", type=Path)
    parser.add_argument("--csv", type=Path)
    args = parser.parse_args()
    table = summarise(load(args.runs))
    markdown = to_markdown(table)
    if args.markdown:
        args.markdown.write_text(markdown + "\n")
    if args.csv:
        with open(args.csv, "w", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=list(table[0].keys()) if table else ["empty"])
            writer.writeheader()
            writer.writerows(table)
    if not args.markdown and not args.csv:
        print(markdown)


if __name__ == "__main__":
    sys.exit(main())
