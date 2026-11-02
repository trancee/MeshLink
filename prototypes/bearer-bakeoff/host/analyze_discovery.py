#!/usr/bin/env python3
"""Analysis for the ticket-13 discovery PROTOTYPE runs (stdlib only).

  python3 host/analyze_discovery.py d1 RUN_DIR [RUN_DIR...]   # rotation + restart control -> summary-d1.md, CSVs

Attribution: Android advertisers log every 8 B advertisement ID (layout A) in `adv_start`; scanners see it as
`a:<hex>`. iPhones in multi-advertiser runs use local name `ML-<tag>` (`name:` ids).
"""
import csv
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path


def load(path):
    out = []
    for line in Path(path).read_text(errors="replace").splitlines():
        if line.startswith("{"):
            try:
                out.append(json.loads(line))
            except json.JSONDecodeError:
                pass
    return out


class Run:
    def __init__(self, directory):
        self.dir = Path(directory)
        self.name = self.dir.name
        self.files = {p.stem: load(p) for p in self.dir.glob("*.jsonl") if p.stem != "host"}
        self.host = load(self.dir / "host.jsonl") if (self.dir / "host.jsonl").exists() else []
        self.start = {d: next((r for r in recs if r.get("test") == "disc_start"), None) for d, recs in self.files.items()}
        self.scanners = [d for d, s in self.start.items() if s and s.get("mode") == "scan"]
        self.advertisers = [d for d, s in self.start.items() if s and s.get("mode") == "adv"]
        self.tags = {s["tag"]: d for d, s in self.start.items() if s}
        self.ad_ids = {}
        for device in self.advertisers:
            for r in self.files[device]:
                if r.get("test") == "adv_start" and r.get("ad_id"):
                    self.ad_ids[r["ad_id"]] = (device, r.get("cycle"), r.get("step"), r.get("rep"))
        self.offsets = {r["device"]: r["offset_ms"] for r in self.host if r.get("event") == "clock" and "offset_ms" in r}

    def model(self, device):
        s = self.start.get(device) or {}
        rep = s.get("reporter") or {}
        return rep.get("model") or s.get("args", {}).get("model") or device, rep.get("os", "")

    def attribute(self, ident):
        if not ident:
            return None
        if ident.startswith("a:"):
            hit = self.ad_ids.get(ident[2:])
            return hit[0] if hit else None
        if ident.startswith("name:ML-"):
            name = ident[len("name:ML-"):]
            for tag, device in self.tags.items():
                if name == tag or (name.startswith(tag) and len(name) == len(tag) + 1):
                    return device
        return None

    def sightings(self, scanners=None):
        """(scanner, t_ms_scanner_clock, address, ident, record) for adv and bucket records."""
        for scanner in scanners or self.scanners:
            for r in self.files[scanner]:
                if r.get("test") in ("adv", "s"):
                    yield scanner, r["t"], r["a"], r.get("id"), r

    def address_owner(self, scanners=None):
        owner = {}
        for _, _, address, ident, r in self.sightings(scanners):
            device = self.attribute(ident)
            if device:
                owner.setdefault(address, device)
        return owner


def fmt_min(ms):
    return f"{ms / 60000:.1f}"


def d1(runs, out_dir):
    interval_rows, control_rows, change_rows = [], [], []
    notes = []
    for run in runs:
        owner = run.address_owner()
        # Per device: per address first/last sighting (union of scanners, scanner clocks; offsets < 0.5 s for the two scanners).
        spans = defaultdict(lambda: defaultdict(lambda: [None, None, 0]))
        for _, t, address, _, r in run.sightings():
            device = owner.get(address)
            if not device:
                continue
            span = spans[device][address]
            span[0] = t if span[0] is None else min(span[0], t)
            span[1] = t if span[1] is None else max(span[1], t)
            span[2] += r.get("n", 1)
        for device in run.advertisers:
            recs = run.files[device]
            args = run.start[device].get("args", {})
            kind = args.get("restart_kind") if args.get("restart_every") else "idle"
            adv = args.get("adv", "ios")
            model, os_name = run.model(device)
            addresses = sorted(spans[device].items(), key=lambda kv: kv[1][0])
            starts = [r for r in recs if r.get("test") == "adv_start"]
            end = next((r["t"] for r in recs if r.get("test") == "done"), None)
            first_start = starts[0]["t"] if starts else None
            off = run.offsets.get(device, 0)
            for i, (address, (first, last, n)) in enumerate(addresses):
                change_rows.append({"run": run.name, "device": device, "model": model, "os": os_name, "kind": kind, "adv": adv,
                                    "address": address, "first_ms": first, "last_ms": last, "sightings": n})
            if kind == "idle":
                changes = [span[0] for _, span in addresses]
                for i in range(1, len(changes)):
                    interval_rows.append({"run": run.name, "device": device, "model": model, "os": os_name, "adv": adv,
                                          "index": i, "censored": i == 1, "change_ms": changes[i],
                                          "interval_s": round((changes[i] - changes[i - 1]) / 1000, 1)})
                continue
            # Control: compare address before and after each restart. Android: by advertisement ID; iOS: by time.
            cycles = defaultdict(set)
            if any(s.get("ad_id") for s in starts):
                ad_cycle = {s["ad_id"]: s.get("cycle") for s in starts if s.get("ad_id")}
                for _, t, address, ident, _ in run.sightings():
                    if ident and ident.startswith("a:") and ident[2:] in ad_cycle:
                        cycles[ad_cycle[ident[2:]]].add(address)
                # bucket records carry the id of the first sighting; ADV-only ids ("svc") are attributed by address
                for s in starts:
                    cycles.setdefault(s.get("cycle"), set())
            else:
                # iOS: device-clock adv_start times converted to the scanner clock (iOS offset unknown, ±1 s).
                bounds = [(s.get("cycle"), s["t"] - off) for s in starts]
                for _, t, address, _, _ in run.sightings():
                    if owner.get(address) != device:
                        continue
                    for idx, (cycle, t0) in enumerate(bounds):
                        t1 = bounds[idx + 1][1] - 2500 if idx + 1 < len(bounds) else 1e18
                        if t0 + 1000 <= t <= t1:
                            cycles[cycle].add(address)
            ordered = sorted(cycles.items(), key=lambda kv: kv[0])
            for (c_prev, a_prev), (c, a_now) in zip(ordered, ordered[1:]):
                if not a_prev or not a_now:
                    status = "unobserved"
                else:
                    status = "new" if not (a_now & a_prev) else "same"
                control_rows.append({"run": run.name, "device": device, "model": model, "os": os_name, "adv": adv, "kind": kind,
                                     "cycle": c, "status": status, "addresses_in_cycle": len(a_now),
                                     "prev": "+".join(sorted(a_prev)), "now": "+".join(sorted(a_now))})
        own = [r for d in run.advertisers for r in run.files[d] if r.get("test") in ("own_address_call", "own_address", "own_type_set")]
        if own:
            errors = sorted({r.get("error") or r.get("address") for r in own})
            notes.append(f"{run.name}: {len(own)} own-address probes; outcomes: {errors}; onOwnAddressRead fired "
                         f"{sum(1 for r in own if r.get('test') == 'own_address')} times")
    out_dir.mkdir(parents=True, exist_ok=True)
    write_csv(out_dir / "d1-addresses.csv", change_rows)
    write_csv(out_dir / "d1-intervals.csv", interval_rows)
    write_csv(out_dir / "d1-control.csv", control_rows)
    lines = ["# D1 summary: BLE address rotation and control", "", f"Runs: {', '.join(r.name for r in runs)}", "",
             "## Idle rotation intervals (scanner clock; first interval is censored by the run start)", "",
             "| Device | Model | OS | API | Addresses | Uncensored intervals (min) | Median (min) |", "|---|---|---|---|---|---|---|"]
    by_device = defaultdict(list)
    for row in interval_rows:
        by_device[(row["run"], row["device"])].append(row)
    idle = [(run, d) for run in runs for d in run.advertisers if not run.start[d].get("args", {}).get("restart_every")]
    for run, device in idle:
        rows = by_device.get((run.name, device), [])
        values = [r["interval_s"] / 60 for r in rows if not r["censored"]]
        count = sum(1 for r in change_rows if r["run"] == run.name and r["device"] == device)
        model, os_name = run.model(device)
        lines.append(f"| {device} | {model} | {os_name} | {run.start[device].get('args', {}).get('adv', 'ios')} | {count} | "
                     f"{', '.join(f'{v:.1f}' for v in values) or '—'} | {statistics.median(values):.1f} |" if values else
                     f"| {device} | {model} | {os_name} | {run.start[device].get('args', {}).get('adv', 'ios')} | {count} | — | — |")
    lines += ["", "## Restart control (address after restart vs before)", "",
              "| Device | Model | OS | API / kind | Cycles | New | Same | Unobserved | Cycles with >1 address |", "|---|---|---|---|---|---|---|---|---|"]
    by_ctl = defaultdict(list)
    for row in control_rows:
        by_ctl[(row["run"], row["device"])].append(row)
    for (run_name, device), rows in by_ctl.items():
        lines.append(f"| {device} | {rows[0]['model']} | {rows[0]['os']} | {rows[0]['adv']} / {rows[0]['kind']} | {len(rows)} | "
                     f"{sum(r['status'] == 'new' for r in rows)} | {sum(r['status'] == 'same' for r in rows)} | "
                     f"{sum(r['status'] == 'unobserved' for r in rows)} | {sum(r['addresses_in_cycle'] > 1 for r in rows)} |")
    lines += ["", "## Own-address probes", ""] + [f"- {n}" for n in notes]
    (out_dir / "summary-d1.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))


def write_csv(path, rows):
    if not rows:
        path.write_text("")
        return
    with open(path, "w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    runs = [Run(d) for d in sys.argv[2:]]
    out = Path(__file__).resolve().parent / "results" / "summary"
    {"d1": d1}[sys.argv[1]](runs, out)


if __name__ == "__main__":
    main()
