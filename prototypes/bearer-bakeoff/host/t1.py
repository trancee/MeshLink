#!/usr/bin/env python3
"""T1 / E1 (PROTOTYPE): iOS advertisement byte layout as received by Android scanners.

Per iPhone, one case at a time (fresh app launch, `--case-s` seconds each). Pixel 6 scans passive (ADV PDUs only) and
Nothing 2 active (ADV + scan response merged), both unfiltered with `log=all`, for the whole run.

  python3 host/t1.py RUN [--phones se2016,ipx,ip15,se2020,ip12mini] [--case-s 30]
"""
import argparse
import time

import discovery
from bakeoff import log
from discovery import host_event, launch, pull, stop

CASES = {
    "e": [],                                   # layout E, no name
    "e-name8": ["name=ABCDEFGH"],              # + 8-char local name (24 + 10 = 34 B > 28 B)
    "e-name10": ["name=ABCDEFGHIJ"],           # + 10-char local name
    "e-uuid3": ["uuids=2"],                    # + a second 128-bit UUID (24 + 16 = 40 B)
    "e-bg": ["bg=1"],                          # background, no name (stale-name check after the name cases)
    "e-again": [],                             # foreground, no name again
}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("run")
    p.add_argument("--phones", default="se2016,ipx,ip15,se2020,ip12mini")
    p.add_argument("--case-s", type=int, default=30)
    p.add_argument("--passive", default="pixel6")
    p.add_argument("--active", default="nothing2")
    a = p.parse_args()
    phones = a.phones.split(",")
    scanners = [a.passive, a.active]
    total = len(phones) * len(CASES) * (a.case_s + 12) + 120
    for name in scanners + phones:
        stop(name)
        host_event(a.run, "clock", **discovery.clock_offset(name))
    try:
        for name in scanners:
            discovery.screen(name, "on")
        launch(a.run, a.passive, ["disc=scan", "filter=none", "log=all", "scan_type=passive", "rescan_s=240", f"duration={total}"])
        launch(a.run, a.active, ["disc=scan", "filter=none", "log=all", "rescan_s=240", f"duration={total}"])
        time.sleep(5)
        for phone in phones:
            for case, args in CASES.items():
                host_event(a.run, "t1_case", device=phone, case=case)
                log(f"t1 {phone} {case}")
                launch(a.run, phone, ["disc=adv", f"duration={a.case_s}", f"case={case}", *args])
                time.sleep(a.case_s + 6)
                stop(phone)
                time.sleep(2)
    finally:
        for name in scanners + phones:
            stop(name, a.run)
        for name in scanners + phones:
            pull(a.run, name)


if __name__ == "__main__":
    main()
