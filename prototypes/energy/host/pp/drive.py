#!/usr/bin/env python3
"""Ticket 35 PROTOTYPE: drive Power Profiler phases on the 12 mini: warm-up A, then 4 blocks of CONDS.

Usage: PP_DEVICE=<udid> drive.py SEED [CONDS] [WARM_S]; session 2: 3535 ABC 300, session 3: 3636 ACD 900."""
import json, os, random, subprocess, sys, time
U, B = os.environ["PP_DEVICE"], "ch.trancee.lab.host"  # devicectl UDID or name, kept out of git
SEED = int(sys.argv[1]) if len(sys.argv) > 1 else 3535
CONDS = sys.argv[2] if len(sys.argv) > 2 else "ABC"
PHASE_S, WARM_S = 240, int(sys.argv[3]) if len(sys.argv) > 3 else 300
ARGS = {
    "A": ["disc=idle"],
    "B": ["disc=both", "filter=mesh", "scan_on_s=4", "scan_off_s=4"],
    "C": ["disc=both", "filter=mesh", "bg=1"],
    "D": ["disc=idle", "bg=1"],
}
rng = random.Random(SEED)
plan = [("warm", "A")] + [(f"b{i}", c) for i in range(1, 5) for c in rng.sample(CONDS, len(CONDS))]
out = open(f"{sys.path[0]}/../../results/pp/phases-{SEED}.jsonl", "a")

def launch(cond, rid):
    cmd = ["xcrun", "devicectl", "device", "process", "launch", "--device", U, "--terminate-existing", B,
           *ARGS[cond], f"run={rid}", "tag=135", "duration=330"]
    for attempt in range(3):
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
        if r.returncode == 0:
            return attempt
        time.sleep(5)
    raise RuntimeError(r.stdout[-400:] + r.stderr[-400:])

print(json.dumps({"seed": SEED, "plan": plan}), file=out, flush=True)
for block, cond in plan:
    rid = f"pp3-{SEED}-{block}-{cond}"
    t0 = time.time()
    retries = launch(cond, rid)
    rec = {"block": block, "cond": cond, "run": rid, "launch_t": t0, "launched_t": time.time(), "retries": retries}
    print(json.dumps(rec), file=out, flush=True); print(rec, flush=True)
    time.sleep((WARM_S if block == "warm" else PHASE_S) - (time.time() - t0))
launch("A", f"pp3-{SEED}-end-A")
print(json.dumps({"end_t": time.time()}), file=out, flush=True); print("done", flush=True)
