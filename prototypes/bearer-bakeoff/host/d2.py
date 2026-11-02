#!/usr/bin/env python3
"""D2 / T3 / T11 orchestration for the ticket-13 discovery PROTOTYPE (see prototypes/discovery/README.md).

  python3 host/d2.py ios-cells RUN [--adv ip15,se2016] [--states fg,bg,lock] [--cell 300]
  python3 host/d2.py android-cells RUN [--adv pixel6:set,s10e:legacy] [--states fg,off] [--cell 300]
  python3 host/d2.py t11 RUN [--cell 300]
  python3 host/d2.py t3 RUN [--adv ip15] [--scanners ipx,se2020,ip12mini] [--trials 5] [--trial-s 40]

Every cell writes host events `cell_start` / `cell_end`; the analysis (`analyze_discovery.py d2`) reads them.
"""

import argparse
import time

import discovery
from bakeoff import DEVICES, log
from discovery import host_event, launch, pull, stop

LONG = "duration=7200"


def android_scanners(run_id, specs):
    """specs: (name, args, screen_off). Screen-off scanners start after delay_s, once the screen is off."""
    for name, args, screen_off in specs:
        stop(name)
        discovery.screen(name, "on")
        launch(run_id, name, ["disc=scan", LONG, *(["delay_s=8"] if screen_off else []), *args])
    time.sleep(3)
    for name, _, screen_off in specs:
        if screen_off:
            discovery.battery(name, "unplug")
            discovery.screen(name, "off")
            host_event(run_id, "screen", device=name, state="off", battery="unplug")
    time.sleep(8)


def restore_android(run_id, specs):
    for name, _, screen_off in specs:
        if screen_off:
            discovery.battery(name, "reset")
            discovery.screen(name, "on")
            host_event(run_id, "screen", device=name, state="on", battery="reset")


def ios_scanners(run_id, specs):
    for name, args in specs:
        stop(name)
        launch(run_id, name, ["disc=scan", LONG, *args])


def clocks(run_id, names):
    for name in names:
        result = discovery.clock_offset(name)
        host_event(run_id, "clock", **result)


def cell(run_id, label, advertiser, adv_args, cell_s, ios_specs, before=None, after=None):
    ios_scanners(run_id, ios_specs)
    time.sleep(8)
    stop(advertiser)
    host_event(run_id, "cell_start", cell=label, advertiser=advertiser)
    log(f"cell {label}: {advertiser} {adv_args}")
    launch(run_id, advertiser, ["disc=adv", f"duration={cell_s + 60}", *adv_args])
    if before:
        before()
    time.sleep(cell_s)
    host_event(run_id, "cell_end", cell=label, advertiser=advertiser)
    if after:
        after()
    stop(advertiser, run_id)


def fetch_all(run_id, names):
    for name in names:
        pull(run_id, name)


def ios_cells(a):
    advertisers = a.adv.split(",")
    states = a.states.split(",")
    android = [
        ("nothing2", ["filter=none", "scan_mode=low_latency", "rescan_s=240"], False),
        ("zflip4", ["filter=none", "scan_mode=low_latency", "scan_type=passive", "rescan_s=240"], False),
        ("moto30", ["filter=service", "scan_mode=balanced"], True),
        ("xcover4", [f"filter={a.ovf_filter}", "scan_mode=balanced"], True),
    ]
    ios = [("ipx", ["filter=service"]), ("se2020", ["filter=service", "bg=1"]), ("ip12mini", ["filter=service", "bg=1"])]
    clocks(a.run, [n for n, _, _ in android] + [n for n, _ in ios] + advertisers)
    android_scanners(a.run, android)
    try:
        for advertiser in advertisers:
            for state in states:
                extra = {"fg": [], "bg": ["bg=1"], "lock": ["bg=1", "lock=1"]}[state]
                cell(a.run, f"{advertiser}-{state}", advertiser, extra, a.cell, ios)
    finally:
        restore_android(a.run, android)
        for name in [n for n, _ in ios] + [n for n, _, _ in android] + advertisers:
            stop(name, a.run)
        fetch_all(a.run, [n for n, _ in ios] + [n for n, _, _ in android] + advertisers)


def android_cells(a):
    advertisers = [spec.split(":") for spec in a.adv.split(",")]
    states = a.states.split(",")
    android = [
        ("nothing2", ["filter=none", "scan_mode=low_latency", "rescan_s=240"], False),
        ("moto30", ["filter=service", "scan_mode=balanced"], True),
        ("xcover4", ["filter=service", "scan_mode=balanced"], True),
    ]
    ios = [("ip15", ["filter=service"]), ("ip12mini", ["filter=service"]), ("se2016", ["filter=service", "bg=1"]),
           ("ipx", ["filter=service", "bg=1"])]
    names = [n for n, _ in advertisers]
    clocks(a.run, [n for n, _, _ in android] + [n for n, _ in ios] + names)
    android_scanners(a.run, android)
    try:
        for name, api in advertisers:
            for state in states:
                if state == "fg":
                    discovery.screen(name, "on")
                    cell(a.run, f"{name}-fg", name, [f"adv={api}"], a.cell, ios)
                else:
                    def off(n=name):
                        time.sleep(2)
                        discovery.battery(n, "unplug")
                        discovery.screen(n, "off")
                        host_event(a.run, "screen", device=n, state="off", battery="unplug")

                    def on(n=name):
                        discovery.battery(n, "reset")
                        discovery.screen(n, "on")
                        host_event(a.run, "screen", device=n, state="on", battery="reset")

                    discovery.screen(name, "on")
                    cell(a.run, f"{name}-off", name, [f"adv={api}", "delay_s=8"], a.cell, ios, before=off, after=on)
    finally:
        restore_android(a.run, android)
        for name in [n for n, _ in ios] + [n for n, _, _ in android] + names:
            stop(name, a.run)
        fetch_all(a.run, [n for n, _ in ios] + [n for n, _, _ in android] + names)


def t11(a):
    """Background iOS scanners restart their scan every 10 s (driven by a 'ticker' advertiser that changes address)."""
    android = [("nothing2", ["filter=none", "scan_mode=low_latency", "rescan_s=240"], False)]
    ios = [("se2016", ["filter=service", "bg=1", "rescan_s=10"]), ("ipx", ["filter=service", "bg=1", "rescan_s=10"]),
           ("ip12mini", ["filter=service", "bg=1", "rescan_s=10"]), ("se2020", ["filter=service", "bg=1"]),
           ("ip15", ["filter=service", "bg=1", "rescan_s=10", "dups=0"])]
    clocks(a.run, ["nothing2", "pixel6", "zflip4"] + [n for n, _ in ios])
    android_scanners(a.run, android)
    try:
        stop("zflip4")
        launch(a.run, "zflip4", ["disc=adv", "adv=set", f"duration={a.cell + 120}", "restart_every=10", "restart_kind=new_set",
                                 "restart_off=1"])
        cell(a.run, "t11", "pixel6", ["adv=set"], a.cell, ios)
    finally:
        for name in [n for n, _ in ios] + ["nothing2", "zflip4", "pixel6"]:
            stop(name, a.run)
        fetch_all(a.run, [n for n, _ in ios] + ["nothing2", "zflip4", "pixel6"])


def t3(a):
    """Overflow discovery: advertiser in the background; each trial is a fresh scanner process."""
    scanners = a.scanners.split(",")
    cases = {"fg-uuid": ["filter=service"], "fg-nil": ["filter=none"], "bg-uuid": ["filter=service", "bg=1"]}
    clocks(a.run, scanners + [a.adv, "nothing2"])
    android = [("nothing2", ["filter=none", "scan_mode=low_latency", "rescan_s=240"], False)]
    android_scanners(a.run, android)
    for name in scanners:
        stop(name)
    stop(a.adv)
    launch(a.run, a.adv, ["disc=adv", "bg=1", "duration=7200"] + (["lock=1"] if a.lock else []))
    host_event(a.run, "t3_adv_start", advertiser=a.adv)
    time.sleep(15)
    try:
        for trial in range(a.trials):
            for index, (case, args) in enumerate(cases.items()):
                for offset, name in enumerate(scanners):
                    rotated = list(cases.items())[(index + offset) % len(cases)]
                    stop(name)
                    host_event(a.run, "t3_trial", device=name, trial=trial, case=rotated[0])
                    launch(a.run, name, ["disc=scan", f"duration={a.trial_s}", f"case={rotated[0]}", f"trial={trial}", *rotated[1]])
                time.sleep(a.trial_s)
                for name in scanners:
                    stop(name)
                time.sleep(2)
    finally:
        for name in scanners + [a.adv, "nothing2"]:
            stop(name, a.run)
        fetch_all(a.run, scanners + [a.adv, "nothing2"])


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("ios-cells"); p.add_argument("run"); p.add_argument("--adv", default="ip15,se2016")
    p.add_argument("--states", default="fg,bg,lock"); p.add_argument("--cell", type=int, default=300)
    p.add_argument("--ovf-filter", default="apple")
    p = sub.add_parser("android-cells"); p.add_argument("run"); p.add_argument("--adv", default="pixel6:set,s10e:legacy")
    p.add_argument("--states", default="fg,off"); p.add_argument("--cell", type=int, default=300)
    p = sub.add_parser("t11"); p.add_argument("run"); p.add_argument("--cell", type=int, default=300)
    p = sub.add_parser("t3"); p.add_argument("run"); p.add_argument("--adv", default="ip15")
    p.add_argument("--scanners", default="ipx,se2020,ip12mini"); p.add_argument("--trials", type=int, default=7)
    p.add_argument("--trial-s", type=int, default=40); p.add_argument("--lock", action="store_true")
    a = parser.parse_args()
    {"ios-cells": ios_cells, "android-cells": android_cells, "t11": t11, "t3": t3}[a.cmd](a)


if __name__ == "__main__":
    main()
