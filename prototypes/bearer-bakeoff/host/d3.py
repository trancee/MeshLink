#!/usr/bin/env python3
"""D3 (PROTOTYPE): one extended-capable advertiser cycles legacy / ext1m / ext2m / coded; every other phone scans.

  python3 host/d3.py RUN [--adv nothing1] [--scanners a,b,...] [--step 90] [--reps 2] [--pad 60]
"""
import argparse
import time

import discovery
from bakeoff import DEVICES, log
from discovery import host_event, launch, pull, stop


def main():
    p = argparse.ArgumentParser()
    p.add_argument("run")
    p.add_argument("--adv", default="nothing1")
    p.add_argument("--scanners", required=True)
    p.add_argument("--step", type=int, default=90)
    p.add_argument("--reps", type=int, default=2)
    p.add_argument("--pad", type=int, default=60)
    a = p.parse_args()
    scanners = a.scanners.split(",")
    total = 4 * a.step * a.reps
    for name in scanners + [a.adv]:
        stop(name)
        host_event(a.run, "clock", **discovery.clock_offset(name))
    try:
        for name in scanners:
            if DEVICES[name]["platform"] == "android":
                discovery.screen(name, "on")
                launch(a.run, name, ["disc=scan", "filter=none", "scan_mode=low_latency", f"rescan_s=240", f"duration={total + 90}"])
            else:
                launch(a.run, name, ["disc=scan", "filter=none", f"duration={total + 90}"])
        time.sleep(15)
        discovery.screen(a.adv, "on")
        launch(a.run, a.adv, ["disc=adv", "adv=legacy+ext1m+ext2m+coded", f"step_s={a.step}", f"reps={a.reps}", f"pad={a.pad}",
                              f"duration={total + 30}"])
        log(f"d3 running {total} s")
        time.sleep(total + 40)
    finally:
        for name in scanners + [a.adv]:
            stop(name, a.run)
        for name in scanners + [a.adv]:
            pull(a.run, name)


if __name__ == "__main__":
    main()
