#!/usr/bin/env python3
"""Analysis for the ticket-13 discovery PROTOTYPE runs (stdlib only).

  python3 host/analyze_discovery.py d1 RUN_DIR [RUN_DIR...]   # rotation + restart control -> summary-d1.md, CSVs
  python3 host/analyze_discovery.py d2 RUN_DIR [RUN_DIR...]   # background matrix (T2/T8/T10) + T11 -> summary-d2.md, CSVs
  python3 host/analyze_discovery.py t3 RUN_DIR [RUN_DIR...]   # overflow discovery trials -> summary-t3.md, CSV
  python3 host/analyze_discovery.py d4 RUN_DIR [RUN_DIR...]   # bakeoff phub runs: hub-side inbound closes -> summary-d4.md, CSV

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
                # A change time is exact when the previous address was seen ≤ 10 s before the new one first appeared;
                # otherwise it fell into a scanner gap. An interval counts only between two exact changes.
                exact = [False] + [addresses[i][1][0] - addresses[i - 1][1][1] <= 10000 for i in range(1, len(addresses))]
                for i in range(1, len(addresses)):
                    previous = addresses[i - 1][1]
                    interval_rows.append({"run": run.name, "device": device, "model": model, "os": os_name, "adv": adv,
                                          "index": i, "exact_change": exact[i], "censored": not (exact[i] and exact[i - 1]),
                                          "change_ms": addresses[i][1][0],
                                          "gap_s": round((addresses[i][1][0] - previous[1]) / 1000, 1),
                                          "interval_s": round((addresses[i][1][0] - previous[0]) / 1000, 1),
                                          "prev_lifetime_min_s": round((previous[1] - previous[0]) / 1000, 1)})
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
             "## Idle rotation intervals (scanner clock)", "",
             "Exact interval = between two changes that each happened while a scanner was receiving (old address seen",
             "≤ 10 s before the new one). Longest lifetime = longest observed first-to-last span of one address (lower bound).", "",
             "| Device | Model | OS | API | Addresses | Exact intervals (min) | Median (min) | Longest lifetime (min) |",
             "|---|---|---|---|---|---|---|---|"]
    by_device = defaultdict(list)
    for row in interval_rows:
        by_device[(row["run"], row["device"])].append(row)
    idle = [(run, d) for run in runs for d in run.advertisers if not run.start[d].get("args", {}).get("restart_every")]
    for run, device in idle:
        rows = by_device.get((run.name, device), [])
        values = [r["interval_s"] / 60 for r in rows if not r["censored"]]
        count = sum(1 for r in change_rows if r["run"] == run.name and r["device"] == device)
        model, os_name = run.model(device)
        lifetimes = [(int(r["last_ms"]) - int(r["first_ms"])) / 60000 for r in change_rows if r["run"] == run.name and r["device"] == device]
        longest = f"{max(lifetimes):.1f}" if lifetimes else "—"
        lines.append(f"| {device} | {model} | {os_name} | {run.start[device].get('args', {}).get('adv', 'ios')} | {count} | "
                     f"{', '.join(f'{v:.1f}' for v in values) or '—'} | {f'{statistics.median(values):.1f}' if values else '—'} | {longest} |")
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


def offsets(run):
    """Latest clock offset per device (device minus host, ms); iOS via the idevicedate second tick (±~50 ms)."""
    out = {}
    for r in run.host:
        if r.get("event") == "clock" and "offset_ms" in r:
            out[r["device"]] = r["offset_ms"]
    return out


def scanner_state(run, device):
    s = run.start[device] or {}
    args = s.get("args", {})
    platform = (s.get("reporter") or {}).get("platform", "")
    if platform == "ios":
        return f"iOS {'bg' if args.get('bg') == '1' else 'fg'} {args.get('filter', 'service')}" + (" rescan" if args.get("rescan_s") else "")
    off = "screen-off FGS" if args.get("delay_s") else "fg"
    extra = " passive" if args.get("scan_type") == "passive" else ""
    return f"Android {off} {args.get('filter', 'none')} {args.get('scan_mode', 'low_latency')}{extra}"


def window_recall(times, start, end, width):
    if not times or end - start < width:
        return None
    windows = int((end - start) // width)
    hit = {int((t - start) // width) for t in times if start <= t < start + windows * width}
    return len(hit) / windows


def cells(run):
    out, open_cells = [], {}
    for r in run.host:
        if r.get("event") == "cell_start":
            open_cells[r["cell"]] = r
        elif r.get("event") == "cell_end" and r["cell"] in open_cells:
            start = open_cells.pop(r["cell"])
            out.append({"cell": r["cell"], "advertiser": r["advertiser"], "t0": start["t"], "t1": r["t"]})
    return out


def d2(runs, out_dir):
    rows, raw_rows, t11_rows = [], [], []
    for run in runs:
        off = offsets(run)
        for c in cells(run):
            adv = c["advertiser"]
            recs = run.files.get(adv, [])
            platform = ((run.start.get(adv) or {}).get("reporter") or {}).get("platform")
            # adv_start of this cell (device clock -> host clock)
            starts = [r for r in recs if r.get("test") == "adv_start" and c["t0"] - 5000 <= r["t"] - off.get(adv, 0) <= c["t1"]]
            if not starts:
                continue
            t_adv = starts[0]["t"] - off.get(adv, 0)
            ad_ids = {r.get("ad_id") for r in recs if r.get("test") == "adv_start" and r.get("ad_id")}
            state_args = (run.start.get(adv) or {}).get("args", {})
            for scanner in run.scanners:
                if scanner == adv:
                    continue
                so = off.get(scanner, 0)
                times, n_total, n_hint, first, first_id, addresses, apps = [], 0, 0, None, None, set(), set()
                long_lived = None
                if c["cell"] == "t11":
                    # pixel6's identifier persists; the ticker's change every ~10 s
                    spans = defaultdict(lambda: [1e18, 0])
                    for r in run.files[scanner]:
                        if r.get("test") == "s" and r.get("id") and c["t0"] <= r["t"] - so <= c["t1"]:
                            spans[r["a"]][0] = min(spans[r["a"]][0], r["t"]); spans[r["a"]][1] = max(spans[r["a"]][1], r["t"])
                    long_lived = {a for a, (lo, hi) in spans.items() if hi - lo > 60000}
                for r in run.files[scanner]:
                    if r.get("test") not in ("s", "adv"):
                        continue
                    t = r["t"] - so
                    if not (t_adv - 1000 <= t <= c["t1"]):
                        continue
                    ident = r.get("id") or ""
                    if ident.startswith("a:"):
                        mine = ident[2:] in ad_ids
                    elif long_lived is not None:
                        mine = r["a"] in long_lived
                    elif platform == "ios":
                        mine = ident in ("svc", "apple-ovf") or ident.startswith("name:")
                    else:
                        mine = ident == "svc"
                    if not mine:
                        continue
                    if r.get("test") == "adv":
                        if platform == "ios" and run.start[scanner].get("reporter", {}).get("platform") == "android":
                            raw_rows.append({"run": run.name, "cell": c["cell"], "scanner": scanner, "state": scanner_state(run, scanner),
                                             "address": r["a"], "id": ident, "raw": r.get("raw"), "legacy": r.get("legacy"),
                                             "conn": r.get("conn")})
                        continue
                    times.append(t)
                    n_total += r.get("n", 1)
                    n_hint += r.get("n", 1) if ident.startswith("a:") else 0
                    addresses.add(r["a"])
                    if first is None or t < first:
                        first, first_id = t, ident
                row = {"run": run.name, "cell": c["cell"], "advertiser": adv, "adv_state": c["cell"].split("-")[-1],
                       "adv_bg": state_args.get("bg", ""), "scanner": scanner, "scanner_state": scanner_state(run, scanner),
                       "latency_s": round((first - t_adv) / 1000, 2) if first is not None else None,
                       "sighting_buckets": len(times), "sightings": n_total,
                       "per_min": round(n_total / ((c["t1"] - t_adv) / 60000), 1),
                       "hint_frac": round(n_hint / n_total, 3) if n_total else None, "first_id": first_id,
                       "addresses": len(addresses)}
                for width in (10, 30, 60):
                    value = window_recall(times, first, c["t1"], width * 1000) if first is not None else None
                    row[f"recall_{width}s"] = round(value, 3) if value is not None else None
                rows.append(row)
                if c["cell"] == "t11" and run.start[scanner].get("reporter", {}).get("platform") == "ios":
                    restarts = [r["t"] - so for r in run.files[scanner] if r.get("test") == "scan_restart"]
                    rere = sum(1 for i, t0 in enumerate(restarts)
                               if any(t0 < t <= (restarts[i + 1] if i + 1 < len(restarts) else t0 + 10000) for t in times))
                    t11_rows.append({"run": run.name, "scanner": scanner, "state": scanner_state(run, scanner),
                                     "restarts": len(restarts), "restarts_followed_by_report": rere,
                                     "report_buckets": len(times), "first_s": row["latency_s"]})
    out_dir.mkdir(parents=True, exist_ok=True)
    write_csv(out_dir / "d2-cells.csv", rows)
    write_csv(out_dir / "d2-raw-adv.csv", raw_rows)
    write_csv(out_dir / "t11.csv", t11_rows)
    lines = ["# D2 summary: background visibility matrix", "", f"Runs: {', '.join(r.name for r in runs)}", "",
             "Latency = first sighting − advertiser start (host clock). Recall = share of complete windows with ≥ 1 sighting,",
             "from the first sighting to the cell end. Hint = share of sightings carrying the layout-A service data.", "",
             "| Cell | Scanner | Scanner state | Latency (s) | Per min | Recall 10 s | 30 s | 60 s | Hint | First id |",
             "|---|---|---|---|---|---|---|---|---|---|"]
    for r in rows:
        lines.append(f"| {r['cell']} | {r['scanner']} | {r['scanner_state']} | {r['latency_s'] if r['latency_s'] is not None else 'not seen'} | "
                     f"{r['per_min']} | {r['recall_10s']} | {r['recall_30s']} | {r['recall_60s']} | {r['hint_frac']} | {r['first_id']} |")
    if raw_rows:
        lines += ["", "## T2: raw bytes from iPhones as received by Android scanners (distinct per cell, scanner, address)", ""]
        seen = set()
        for r in raw_rows:
            key = (r["cell"], r["scanner"], r["raw"])
            if key in seen:
                continue
            seen.add(key)
            lines.append(f"- {r['cell']} → {r['scanner']} ({r['state']}), {r['address']}, id {r['id']}: `{r['raw']}`")
    if t11_rows:
        lines += ["", "## T11: background iOS re-report after scan restarts", "",
                  "| Scanner | State | Restarts | Restarts followed by a report of the stable peer | Report buckets |", "|---|---|---|---|---|"]
        for r in t11_rows:
            lines.append(f"| {r['scanner']} | {r['state']} | {r['restarts']} | {r['restarts_followed_by_report']} | {r['report_buckets']} |")
    (out_dir / "summary-d2.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))


def t3(runs, out_dir):
    rows = []
    for run in runs:
        for scanner in run.scanners:
            trial = None
            for r in run.files[scanner]:
                if r.get("test") == "disc_start":
                    args = r.get("args", {})
                    trial = {"run": run.name, "scanner": scanner, "os": (r.get("reporter") or {}).get("os"), "case": args.get("case"),
                             "trial": args.get("trial"), "scan_start": None, "first_s": None, "first_app": None,
                             "duration_s": int(args.get("duration", 0))}
                    rows.append(trial)
                elif trial is None:
                    continue
                elif r.get("test") == "scan_start" and trial["scan_start"] is None:
                    trial["scan_start"] = r["t"]
                elif r.get("test") == "adv" and r.get("id") in ("svc", "apple-ovf") and trial["first_s"] is None and trial["scan_start"]:
                    trial["first_s"] = round((r["t"] - trial["scan_start"]) / 1000, 2)
                    trial["first_app"] = r.get("app")
                    trial["ovf"] = r.get("ovf")
    rows = [r for r in rows if r["case"]]
    out_dir.mkdir(parents=True, exist_ok=True)
    write_csv(out_dir / "t3-trials.csv", rows)
    lines = ["# T3 summary: iOS overflow-area discovery (advertiser in background)", "",
             f"Runs: {', '.join(r.name for r in runs)}", "", "Latency = first discovery − scan start on the scanner clock.", "",
             "| Case | Scanner | OS | Trials | Found | Median (s) | Max (s) |", "|---|---|---|---|---|---|---|"]
    groups = defaultdict(list)
    for r in rows:
        groups[(r["case"], r["scanner"], r["os"])].append(r)
    for (case, scanner, os_name), group in sorted(groups.items()):
        found = [r["first_s"] for r in group if r["first_s"] is not None]
        lines.append(f"| {case} | {scanner} | {os_name} | {len(group)} | {len(found)} | "
                     f"{statistics.median(found) if found else '—'} | {max(found) if found else '—'} |")
    by_case = defaultdict(list)
    for r in rows:
        by_case[r["case"]].append(r)
    lines += ["", "| Case (pooled) | Trials | Found | Median (s) | p90 (s) |", "|---|---|---|---|---|"]
    for case, group in sorted(by_case.items()):
        found = sorted(r["first_s"] for r in group if r["first_s"] is not None)
        p90 = found[min(len(found) - 1, int(0.9 * len(found)))] if found else "—"
        lines.append(f"| {case} | {len(group)} | {len(found)} | {statistics.median(found) if found else '—'} | {p90} |")
    (out_dir / "summary-t3.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))


def d4(runs, out_dir):
    """A hub close is "early" when the central's hold ended with a failure (the link dropped before `duration`)."""
    rows, lines = [], ["# D4 iPhone 15 inbound links (phub)", "",
                       "| run | centrals (arrival order) | max links | early closes | links at each early close |",
                       "|---|---|---|---|---|"]
    for run in runs:
        hub = next((recs for d, recs in run.files.items() if d.endswith("-peripheral")), [])
        holds = {d[: -len("-hold")]: recs for d, recs in run.files.items() if d.endswith("-hold")}
        active, max_links, at_close = 0, 0, {}
        for r in hub:
            if r.get("test") == "peripheral_link_open":
                active = r.get("active_links") or active + 1
                max_links = max(max_links, active)
            elif r.get("test") == "peripheral_link_end":
                at_close.setdefault((r.get("central") or {}).get("model"), []).append((r.get("date"), active, r.get("error")))
                active = max(0, active - 1)
        order = sorted(holds, key=lambda d: next((r.get("date") for r in holds[d] if r.get("test") == "hold_connect"), "~"))
        early = []
        for device in order:
            recs = holds[device]
            connect = next((r for r in recs if r.get("test") == "hold_connect"), {})
            end = next((r for r in recs if r.get("test") == "hold_end"), {})
            model = (end.get("central") or connect.get("central") or (recs[0].get("reporter") if recs else {}) or {}).get("model")
            hub_ends = at_close.get(model, [])
            failed = bool(end) and end.get("outcome") != "ok"
            rows.append({"run": run.name, "central": device, "connect": connect.get("date"), "connect_outcome": connect.get("outcome"),
                         "hold_end": end.get("date"), "hold_outcome": end.get("outcome"), "hold_error": end.get("error"),
                         "hub_end": hub_ends[0][0] if hub_ends else None, "links_before_hub_end": hub_ends[0][1] if hub_ends else None,
                         "hub_error": hub_ends[0][2] if hub_ends else None})
            if failed:
                early.append((device, hub_ends[0][1] if hub_ends else "?"))
        lines.append(f"| {run.name} | {', '.join(order)} | {max_links} | {len(early)} | "
                     f"{', '.join(f'{d}@{n}' for d, n in early) or '—'} |")
    out_dir.mkdir(parents=True, exist_ok=True)
    write_csv(out_dir / "d4-links.csv", rows)
    (out_dir / "summary-d4.md").write_text("\n".join(lines) + "\n")
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
    {"d1": d1, "d2": d2, "t3": t3, "d4": d4}[sys.argv[1]](runs, out)


if __name__ == "__main__":
    main()
