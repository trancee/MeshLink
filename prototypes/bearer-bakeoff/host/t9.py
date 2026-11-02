#!/usr/bin/env python3
"""T9 (PROTOTYPE): Android 17 scan allowance/throttling on Pixel 6.

One filtered (MESH) BALANCED foreground-service scan without app restarts for `--on` minutes with the screen on, then
`--off` minutes with the screen off and the battery unplugged. A spare phone advertises layout E the whole time so the
scan has a steady target. Every `--poll` seconds the host snapshots our app's entry in `dumpsys bluetooth_manager`
(LE Scanner Map) and writes the parsed fields to `t9-dumpsys.jsonl`; the raw snapshots go to `dumpsys/`.

  python3 host/t9.py --run t9-20261102 [--scanner pixel6] [--beacon nova9] [--on 60] [--off 60] [--poll 60]
"""
import argparse
import json
import re
import time

import discovery
from bakeoff import ANDROID_PACKAGE, DEVICES, adb, log
from discovery import RESULTS, battery, host_event, launch, pull, screen, stop


def snapshot(name):
    text = adb(DEVICES[name], "shell", "dumpsys", "bluetooth_manager", quiet=True, check=False).stdout
    match = re.search(rf"\[\d+/\d+\] {re.escape(ANDROID_PACKAGE)}:\n(.*?)(?=\n  \[\d+/\d+\] |\n\S)", text, re.S)
    return match.group(1) if match else ""


def parse(section):
    out = {"found": bool(section)}
    ongoing = section.split("Ongoing", 1)[1] if "Ongoing" in section else ""
    modes = re.findall(r"^\s+(OPPORTUNISTIC|LOW_POWER|BALANCED|LOW_LATENCY|AMBIENT_DISCOVERY|SCREEN_OFF\S*)\s+\S+\s+\S+", ongoing, re.M)
    out["ongoing_modes"] = modes
    out["ongoing_header"] = [line.strip() for line in ongoing.splitlines() if "Info:" in line or "Importance" in line or "Elapsed" in line]
    results = re.search(r"ALL_MATCHES\s+(\d+)\s+(\d+)\s+(\d+)", section)
    if results:
        out["results_screen_off"], out["results_screen_on"], out["results_total"] = map(int, results.groups())
    durations = re.search(r"DURATION\s+(.*)", section)
    if durations:
        out["mode_durations_ms"] = [int(v) for v in re.findall(r"\((\d+)ms\)", durations.group(1))]
    suspend = re.search(r"-{5,}.*\n\s+(\d+)\s+(\d+)\s+.*?\((\d+)ms\)\s+.*?\((\d+)ms\)", section)
    if suspend:
        out["started"], out["stopped"], out["active_ms"], out["suspend_ms"] = map(int, suspend.groups())
    return out


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--run", required=True)
    p.add_argument("--scanner", default="pixel6")
    p.add_argument("--beacon", default="nova9")
    p.add_argument("--on", type=int, default=60)
    p.add_argument("--off", type=int, default=60)
    p.add_argument("--poll", type=int, default=60)
    a = p.parse_args()
    total = (a.on + a.off) * 60
    out_dir = RESULTS / a.run
    (out_dir / "dumpsys").mkdir(parents=True, exist_ok=True)
    for name in (a.scanner, a.beacon):
        stop(name, a.run)
        host_event(a.run, "clock", **discovery.clock_offset(name))
    screen(a.scanner, "on")
    launch(a.run, a.beacon, ["disc=adv", "adv=set", f"duration={total + 300}"])
    launch(a.run, a.scanner, ["disc=scan", "filter=mesh", "scan_mode=balanced", f"rescan_s={total + 600}", f"duration={total + 120}"])
    start = time.time()
    phase = "on"
    index = 0
    try:
        while time.time() - start < total + 60:
            if phase == "on" and time.time() - start >= a.on * 60:
                battery(a.scanner, "unplug")
                screen(a.scanner, "off")
                phase = "off"
                host_event(a.run, "phase", phase="screen_off_unplugged")
            section = snapshot(a.scanner)
            (out_dir / "dumpsys" / f"{index:04d}.txt").write_text(section)
            record = {"t": int(time.time() * 1000), "phase": phase, "elapsed_s": round(time.time() - start), **parse(section)}
            with open(out_dir / "t9-dumpsys.jsonl", "a") as handle:
                handle.write(json.dumps(record) + "\n")
            log(f"t9 {index} {phase} {record.get('ongoing_modes')} results={record.get('results_total')}")
            index += 1
            time.sleep(a.poll)
    finally:
        battery(a.scanner, "reset")
        screen(a.scanner, "on")
        host_event(a.run, "phase", phase="end")
        for name in (a.scanner, a.beacon):
            pull(a.run, name)
            stop(name, a.run)


if __name__ == "__main__":
    main()
