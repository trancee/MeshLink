#!/usr/bin/env python3
"""T4 (PROTOTYPE): does a Live Activity give a background iOS 26+ app foreground Core Bluetooth privileges?

One iPhone (default ip12mini, iOS 27). Each cell is a fresh launch; `live=1` starts a Live Activity right before the
app suspends itself (after its CBManager exists). A launch without `live=1` ends any leftover activity.

Scanner phase: Pixel 6 advertises layout E; the iPhone scans with duplicates allowed, `log=all`.
Advertiser phase: the iPhone advertises layout E in the background; Pixel 6 (passive) and Nothing 2 (active) scan
unfiltered with `log=all`.

  python3 host/t4.py RUN [--phone ip12mini] [--scan-s 180] [--adv-s 120]
"""
import argparse
import time

import discovery
from bakeoff import log
from discovery import host_event, launch, pull, stop

SCAN_CELLS = [
    ("s-nil-fg", ["filter=none"]),
    ("s-nil-bg", ["filter=none", "bg=1"]),
    ("s-nil-bg-live", ["filter=none", "bg=1", "live=1"]),
    ("s-mesh-bg", ["filter=mesh", "bg=1"]),
    ("s-mesh-bg-live", ["filter=mesh", "bg=1", "live=1"]),
]
ADV_CELLS = [
    ("a-bg", ["bg=1"]),
    ("a-bg-live", ["bg=1", "live=1"]),
    ("a-bg", ["bg=1"]),
    ("a-bg-live", ["bg=1", "live=1"]),
]


def cell(run_id, phone, case, args, seconds):
    host_event(run_id, "t4_case", device=phone, case=case)
    log(f"t4 {phone} {case}")
    launch(run_id, phone, [f"duration={seconds + 30}", f"case={case}", *args])
    time.sleep(seconds)
    stop(phone)
    time.sleep(3)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("run")
    p.add_argument("--phone", default="ip12mini")
    p.add_argument("--scan-s", type=int, default=180)
    p.add_argument("--adv-s", type=int, default=120)
    a = p.parse_args()
    passive, active = "pixel6", "nothing2"
    names = [a.phone, passive, active]
    for name in names:
        stop(name)
        host_event(a.run, "clock", **discovery.clock_offset(name))
    try:
        discovery.screen(passive, "on")
        discovery.screen(active, "on")
        launch(a.run, passive, ["disc=adv", "adv=set", f"duration={len(SCAN_CELLS) * (a.scan_s + 20) + 60}"])
        time.sleep(5)
        for case, args in SCAN_CELLS:
            cell(a.run, a.phone, case, ["disc=scan", "log=all", *args], a.scan_s)
        stop(passive)
        pull(a.run, passive)
        total = len(ADV_CELLS) * (a.adv_s + 35) + 60
        launch(a.run, passive, ["disc=scan", "filter=none", "log=all", "scan_type=passive", "rescan_s=240", f"duration={total}"])
        launch(a.run, active, ["disc=scan", "filter=none", "log=all", "rescan_s=240", f"duration={total}"])
        time.sleep(5)
        for case, args in ADV_CELLS:
            cell(a.run, a.phone, case, ["disc=adv", *args], a.adv_s)
            host_event(a.run, "t4_case", device=a.phone, case="post-stop")
            time.sleep(25)
        # Ends the last Live Activity.
        launch(a.run, a.phone, ["disc=caps", "case=cleanup"])
        time.sleep(5)
    finally:
        for name in names:
            stop(name, a.run)
        for name in names:
            pull(a.run, name)


if __name__ == "__main__":
    main()
