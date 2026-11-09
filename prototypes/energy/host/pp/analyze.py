#!/usr/bin/env python3
"""Ticket 35 PROTOTYPE: per-phase SystemPowerLevel medians from a Power Profiler trace + driver phase log."""
import datetime, json, os, statistics as st, subprocess, sys, xml.etree.ElementTree as ET
TRACE, PHASES = sys.argv[1], sys.argv[2]
WH = 6.7  # 12 mini full-charge energy (1,741 mAh x ~3.85 V), ticket 35
env = dict(os.environ, DEVELOPER_DIR=os.environ.get("DEVELOPER_DIR", "/Applications/Xcode-27.2.0.app/Contents/Developer"))

def export(xpath):
    return ET.fromstring(subprocess.run(["xcrun", "xctrace", "export", "--input", TRACE, "--xpath", xpath],
                                        capture_output=True, text=True, env=env, check=True).stdout)

def rows(schema):
    root, ids = export(f'/trace-toc/run[@number="1"]/data/table[@schema="{schema}"]'), {}
    for row in root.iter("row"):
        vals = []
        for el in row:
            if el.get("ref"): el = ids[el.get("ref")]
            elif el.get("id"): ids[el.get("id")] = el
            vals.append((el.tag, el.text, el.get("fmt")))
        yield vals

toc = ET.fromstring(subprocess.run(["xcrun", "xctrace", "export", "--input", TRACE, "--toc"], capture_output=True, text=True, env=env, check=True).stdout)
t0 = datetime.datetime.fromisoformat(toc.find(".//run/info/summary/start-date").text).timestamp()
samples = [(t0 + int(v[0][1]) / 1e9, float(v[2][1])) for v in rows("SystemPowerLevel")]
charging = [[x[2] for x in v] for v in rows("DeviceChargingState")]
print(f"samples {len(samples)}, span {samples[0][0]-t0:.0f}-{samples[-1][0]-t0:.0f} s; charging rows {len(charging)}: {charging[:3]}")

log = [json.loads(l) for l in open(PHASES)]
phases = [r for r in log if "cond" in r]
ends = [p["launch_t"] for p in phases[1:]] + [next(r["end_t"] for r in log if "end_t" in r)]
res = []
for p, end in zip(phases, ends):
    xs = [v for t, v in samples if p["launched_t"] + 15 <= t < end - 5]
    res.append({**p, "n": len(xs), "median": st.median(xs) if xs else None, "mean": st.mean(xs) if xs else None})
    print(f'{p["block"]:5} {p["cond"]} n={len(xs):3} median={res[-1]["median"]:6.2f} mean={res[-1]["mean"]:6.2f} %/hr')
conds = sorted({r["cond"] for r in res})
blocks = sorted({r["block"] for r in res if r["block"].startswith("b")})
med = {(r["block"], r["cond"]): r["median"] for r in res}
floor = min(med[(b, "A")] for b in blocks)
# Session 3 rule (fixed before the run): a block is invalid if any phase median exceeds 2x the lowest A median.
invalid = {b for b in blocks if any(med[(b, c)] > 2 * floor for c in conds)}
print(f"lowest A median {floor:.2f} %/hr; blocks failing the 2x rule: {sorted(invalid) or 'none'}")
pairs = [(c, "A") for c in conds if c != "A"] + ([("C", "D")] if {"C", "D"} <= set(conds) else [])
for label, keep in (("all blocks", blocks), ("valid blocks", [b for b in blocks if b not in invalid])):
    if not keep or keep == blocks and label != "all blocks":
        continue
    for x, y in pairs:
        d = [med[(b, x)] - med[(b, y)] for b in keep]
        m = st.median(d)
        print(f"[{label}] {x}-{y}: {[round(v, 2) for v in d]} positive {sum(v > 0 for v in d)}/{len(d)}; "
              f"median {m:+.2f} %/hr = {m * WH * 10:+.0f} mW (range {min(d) * WH * 10:+.0f}..{max(d) * WH * 10:+.0f} mW)")
