#!/usr/bin/env python3
"""Energy prototype analysis (ticket 23). Run with the perfetto venv: /tmp/pfv/bin/python analyze.py ...

  window <trace> <jsonl> <script.log>   -> JSON for one window (rail power, validity, traffic) on stdout
  summary                               -> results/summary.json + markdown tables on stdout
"""
import json
import statistics
import sys
from pathlib import Path

RESULTS = Path(__file__).resolve().parent.parent / "results"
BATTERY_MWH = 3000 * 3.85  # 11,550 mWh reference battery


def records(path):
    out = []
    for line in Path(path).read_text().splitlines():
        line = line.strip()
        if line.startswith("{"):
            try:
                out.append(json.loads(line))
            except json.JSONDecodeError:
                pass
    return out


def interp(samples, ts):
    """Linear interpolation of a cumulative counter at ts; samples sorted [(ts, value)]."""
    if not samples or ts < samples[0][0]:
        return None
    if ts > samples[-1][0]:
        # A6: the device may suspend right after the app releases its wake lock, so the last 1 s poll can land a
        # few ms before window_end; extrapolate the last segment by at most 2 s.
        if len(samples) < 2 or ts - samples[-1][0] > 2e9:
            return None
        (t0, v0), (t1, v1) = samples[-2], samples[-1]
        return v1 + (v1 - v0) * (ts - t1) / (t1 - t0)
    lo, hi = 0, len(samples) - 1
    while hi - lo > 1:
        mid = (lo + hi) // 2
        if samples[mid][0] <= ts:
            lo = mid
        else:
            hi = mid
    (t0, v0), (t1, v1) = samples[lo], samples[hi]
    return v0 if t1 == t0 else v0 + (v1 - v0) * (ts - t0) / (t1 - t0)


def rails(trace):
    from perfetto.trace_processor import TraceProcessor
    tp = TraceProcessor(trace=str(trace))
    rows = tp.query("select t.name as name, c.ts as ts, c.value as value from counter c join counter_track t on c.track_id = t.id "
                    "order by t.name, c.ts")
    series = {}
    for r in rows:
        series.setdefault(r.name, []).append((r.ts, r.value))
    tp.close()
    return series


def window(trace, jsonl, log_path):
    recs = records(jsonl)
    log = Path(log_path).read_text() if Path(log_path).exists() else ""
    by = {}
    for r in recs:
        by.setdefault(r["test"], []).append(r)
    start = (by.get("window_start") or [None])[-1]
    end = (by.get("window_end") or [None])[-1]
    done = (by.get("done") or [None])[-1]
    energy_start = (by.get("energy_start") or [{}])[-1]
    invalid = []
    if "wifi_on=0" not in log.split("wifi_off", 1)[-1].split("\n", 1)[0]:
        invalid.append("wifi not off at window script start")
    app_done_line = next((l for l in log.splitlines() if l.startswith("app_done")), "")
    if "wifi_on=0" not in app_done_line:
        invalid.append("wifi not off at app_done")
    if not start or not end:
        invalid.append("no window_start/window_end")
    if not done or done.get("outcome") != "ok":
        invalid.append(f"app done={done and done.get('outcome')} {done and done.get('error')}")
    out = {"energy": energy_start.get("mode"), "args": energy_start.get("args")}
    if start and end:
        for k in ("wifi_enabled", "screen_interactive"):
            if start.get(k) or end.get(k):
                invalid.append(f"{k} true")
        if start.get("plugged") != end.get("plugged"):
            invalid.append(f"plug changed {start.get('plugged')}->{end.get('plugged')}")
        if end.get("window_ms", 0) < 590_000 and "trial" not in str(jsonl):
            invalid.append(f"window short {end.get('window_ms')} ms")
        if end.get("lost"):
            invalid.append(f"links lost {end['lost']}")
        for e in by.get("system_event", []):
            if start["boot_ns"] <= e["boot_ns"] <= end["boot_ns"] and e.get("event") in ("SCREEN_ON", "ACTION_POWER_CONNECTED", "ACTION_POWER_DISCONNECTED", "POWER_CONNECTED", "POWER_DISCONNECTED"):
                invalid.append(f"system_event {e.get('event')} in window")
        for e in by.get("link_lost", []):
            if e["boot_ns"] <= end["boot_ns"]:
                invalid.append(f"link_lost {e.get('model')}")
        out["state_start"] = {k: start.get(k) for k in ("level", "temp_dc", "plugged", "battery_status", "device_idle", "light_idle")}
        out["state_end"] = {k: end.get(k) for k in ("level", "temp_dc", "plugged", "battery_status", "device_idle", "light_idle", "scan_results")}
        out["links"] = start.get("links")
        out["window_s"] = (end["boot_ns"] - start["boot_ns"]) / 1e9
        for k in ("sent_bytes", "messages", "msg_size", "send_ms", "kbps_sent", "forwarded_in_window", "rate", "size", "expected"):
            if k in end:
                out[k] = end[k]
        ack = (by.get("ack") or [None])[-1]
        mode = energy_start.get("mode", "")
        if "bulk" in mode:
            if not ack or ack.get("acked_bytes") is None:
                invalid.append("bulk ack missing")
            else:
                out["acked_bytes"] = ack["acked_bytes"]
                # Peer acks cumulative bytes received; bytes sent after window_end are only the BULK_END frame.
                out["kbps_acked"] = ack["acked_bytes"] / 1024 / out["window_s"]
        if "relay" in mode:
            if not ack or ack.get("sink_acked_bytes") is None:
                invalid.append("relay sink ack missing")
            else:
                out.update({k: ack.get(k) for k in ("sink_acked_bytes", "forwarded_total", "forwarded_bytes_total")})
        n_want = int((energy_start.get("args") or {}).get("n", 0) or 0)
        ups = by.get("link_up", [])
        out["link_up"] = [{k: u.get(k) for k in ("model", "bearer", "mtu", "phy", "attempt")} for u in ups]
        out["link_fail"] = len(by.get("link_fail", []))
        if n_want and len(ups) < n_want:
            invalid.append(f"links {len(ups)}/{n_want}")
        bursts = [b["on_ms"] for b in by.get("burst", []) if "on_ms" in b]
        gaps = [g["off_ms"] for g in by.get("gap", []) if "off_ms" in g]
        if bursts and gaps:
            out["scan_duty_logged"] = sum(bursts) / (sum(bursts) + sum(gaps)) if len(bursts) == len(gaps) else statistics.mean(bursts) / (statistics.mean(bursts) + statistics.mean(gaps))
            out["burst_ms_mean"] = statistics.mean(bursts)
            out["gap_ms_mean"] = statistics.mean(gaps)
        summ = (by.get("scan_summary") or [None])[-1]
        if summ:
            out["scan_summary"] = {k: summ.get(k) for k in ("bursts", "empty_bursts", "results")}
            if start and end and summ.get("bursts") and out.get("window_s") and "scan_on_s" in (energy_start.get("args") or {}):
                a = energy_start["args"]
                period = float(a["scan_on_s"]) + float(a["scan_off_s"])
                out["bursts_expected"] = out["window_s"] / period
                if summ["bursts"] < 0.9 * out["bursts_expected"] and (energy_start["args"].get("wakelock") != "0"):
                    invalid.append(f"scan duty not honored: {summ['bursts']} bursts, expected {out['bursts_expected']:.0f}")
        out["adv_starts"] = len(by.get("adv_start", []))
    power = {}
    if start and end:
        try:
            series = rails(trace)
        except Exception as e:  # noqa: BLE001
            series = {}
            invalid.append(f"trace parse failed {e}")
        t0, t1 = start["boot_ns"], end["boot_ns"]
        total = 0.0
        cpu = 0.0
        for name, samples in series.items():
            if not name.startswith("power.rails."):
                continue
            e0, e1 = interp(samples, t0), interp(samples, t1)
            if e0 is None or e1 is None:
                continue
            mw = (e1 - e0) / ((t1 - t0) / 1e9) / 1000.0  # µWs / s = µW -> mW
            short = name[len("power.rails."):]
            power[short] = round(mw, 3)
            total += mw
            if short.startswith("cpu."):
                cpu += mw
        if "wifi.bt" not in power:
            invalid.append("wifi.bt rail missing or does not cover window")
        else:
            power["all"] = round(total, 3)
            power["cpu"] = round(cpu, 3)
        cur = [v for ts, v in series.get("batt.current_ua", []) if t0 <= ts <= t1]
        if cur:
            out["batt_current_ua_mean"] = statistics.mean(cur)
        samples = series.get("power.rails.wifi.bt", [])
        inside = [s for s in samples if t0 <= s[0] <= t1]
        if len(inside) >= 3:
            dts = [(b[0] - a[0]) / 1e9 for a, b in zip(inside, inside[1:])]
            out["rail_sample_period_s"] = statistics.median(dts)
            # Per-minute wifi.bt power to see drift / Doze phases.
            per = []
            step = 60e9
            t = t0
            while t + step <= t1:
                a, b = interp(samples, t), interp(samples, t + step)
                if a is not None and b is not None:
                    per.append(round((b - a) / 60 / 1000, 2))
                t += step
            out["wifi_bt_per_min_mw"] = per
    out["power_mw"] = power
    out["invalid"] = invalid
    out["valid"] = not invalid
    return out


# ---------------------------------------------------------------- summary

def fmt(v, d=1):
    return "–" if v is None else f"{v:.{d}f}"


RAW = Path("copilot-session/files/energy-raw")


def reanalyze():
    """Recomputes every Pixel window from raw files with the current analysis code (-> windows.final.jsonl)."""
    out = []
    for line in (RESULTS / "windows.jsonl").read_text().splitlines():
        row = json.loads(line)
        rid = row["rid"]
        trace, jl, lg = RAW / f"{rid}.pftrace", RESULTS / "windows" / f"{rid}.jsonl", RESULTS / "windows" / f"{rid}.log"
        if trace.exists() and jl.exists():
            fresh = window(trace, jl, lg)
            if fresh.get("valid") != row.get("valid"):
                print(f"{rid}: valid {row.get('valid')} -> {fresh.get('valid')} {fresh.get('invalid')}")
            row.update(fresh)
        out.append(row)
    (RESULTS / "windows.final.jsonl").write_text("".join(json.dumps(r) + "\n" for r in out))


def summary():
    src = RESULTS / "windows.final.jsonl"
    src = src if src.exists() else RESULTS / "windows.jsonl"
    rows = [json.loads(l) for l in src.read_text().splitlines() if l.strip()]
    rows = [r for r in rows if r.get("scenario") != "overnight" and r.get("rep", 0) > 0]
    by_rep = {}
    for r in rows:
        by_rep.setdefault(r["rep"], []).append(r)
    deltas = {}
    for rep, rs in by_rep.items():
        rs.sort(key=lambda r: r["date"])
        bases = [(i, r) for i, r in enumerate(rs) if r["scenario"] == "base" and r.get("valid")]
        for i, r in enumerate(rs):
            if r["scenario"] == "base" or not r.get("valid"):
                continue
            if any(d["rep"] == rep for d in deltas.get(r["scenario"], [])):
                continue  # one window per scenario and rep: the first valid one
            before = [b for j, b in bases if j < i][-1:]
            after = [b for j, b in bases if j > i][:1]
            br = before + after
            if not br:
                continue
            d = {"rep": rep, "rid": r["rid"], "row": r}
            for rail in ("wifi.bt", "all", "cpu"):
                d[rail] = r["power_mw"][rail] - statistics.mean(b["power_mw"][rail] for b in br)
            d["base_wifi_bt"] = statistics.mean(b["power_mw"]["wifi.bt"] for b in br)
            # Secondary (amendment A4): median of per-minute wifi.bt, robust to the short system bursts (~250 mW, ~3 s).
            med = lambda x: statistics.median(x["wifi_bt_per_min_mw"]) if x.get("wifi_bt_per_min_mw") else None
            if med(r) is not None and all(med(b) is not None for b in br):
                d["wifi.bt.med"] = med(r) - statistics.mean(med(b) for b in br)
            deltas.setdefault(r["scenario"], []).append(d)
    base_abs = {rail: [r["power_mw"][rail] for r in rows if r["scenario"] == "base" and r.get("valid")] for rail in ("wifi.bt", "all", "cpu")}
    table = []
    for sid, ds in deltas.items():
        e = {"scenario": sid, "n": len(ds)}
        for rail in ("wifi.bt", "all", "cpu"):
            vals = [d[rail] for d in ds]
            e[rail] = {"mean": statistics.mean(vals), "sd": statistics.stdev(vals) if len(vals) > 1 else None,
                       "min": min(vals), "max": max(vals), "values": [round(v, 2) for v in vals]}
        mv = [d["wifi.bt.med"] for d in ds if "wifi.bt.med" in d]
        if mv:
            e["wifi.bt.med"] = {"mean": statistics.mean(mv), "sd": statistics.stdev(mv) if len(mv) > 1 else None, "values": [round(v, 2) for v in mv]}
        e["pct_per_h_wifi_bt"] = e["wifi.bt"]["mean"] / BATTERY_MWH * 100
        e["pct_per_h_all"] = e["all"]["mean"] / BATTERY_MWH * 100
        e["abs_wifi_bt"] = statistics.mean(d["row"]["power_mw"]["wifi.bt"] for d in ds)
        rows_ = [d["row"] for d in ds]
        if sid.startswith("idle_"):
            n = int(sid.rsplit("_", 1)[1])
            e["per_link_mw_wifi_bt"] = e["wifi.bt"]["mean"] / n
            e["per_link_mw_all"] = e["all"]["mean"] / n
        if sid.startswith("bulk_"):
            kb = [d["row"].get("acked_bytes", 0) / 1024 for d in ds]
            e["kbps_acked"] = statistics.mean(r.get("kbps_acked", 0) for r in rows_)
            e["mj_per_kb_wifi_bt"] = statistics.mean(d["wifi.bt"] * d["row"]["window_s"] / k for d, k in zip(ds, kb) if k)
            e["mj_per_kb_all"] = statistics.mean(d["all"] * d["row"]["window_s"] / k for d, k in zip(ds, kb) if k)
        if sid.startswith("relay_"):
            fw = [d["row"].get("forwarded_in_window", 0) for d in ds]
            e["forwarded_in_window"] = statistics.mean(fw)
            kb = [f * 200 / 1024 for f in fw]
            e["mj_per_msg_all"] = statistics.mean(d["all"] * d["row"]["window_s"] / f for d, f in zip(ds, fw) if f)
            e["mj_per_kb_all"] = statistics.mean(d["all"] * d["row"]["window_s"] / k for d, k in zip(ds, kb) if k)
            e["mj_per_kb_wifi_bt"] = statistics.mean(d["wifi.bt"] * d["row"]["window_s"] / k for d, k in zip(ds, kb) if k)
        if any("scan_duty_logged" in r for r in rows_):
            e["scan_duty_logged"] = statistics.mean(r["scan_duty_logged"] for r in rows_ if "scan_duty_logged" in r)
        e["temp_c"] = statistics.mean((r.get("state_end") or {}).get("temp_dc", 0) / 10 for r in rows_)
        table.append(e)
    order = ["adv_fast", "adv_slow", "scan_low", "scan_med", "scan_high", "scanmode_bal", "scanmode_lp", "comb_low", "comb_med", "comb_high"]
    table.sort(key=lambda e: (order.index(e["scenario"]) if e["scenario"] in order else 99, e["scenario"]))
    result = {"baseline_abs_mw": {k: {"mean": statistics.mean(v), "sd": statistics.stdev(v) if len(v) > 1 else None, "n": len(v)} for k, v in base_abs.items() if v},
              "scenarios": table,
              "invalid_windows": [{k: r.get(k) for k in ("rid", "scenario", "rep", "invalid")} for r in rows if not r.get("valid")]}
    (RESULTS / "summary.json").write_text(json.dumps(result, indent=1) + "\n")
    print("| scenario | n | wifi.bt ΔmW (±SD) [min–max] | wifi.bt median-minute ΔmW (±SD) | all rails ΔmW (±SD) | CPU ΔmW | %/h (wifi.bt / all) | extra |")
    print("|---|---|---|---|---|---|---|---|")
    for e in table:
        extra = []
        if "per_link_mw_wifi_bt" in e:
            extra.append(f"per link-hour {fmt(e['per_link_mw_wifi_bt'], 2)} / {fmt(e['per_link_mw_all'], 2)} mWh")
        if "kbps_acked" in e:
            extra.append(f"{fmt(e['kbps_acked'], 1)} KiB/s; {fmt(e['mj_per_kb_wifi_bt'], 3)} / {fmt(e['mj_per_kb_all'], 3)} mJ/KiB")
        if "forwarded_in_window" in e:
            extra.append(f"fwd {e['forwarded_in_window']:.0f}; {fmt(e['mj_per_msg_all'], 2)} mJ/msg (all); {fmt(e['mj_per_kb_wifi_bt'], 2)} / {fmt(e['mj_per_kb_all'], 2)} mJ/KiB")
        if "scan_duty_logged" in e:
            extra.append(f"duty {e['scan_duty_logged']:.2f}")
        w, a, c = e["wifi.bt"], e["all"], e["cpu"]
        print(f"| {e['scenario']} | {e['n']} | {fmt(w['mean'], 2)} ± {fmt(w['sd'], 2)} [{fmt(w['min'], 1)}–{fmt(w['max'], 1)}] | "
              f"{fmt((e.get('wifi.bt.med') or {}).get('mean'), 2)} ± {fmt((e.get('wifi.bt.med') or {}).get('sd'), 2)} | "
              f"{fmt(a['mean'])} ± {fmt(a['sd'])} | {fmt(c['mean'])} | {fmt(e['pct_per_h_wifi_bt'], 3)} / {fmt(e['pct_per_h_all'], 3)} | {'; '.join(extra)} |")
    print()
    print("baseline absolute:", json.dumps(result["baseline_abs_mw"]))
    print("invalid:", json.dumps(result["invalid_windows"]))


if __name__ == "__main__":
    if sys.argv[1] == "window":
        print(json.dumps(window(*sys.argv[2:5])))
    elif sys.argv[1] == "reanalyze":
        reanalyze()
    else:
        summary()
