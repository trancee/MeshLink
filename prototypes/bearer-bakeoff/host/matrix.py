#!/usr/bin/env python3
"""Resumable batch runner for the bake-off matrix (Python 3 stdlib only).

  python3 host/matrix.py list <phase>          # show planned runs and which are already done
  python3 host/matrix.py run <phase> [--only SUBSTR]

A run counts as done when its central (or hub/solo) JSONL contains a `done` record; done runs are skipped, so
re-running a phase resumes it. Progress is appended to host/results/matrix.log.
"""
import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

HOST = Path(__file__).resolve().parent
RESULTS = HOST / "results"

ANDROID_10_PLUS = ["pixel6", "nothing2", "s10e", "redmi11", "oppo", "mi8"]
IPHONES = ["ip15", "se2020", "ip12mini", "se2016", "ipx"]
SUITE = ["n=5", "bearers=gatt+coc", "plan=suite"]
CONNECT = ["n=50", "bearers=gatt+coc", "plan=connect"]


def pair(central, peripheral, run_id, args, timeout):
    return {"kind": "pair", "central": central, "peripheral": peripheral, "run": run_id, "args": args, "timeout": timeout}


def both(a, b, prefix, args, timeout):
    return [pair(a, b, f"{prefix}-{a}-{b}", args, timeout), pair(b, a, f"{prefix}-{b}-{a}", args, timeout)]


def phases():
    plan = {}
    plan["smoke-ios"] = [
        *both("pixel6", "ip15", "i1", [*SUITE, "phy=2m"], 600),
        *both("pixel6", "ip12mini", "i1", [*SUITE, "phy=2m"], 600),
        *both("ip15", "se2020", "i1", [*SUITE, "phy=2m"], 600),
    ]
    step1 = []
    for android in ANDROID_10_PLUS:
        for iphone in IPHONES:
            step1 += both(android, iphone, "m1", [*CONNECT, "phy=2m"], 1500)
    for a, b in [("ip15", "se2020"), ("ip12mini", "ipx"), ("se2016", "ip15")]:
        step1 += both(a, b, "m1", [*CONNECT, "phy=2m"], 1500)
    plan["step1-connect"] = step1
    step2 = []
    for android in ANDROID_10_PLUS:
        for iphone in IPHONES:
            step2 += both(android, iphone, "m2", [*SUITE, "phy=2m"], 600)
    for a, b in [("ip15", "se2020"), ("ip12mini", "ipx"), ("se2016", "ip15")]:
        step2 += both(a, b, "m2", [*SUITE, "phy=2m"], 600)
    plan["step2-suite"] = step2
    step3 = []
    for a, b in [("pixel6", "s10e"), ("pixel6", "nothing2"), ("nothing2", "redmi11"), ("s10e", "oppo")]:
        for phy in ("1m", "2m"):
            step3 += both(a, b, f"m3-{phy}-suite", [*SUITE, f"phy={phy}"], 600)
            step3 += both(a, b, f"m3-{phy}-conn", ["n=20", "bearers=gatt+coc", "plan=connect", f"phy={phy}"], 900)
    plan["step3-phy"] = step3
    step5 = []
    for legacy in ("p10lite", "xcover4"):
        for other in ("pixel6", "ip15"):
            step5 += both(other, legacy, "m5-suite", ["n=5", "bearers=gatt", "plan=suite", "phy=2m"], 600)
            step5 += both(other, legacy, "m5-conn", ["n=20", "bearers=gatt", "plan=connect", "phy=2m"], 900)
    plan["step5-legacy"] = step5
    return plan


def variants(entry):
    """The plain run id plus timestamped executions (`<run>-t<ddHHMMSS>`); device files are append-only per run id."""
    base = RESULTS / entry["run"]
    return [base, *sorted(RESULTS.glob(entry["run"] + "-t[0-9]*"))]


def is_done(entry):
    for run_dir in variants(entry):
        for path in run_dir.glob("*.jsonl"):
            if "-peripheral" in path.name:
                continue
            for line in path.read_text(errors="replace").splitlines():
                if '"test":"done"' in line:
                    return True
    return False


def fresh_run_id(entry):
    return f"{entry['run']}-t{time.strftime('%d%H%M%S')}"


def log(message):
    line = f"[{time.strftime('%Y-%m-%d %H:%M:%S')}] {message}"
    print(line, flush=True)
    RESULTS.mkdir(parents=True, exist_ok=True)
    with open(RESULTS / "matrix.log", "a") as handle:
        handle.write(line + "\n")


def execute(entry):
    entry = dict(entry, run=fresh_run_id(entry))
    cmd = [sys.executable, str(HOST / "bakeoff.py"), "pair", "--central", entry["central"], "--peripheral", entry["peripheral"],
           "--run", entry["run"], "--timeout", str(entry["timeout"]), *entry["args"]]
    started = time.time()
    output = subprocess.run(cmd, capture_output=True, text=True, timeout=entry["timeout"] + 400)
    (RESULTS / entry["run"]).mkdir(parents=True, exist_ok=True)
    (RESULTS / entry["run"] / "orchestrator.log").write_text(output.stdout + output.stderr)
    log(f"{entry['run']}: exit={output.returncode} done={is_done(entry)} in {time.time() - started:.0f}s")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("action", choices=["list", "run"])
    parser.add_argument("phase", choices=sorted(phases()))
    parser.add_argument("--only", help="only runs whose id contains this substring")
    args = parser.parse_args()
    entries = [e for e in phases()[args.phase] if not args.only or args.only in e["run"]]
    if args.action == "list":
        for entry in entries:
            print(f"{'done ' if is_done(entry) else 'todo '} {entry['run']} {' '.join(entry['args'])}")
        return
    todo = [e for e in entries if not is_done(e)]
    log(f"phase {args.phase}: {len(todo)}/{len(entries)} runs to do")
    subprocess.run([sys.executable, str(HOST / "bakeoff.py"), "stop"], capture_output=True, timeout=600)
    for index, entry in enumerate(todo, 1):
        log(f"start {index}/{len(todo)} {entry['run']}")
        try:
            execute(entry)
        except subprocess.TimeoutExpired:
            log(f"{entry['run']}: orchestrator timeout")


if __name__ == "__main__":
    main()
