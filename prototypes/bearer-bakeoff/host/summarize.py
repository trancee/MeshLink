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

STANDARD_ARGS = {"n", "bearers", "plan", "phy", "run", "commit", "duration", "tag"}
HEADLINE = [
    ("setup_ms", "ms scan start -> first app byte (derived; excludes scan pacing wait)"),
    ("kbytes_per_s", "KB/s"),
    ("median_ms", "ms (per-attempt median)"),
    ("count", "count"),
]
SKIP = {"log", "attempt_end", "central_summary", "done", "ios_bt_authorization", "max_concurrent", "crossed_round"}


def device_label(info):
    if not info:
        return "?"
    if info.get("os") is None:
        return info.get("model", "?")
    return f"{info.get('model', '?')} ({info.get('os', '?')})"


def percentile(values, q):
    ordered = sorted(values)
    if not ordered:
        return None
    index = min(len(ordered) - 1, max(0, round(q * (len(ordered) - 1))))
    return ordered[index]


def variant(run_dir):
    """Non-standard launch knobs (e.g. connect_retries=3) so mitigation runs are not merged with baselines."""
    meta = run_dir / "run.json"
    if not meta.exists():
        return "-"
    try:
        args = json.loads(meta.read_text()).get("args", [])
    except json.JSONDecodeError:
        return "?"
    knobs = sorted(a for a in args if "=" in a and a.split("=", 1)[0] not in STANDARD_ARGS)
    return " ".join(knobs) or "-"


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
        start = len(rows)
        for path in sorted(Path(run_dir).glob("*-central.jsonl")) + sorted(Path(run_dir).glob("*-hub.jsonl")) + sorted(Path(run_dir).glob("*-dual.jsonl")) + sorted(Path(run_dir).glob("*-hold.jsonl")):
            for line in path.read_text(errors="replace").splitlines():
                try:
                    record = json.loads(line)
                except json.JSONDecodeError:
                    continue
                record["_run_dir"] = str(run_dir)
                record["_phy_requested"] = requested_phy(Path(run_dir))
                record["_variant"] = variant(Path(run_dir))
                timings = record.get("timings")
                if isinstance(timings, dict) and isinstance(timings.get("first_app_byte"), (int, float)):
                    # GATT: from scan start; CoC: already relative to the PSM read that starts the L2CAP step.
                    timings["setup_ms"] = round(timings["first_app_byte"] - (timings.get("scan_start") or 0), 3)
                rows.append(record)
        # Failed attempts carry no peer identity; attribute them to the run's (single) observed peer.
        peers = defaultdict(set)
        for record in rows[start:]:
            if record.get("central") and record.get("peripheral"):
                peers[device_label(record["central"])].add(device_label(record["peripheral"]))
        for record in rows[start:]:
            if record.get("central") and not record.get("peripheral"):
                known = peers.get(device_label(record["central"]), set())
                if len(known) == 1:
                    record["peripheral"] = {"model": next(iter(known)), "os": None}
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
            record.get("_variant", "-"),
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
        # Android centrals may retry a 133/147 connect (connect_retries=N); retry marks stay in the timings.
        retried = [r for r in items if any(k.startswith("retry") for k in (r.get("timings") or {}))]
        first_try_ok = len(ok) - len([r for r in retried if r.get("outcome") == "ok"])
        table.append({
            "central": key[0], "peripheral": key[1], "phy_requested": key[2], "variant": key[3], "test": key[4], "bearer": key[5],
            "ok": len(ok), "total": len(items), "success_rate": round(len(ok) / len(items), 3) if items else None,
            "first_try_ok": first_try_ok, "retried": len(retried),
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
    cols = ["central", "peripheral", "phy_requested", "variant", "test", "bearer", "ok", "total", "success_rate", "first_try_ok", "metric", "median", "p90", "min", "max", "mtu", "phy", "errors"]
    lines = ["| " + " | ".join(cols) + " |", "|" + "---|" * len(cols)]
    for row in table:
        lines.append("| " + " | ".join("" if row[c] is None else str(row[c]).replace("|", "/") for c in cols) + " |")
    return "\n".join(lines)


def step4(run_dirs):
    """Max-concurrency (hub = central role, phub = peripheral role) and crossed-connection (dual) summaries."""
    lines = []
    for run_dir in map(Path, run_dirs):
        for path in sorted(run_dir.glob("*-hub.jsonl")):
            for r in map(json.loads, filter(None, path.read_text(errors="replace").splitlines())):
                if r.get("test") == "max_concurrent":
                    added = [x for x in map(json.loads, filter(None, path.read_text(errors="replace").splitlines())) if x.get("test") == "hub_link_added"]
                    fails = sorted({(x.get("error") or "")[:60] for x in added if x.get("outcome") != "ok"})
                    lines.append(f"| hub(central) | {run_dir.name} | {device_label(r.get('reporter'))} | max={r.get('max_concurrent')} alive_after_30s={r.get('alive_after_30s')} | peers={r.get('peers')} | add_fail={fails} |")
        holds = sorted(run_dir.glob("*-hold.jsonl"))
        if holds:
            for path in sorted(run_dir.glob("*-peripheral.jsonl")):
                opens = [r for r in map(json.loads, filter(None, path.read_text(errors="replace").splitlines())) if r.get("test") == "peripheral_link_open" and r.get("bearer") == "gatt"]
                reporter = next((r.get("reporter") for r in map(json.loads, filter(None, path.read_text(errors="replace").splitlines()))), None)
                held = []
                for hold in holds:
                    end = [r for r in map(json.loads, filter(None, hold.read_text(errors="replace").splitlines())) if r.get("test") in ("hold_connect", "hold_end", "done")]
                    held.append(f"{hold.name.split('-hold')[0]}:" + "/".join(f"{r['test']}={r['outcome']}" for r in end))
                lines.append(f"| phub(peripheral) | {run_dir.name} | {device_label(reporter)} | max_active_gatt_links={max((r.get('active_links') or 0) for r in opens) if opens else 0} | {' '.join(held)} | |")
        duals = sorted(run_dir.glob("*-dual.jsonl"))
        if duals:
            by_round = defaultdict(list)
            for path in duals:
                for r in map(json.loads, filter(None, path.read_text(errors="replace").splitlines())):
                    if r.get("test") == "crossed_round":
                        by_round[r.get("round")].append(r)
            crossed = [rs for rs in by_round.values() if any(r.get("crossed") for r in rs)]
            outcomes = defaultdict(int)
            for rs in crossed:
                alive = sum(1 for r in rs if r.get("own_central_alive_at_end"))
                actions = "+".join(sorted(r.get("keep_one_action") for r in rs))
                outcomes[f"{actions} -> centrals_alive={alive}"] += 1
            names = " vs ".join(device_label(json.loads(p.read_text().splitlines()[0]).get("reporter")) for p in duals if p.read_text().strip())
            lines.append(f"| dual | {run_dir.name} | {names} | rounds={len(by_round)} crossed={len(crossed)} | {dict(outcomes)} | |")
    if not lines:
        return ""
    return "\n".join(["| kind | run | device(s) | result | detail | failures |", "|---|---|---|---|---|---|", *lines])


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("runs", nargs="+", type=Path)
    parser.add_argument("--markdown", type=Path)
    parser.add_argument("--csv", type=Path)
    args = parser.parse_args()
    table = summarise(load(args.runs))
    markdown = to_markdown(table)
    extra = step4(args.runs)
    if extra:
        markdown += "\n\n" + extra
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
