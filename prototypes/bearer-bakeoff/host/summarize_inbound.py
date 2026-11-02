#!/usr/bin/env python3
"""Per-hub tables for the iPhone inbound-link sweep (m6-inbound-<hub>-k<k>/step.json). Stdlib only; read-only."""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from bakeoff import _sightings  # noqa: E402  (recomputed from the raw scanner JSONL, not from step.json)


def cell(probe, filtered):
    p = probe.get("filtered" if filtered else "unfiltered") or {}
    if not p.get("completed", True):
        return "probe incomplete"
    if p.get("scan_errors"):
        return "scan error: " + "; ".join(map(str, p["scan_errors"]))
    if not p.get("seen"):
        return "no"
    return f"yes ({p.get('first_ms')} ms, {p.get('rssi')} dBm)"


def table(hub_dir):
    steps = sorted(hub_dir.parent.glob(hub_dir.name + "-k*/step.json"), key=lambda p: int(p.parent.name.rsplit("-k", 1)[1]))
    rows = ["| k | New central | Connected (s) | Links healthy | iPhone scanner filtered | iPhone scanner unfiltered | Android scanner filtered | Android scanner unfiltered | Hub link closes so far |",
            "|---|---|---|---|---|---|---|---|---|"]
    meta = json.loads((hub_dir / "run.json").read_text())
    ios, android = meta["devices"].get("ios_scanner", {}).get("name"), meta["devices"].get("android_scanner", {}).get("name")
    for path in steps:
        s = json.loads(path.read_text())
        scanner = lambda name: _sightings(path.parent / f"{name}-scanner.jsonl") if (path.parent / f"{name}-scanner.jsonl").exists() else None
        pi, pa = scanner(ios) or {}, scanner(android)
        closes = "; ".join(f"{c['peer']}: {c['error']}" for c in s["hub_link_closes"]) or "none"
        a_f = cell(pa, True) if pa else "n/a (scanner held)"
        a_u = cell(pa, False) if pa else "n/a (scanner held)"
        ok = f"yes ({s['connect_wait_s']})" if s["connected"] else f"no ({s['connect_wait_s']})"
        for f in s.get("failed_candidates", []):
            ok = f"{f['central']} failed ({f.get('end_error') or (f.get('connect_failures') or ['no link'])[-1]}); " + ok
        rows.append(f"| {s['k']} | {s['new_central']} | {ok} | {s['links_healthy']} | {cell(pi, True)} | {cell(pi, False)} | {a_f} | {a_u} | {closes} |")
    return f"### {hub_dir.name} (iPhone scanner: {ios}, Android scanner: {android})\n\n" + "\n".join(rows) + "\n"


if __name__ == "__main__":
    print("\n".join(table(Path(a)) for a in sys.argv[1:]))
