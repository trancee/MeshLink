#!/usr/bin/env python3
"""Energy prototype runner (ticket 23). Throwaway lab code.

Subcommands:
  trial                  60 s Wi-Fi-off validation window (adv+scan) on the Pixel 6
  window <id> [--rep N]  one scenario window (id from SCENARIOS or "base")
  run --rep N            one repetition: seeded shuffle, baselines interleaved, invalid windows rerun once
  throttle               60 s functional check of literal 2-of-4 / 2-of-6 scan schedules (no energy)
  crosscheck <phone>     cross-check windows (base, scan_low, idle_gatt_3) with batterystats BT controller time
  overnight [--hours H]  8 h screen-off medium-duty run on the Pixel 6
  ios-sample <name>      poll AppleSmartBattery over Wi-Fi (libimobiledevice) and log JSONL
"""
import argparse
import json
import os
import plistlib
import random
import re
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
ENERGY = HERE.parent
RESULTS = ENERGY / "results"
RAW_WINDOWS = RESULTS / "windows"
BIG_RAW = Path("copilot-session/files/energy-raw")
PFV = "/tmp/pfv/bin/python"
PKG = "ch.trancee.lab.host"
os.environ.setdefault("ADB_SERVER_SOCKET", "tcp:adb-server:5037")

DUT = "pixel6-wifi:5555"
TAG = 4242
PHONES = {
    "pixel6": DUT, "nothing2": "nothing2", "flip4": "flip4", "gx6": "gx6", "cph2359": "cph2359",
    "nova9": "nova9", "mi8": "mi8", "moto30": "moto30", "s10e": "s10e", "a16": "lab-wifi:5555",
    "op6": "lab-wifi:5555", "nord2": "lab-wifi:5555", "rmx3710": "rmx3710", "nothing1": "lab-wifi:5555",
    "cph2251": "lab-wifi:5555", "pocom5": "lab-wifi:5555", "nokiax20": "lab-wifi:5555",
}
MODEL = {"nothing2": "A065", "flip4": "SM-F721B", "gx6": "E940", "cph2359": "CPH2359", "nova9": "NAM-LX9"}
LINK_PEERS = ["nothing2", "flip4", "gx6", "cph2359", "nova9"]
ADVERTISER = "mi8"

SCAN = {"low": [], "med": ["scan_on_s=4", "scan_off_s=4"], "high": ["scan_on_s=4", "scan_off_s=8"]}


def scenarios():
    s = {"base": {"energy": "idle"}}
    s["adv_fast"] = {"energy": "adv", "args": ["adv=fast"]}
    s["adv_slow"] = {"energy": "adv", "args": ["adv=slow"]}
    for d in SCAN:
        s[f"scan_{d}"] = {"energy": "scan", "args": SCAN[d]}
    s["scanmode_bal"] = {"energy": "scan", "args": ["scan_mode=balanced"]}
    s["scanmode_lp"] = {"energy": "scan", "args": ["scan_mode=low_power"]}
    for d, adv in (("low", "fast"), ("med", "fast"), ("high", "slow")):
        s[f"comb_{d}"] = {"energy": "adv+scan", "args": SCAN[d] + [f"adv={adv}"]}
    for bearer in ("gatt", "coc"):
        for n in (1, 3, 5):
            s[f"idle_{bearer}_{n}"] = {"energy": "links", "peers": LINK_PEERS[:n], "args": [f"bearer={bearer}", "prio=none", "phy=2m"]}
    for bearer in ("gatt", "coc"):
        for phy in ("1m", "2m"):
            for size, msg in (("4k", 4096), ("128k", 131072)):
                s[f"bulk_{bearer}_{phy}_{size}"] = {"energy": "bulk", "peers": ["nothing2"],
                                                     "args": [f"bearer={bearer}", f"phy={phy}", "prio=high", f"msg={msg}",
                                                              # A5: bound the Pixel's write-without-response queue.
                                                              *(["write_ack_every=256"] if bearer == "gatt" else [])]}
    for rate in (1, 5, 20):
        s[f"relay_{rate}"] = {"energy": "relay", "peers": ["nothing2", "flip4"],
                              "args": ["bearer=coc", "prio=none", "phy=2m", f"rate={rate}", "size=200"]}
    return s


SCENARIOS = scenarios()


def log(msg):
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}", flush=True)


def adb(serial, *args, timeout=120, check=False, input=None):
    cmd = ["adb", "-s", serial, *args]
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, input=input)
    except subprocess.TimeoutExpired:
        return subprocess.CompletedProcess(cmd, 124, "", "timeout")
    if check and r.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)} -> {r.returncode}: {r.stdout}{r.stderr}")
    return r


def sh(serial, command, timeout=120):
    return adb(serial, "shell", command, timeout=timeout).stdout


def online(serial):
    r = adb(serial, "shell", "echo ok", timeout=15)
    return r.returncode == 0 and "ok" in r.stdout


def reconnect(serial, deadline):
    while time.time() < deadline:
        subprocess.run(["adb", "connect", serial], capture_output=True, text=True, timeout=30)
        if online(serial):
            return True
        time.sleep(15)
    return False


def git_commit():
    return subprocess.run(["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True, cwd=ENERGY).stdout.strip()


# ---------------------------------------------------------------- peers

def launch_activity(name, args):
    adb(PHONES[name], "shell", "am", "start", "-S", "-n", f"{PKG}/.MainActivity", "--es", "args", ",".join(args), timeout=60)


def stop_app(name):
    adb(PHONES[name], "shell", "am", "force-stop", PKG, timeout=60)


def start_peers(rid, peers, duration_s, tag=TAG):
    for p in peers:
        launch_activity(p, ["role=peripheral", f"run={rid}", f"tag={tag}", f"duration={duration_s}"])


def start_advertiser(rid, duration_s):
    launch_activity(ADVERTISER, ["disc=adv", "layout=E", f"run={rid}", "tag=mi8", f"duration={duration_s}"])


# ---------------------------------------------------------------- on-device window script

PF_CFG = """buffers {{ size_kb: 16384 fill_policy: RING_BUFFER }}
data_sources {{ config {{ name: "android.power" android_power_config {{ battery_poll_ms: {poll} collect_power_rails: true battery_counters: BATTERY_COUNTER_CURRENT battery_counters: BATTERY_COUNTER_CHARGE battery_counters: BATTERY_COUNTER_VOLTAGE battery_counters: BATTERY_COUNTER_CAPACITY_PERCENT }} }} }}
write_into_file: true
file_write_period_ms: 60000
duration_ms: {duration_ms}
"""

SCRIPT = r"""#!/system/bin/sh
D=/data/local/tmp/energy
R={rid}
L=$D/$R.log
T=/data/misc/perfetto-traces/$R.pftrace
S=$(date +%s)
echo "script_start $S wifi_on=$(settings get global wifi_on) $(dumpsys power | grep -m1 mWakefulness=)" >> $L
{pre}
rm -f $T
cat $D/$R.cfg | perfetto --txt -c - -o $T > /dev/null 2>> $L &
PF=$!
echo "perfetto_pid $PF" >> $L
# The app takes its wake lock first; screen-off sleeps in this script would otherwise stretch (CPU suspend).
am start-foreground-service -n {pkg}/.EnergyService --es args "{args}" >> $L 2>&1
svc wifi disable
input keyevent KEYCODE_SLEEP
echo "wifi_off $(date +%s) wifi_on=$(settings get global wifi_on) $(dumpsys power | grep -m1 mWakefulness=)" >> $L
while [ $(( $(date +%s) - S )) -lt {timeout} ]; do
  sleep 10
  if run-as {pkg} grep -q '"test":"done"' files/results/$R.jsonl 2>/dev/null; then break; fi
done
echo "app_done $(date +%s) waited=$(( $(date +%s) - S )) wifi_on=$(settings get global wifi_on) $(dumpsys power | grep -m1 mWakefulness=)" >> $L
# Wi-Fi first: everything after this may run slowly if the phone suspends.
svc wifi enable
dumpsys bluetooth_manager > $D/$R.btm.txt 2>&1
{post}
kill -TERM $PF
j=0
while kill -0 $PF 2>/dev/null && [ $j -lt 30 ]; do sleep 1; j=$((j+1)); done
echo "perfetto_stopped $(date +%s) after=$j" >> $L
am force-stop {pkg}
echo "wifi_back $(date +%s) wifi_on=$(settings get global wifi_on)" >> $L
touch $D/$R.done
"""


def window(rid, energy, extra, *, peers=(), window_s=600, settle_s=20, poll_ms=1000, pre="", post="", out_dir=None,
           advertiser=True):
    """Runs one Wi-Fi-off window on the Pixel; returns (trace, jsonl, script log)."""
    out_dir = out_dir or RAW_WINDOWS
    out_dir.mkdir(parents=True, exist_ok=True)
    BIG_RAW.mkdir(parents=True, exist_ok=True)
    setup_s = 240 if peers else 0
    timeout = settle_s + setup_s + window_s + 180
    if not online(DUT) and not reconnect(DUT, time.time() + 120):
        raise RuntimeError("DUT offline before window")
    sh(DUT, f"am force-stop {PKG}; mkdir -p /data/local/tmp/energy")
    for p in peers:
        stop_app(p)
    if peers:
        start_peers(rid, peers, timeout + 300)
    if advertiser:
        start_advertiser(rid, timeout + 300)
    time.sleep(6)
    args = ",".join([f"energy={energy}", f"run={rid}", f"window_s={window_s}", f"settle_s={settle_s}", f"tag={TAG}",
                     *([f"want={'+'.join(MODEL[p] for p in peers)}", f"n={len(peers)}"] if peers else []), *extra])
    cfg = PF_CFG.format(poll=poll_ms, duration_ms=(timeout + 240) * 1000)
    script = SCRIPT.format(rid=rid, pkg=PKG, args=args, timeout=timeout, pre=pre, post=post)
    adb(DUT, "shell", f"cat > /data/local/tmp/energy/{rid}.cfg", input=cfg, check=True)
    adb(DUT, "shell", f"cat > /data/local/tmp/energy/{rid}.sh", input=script, check=True)
    started = time.time()
    adb(DUT, "shell", f"nohup setsid sh /data/local/tmp/energy/{rid}.sh > /dev/null 2>&1 &", timeout=30)
    log(f"window {rid}: started ({energy} {' '.join(extra)}), expect <= {timeout} s")
    time.sleep(min(timeout, settle_s + window_s + (60 if peers else 10)))
    deadline = started + timeout + 600
    done = False
    while time.time() < deadline:
        if reconnect(DUT, min(deadline, time.time() + 60)):
            if "yes" in sh(DUT, f"[ -f /data/local/tmp/energy/{rid}.done ] && echo yes"):
                done = True
                break
        time.sleep(20)
    if not done:
        raise RuntimeError(f"window {rid}: DUT not back or script not done (adb connect {DUT})")
    for p in peers:
        stop_app(p)
    if advertiser:
        stop_app(ADVERTISER)
    trace = BIG_RAW / f"{rid}.pftrace"
    adb(DUT, "pull", f"/data/misc/perfetto-traces/{rid}.pftrace", str(trace), timeout=300)
    jsonl = out_dir / f"{rid}.jsonl"
    jsonl.write_text(adb(DUT, "exec-out", "run-as", PKG, "cat", f"files/results/{rid}.jsonl").stdout)
    (out_dir / f"{rid}.log").write_text(sh(DUT, f"cat /data/local/tmp/energy/{rid}.log"))
    btm = sh(DUT, f"cat /data/local/tmp/energy/{rid}.btm.txt", timeout=60)
    (BIG_RAW / f"{rid}.btm.txt").write_text(btm)
    sh(DUT, f"rm -f /data/misc/perfetto-traces/{rid}.pftrace /data/local/tmp/energy/{rid}.*")
    return trace, jsonl, out_dir / f"{rid}.log"


def probe(sid, window_s, extra):
    """Functional check on the Pixel with Wi-Fi on (no Perfetto, not a measurement)."""
    sc = SCENARIOS[sid]
    peers = sc.get("peers", [])
    rid = f"probe-{sid}-{int(time.time()) % 100000}"
    sh(DUT, f"am force-stop {PKG}")
    for p in peers:
        stop_app(p)
    if peers:
        start_peers(rid, peers, window_s + 600)
    time.sleep(6)
    args = ",".join([f"energy={sc['energy']}", f"run={rid}", f"window_s={window_s}", "settle_s=10", f"tag={TAG}",
                     *([f"want={'+'.join(MODEL[p] for p in peers)}", f"n={len(peers)}"] if peers else []), *sc.get("args", []), *extra])
    sh(DUT, f"am start-foreground-service -n {PKG}/.EnergyService --es args '{args}'")
    t0 = time.time()
    text = ""
    while time.time() - t0 < window_s + 400:
        time.sleep(10)
        text = adb(DUT, "exec-out", "run-as", PKG, "cat", f"files/results/{rid}.jsonl").stdout
        if '"test":"done"' in text:
            break
    for p in peers:
        stop_app(p)
    (RESULTS / "probe").mkdir(exist_ok=True)
    (RESULTS / "probe" / f"{rid}.jsonl").write_text(text)
    for line in text.splitlines():
        d = json.loads(line)
        if d.get("test") in ("link_up", "window_end", "ack", "done", "link_fail"):
            d.pop("timings", None)
            print(json.dumps(d)[:600])


def analyze(trace, jsonl, log_path):
    r = subprocess.run([PFV, str(HERE / "analyze.py"), "window", str(trace), str(jsonl), str(log_path)],
                       capture_output=True, text=True, timeout=900)
    if r.returncode != 0:
        return {"valid": False, "invalid": [f"analyze failed: {r.stderr[-500:]}"]}
    return json.loads(r.stdout)


def run_window(sid, rep, attempt=0, out_dir=None):
    sc = SCENARIOS[sid]
    rid = f"e{rep}-{sid}" + (f"-r{attempt}" if attempt else "") + f"-{int(time.time()) % 100000}"
    try:
        trace, jsonl, lp = window(rid, sc["energy"], sc.get("args", []), peers=sc.get("peers", ()), out_dir=out_dir)
        result = analyze(trace, jsonl, lp)
    except Exception as e:  # noqa: BLE001 - lab runner: record and move on
        log(f"window {rid} error: {e}")
        result = {"valid": False, "invalid": [str(e)[:400]]}
    result.update({"rid": rid, "scenario": sid, "rep": rep, "attempt": attempt, "commit": git_commit(),
                   "device": "Google Pixel 6", "date": datetime.now(timezone.utc).isoformat(timespec="seconds")})
    with (RESULTS / "windows.jsonl").open("a") as f:
        f.write(json.dumps(result) + "\n")
    p = result.get("power_mw", {})
    log(f"window {rid}: valid={result.get('valid')} {result.get('invalid', [])} wifi.bt={p.get('wifi.bt')} all={p.get('all')} mW "
        f"traffic={ {k: result.get(k) for k in ('kbps_acked', 'forwarded_in_window', 'links')} }")
    return result


def run_rep(rep, only=None):
    ids = [s for s in SCENARIOS if s != "base"]
    if only:
        ids = [s for s in ids if s in only]
    random.Random(rep).shuffle(ids)
    log(f"rep {rep}: order {ids}")
    redo = []

    def base():
        for attempt in range(3):
            if run_window("base", rep, attempt).get("valid"):
                return

    base()
    for i, sid in enumerate(ids, 1):
        if not run_window(sid, rep).get("valid"):
            redo.append(sid)
        if i % 3 == 0 or i == len(ids):
            base()
    if redo:
        log(f"rep {rep}: rerunning invalid {redo}")
        for i, sid in enumerate(redo, 1):
            run_window(sid, rep, attempt=1)
            if i % 3 == 0 or i == len(redo):
                base()


# ---------------------------------------------------------------- functional scan-throttle check (Wi-Fi on, no energy)

def throttle():
    out = RESULTS / "throttle"
    out.mkdir(parents=True, exist_ok=True)
    start_advertiser("throttle", 600)
    for name, on, off in (("2of4", 2, 2), ("2of6", 2, 4), ("4of8", 4, 4), ("4of12", 4, 8)):
        rid = f"thr-{name}-{int(time.time()) % 100000}"
        sh(DUT, f"am force-stop {PKG}")
        time.sleep(31)  # fresh 30 s startScan budget
        adb(DUT, "shell", "am", "start-foreground-service", "-n", f"{PKG}/.EnergyService", "--es", "args",
            f"energy=scan,run={rid},window_s=60,settle_s=2,scan_on_s={on},scan_off_s={off}")
        time.sleep(75)
        text = adb(DUT, "exec-out", "run-as", PKG, "cat", f"files/results/{rid}.jsonl").stdout
        (out / f"{rid}.jsonl").write_text(text)
        recs = [json.loads(l) for l in text.splitlines() if l.strip()]
        bursts = [r for r in recs if r["test"] == "burst"]
        summ = next((r for r in recs if r["test"] == "scan_summary"), {})
        log(f"throttle {name}: bursts={summ.get('bursts')} empty={summ.get('empty_bursts')} results={summ.get('results')} "
            f"first={[(b['on_ms'], b['results']) for b in bursts[:10]]}")
    stop_app(ADVERTISER)
    sh(DUT, f"am force-stop {PKG}")


# ---------------------------------------------------------------- cross-check (batterystats)

MODEL_X = {"op6": "ONEPLUS A6003", "nord2": "DN2103", "rmx3710": "RMX3710", "nothing1": "A063", "cph2251": "CPH2251",
           "pocom5": "M5", "nokiax20": "X20", **MODEL}


def crosscheck(name, peers, only=None):
    serial = PHONES[name]
    out = RESULTS / "crosscheck"
    out.mkdir(parents=True, exist_ok=True)
    BIG_RAW.mkdir(parents=True, exist_ok=True)
    plan = [("base", "idle", [], []), ("scan_low", "scan", [], []),
            ("idle_gatt_3", "links", ["bearer=gatt", "prio=none", "phy=2m"], peers)]
    tag = TAG + 1 + list(PHONES).index(name)
    for sid, energy, extra, prs in plan:
        if only and sid not in only:
            continue
        rid = f"x-{name}-{sid}-{int(time.time()) % 100000}"
        adb(serial, "shell", "am", "force-stop", PKG)
        for p in prs:
            stop_app(p)
        start_peers(rid, prs, 1500, tag)
        time.sleep(5)
        want = [f"want={'+'.join(MODEL_X[p] for p in prs)}", f"n={len(prs)}"] if prs else []
        args = ",".join([f"energy={energy}", f"run={rid}", "window_s=600", "settle_s=20", f"tag={tag}", *want, *extra])
        # batterystats only accumulates on battery: pretend unplugged for the window (reset after reading).
        sh(serial, "dumpsys battery unplug; dumpsys batterystats --reset")
        adb(serial, "shell", "am", "start-foreground-service", "-n", f"{PKG}/.EnergyService", "--es", "args", args)
        t0 = time.time()
        text = ""
        if prs:
            # Reset at window_start so connect scans and retries during link setup are not counted.
            while time.time() - t0 < 300:
                time.sleep(2)
                if '"test":"window_start"' in adb(serial, "exec-out", "run-as", PKG, "cat", f"files/results/{rid}.jsonl").stdout:
                    sh(serial, "dumpsys batterystats --reset")
                    break
        while time.time() - t0 < 600 + 20 + (240 if prs else 0) + 240:
            time.sleep(20)
            text = adb(serial, "exec-out", "run-as", PKG, "cat", f"files/results/{rid}.jsonl").stdout
            if '"test":"window_end"' in text or '"test":"done"' in text:
                break
        full = sh(serial, "dumpsys batterystats", timeout=300)
        sh(serial, "dumpsys battery reset")
        text = adb(serial, "exec-out", "run-as", PKG, "cat", f"files/results/{rid}.jsonl").stdout
        lines = [l.strip() for l in full.splitlines() if re.search(r"(?i)bluetooth", l)][:80]
        (out / f"{rid}.jsonl").write_text(text)
        (BIG_RAW / f"{rid}.batterystats.txt").write_text(full)
        (out / f"{rid}.bt-lines.txt").write_text("\n".join(lines) + "\n")
        adb(serial, "shell", "am", "force-stop", PKG)
        for p in prs:
            stop_app(p)
        log(f"crosscheck {rid}: " + " | ".join(l for l in lines if re.search("Idle|Rx|Tx", l))[:600])


# ---------------------------------------------------------------- overnight

def overnight(hours, peers=("nothing2", "flip4"), scanner="nothing1"):
    window_s = int(hours * 3600)
    rid = f"night-{int(time.time()) % 100000}"
    pre = "dumpsys battery unplug\ndumpsys batterystats --reset\necho \"unplugged $(date +%s)\" >> $L"
    post = ("dumpsys batterystats > /data/local/tmp/night-$R.batterystats.txt 2>&1\n"
            "dumpsys deviceidle > /data/local/tmp/night-$R.deviceidle.txt 2>&1\n"
            "dumpsys battery reset\necho \"battery_reset $(date +%s)\" >> $L")
    out = RESULTS / "overnight"
    if scanner:
        launch_activity(scanner, ["disc=scan", "filter=mesh", f"run={rid}", "tag=scan", f"duration={window_s + 1800}"])
    try:
        trace, jsonl, lp = window(rid, "adv+scan+links", ["adv=fast", *SCAN["med"], "bearer=coc", "prio=none", "phy=2m", "wakelock=0"],
                                  peers=list(peers), window_s=window_s, poll_ms=10000, pre=pre, post=post, out_dir=out)
        for kind in ("batterystats", "deviceidle"):
            (BIG_RAW / f"{rid}.{kind}.txt").write_text(sh(DUT, f"cat /data/local/tmp/night-{rid}.{kind}.txt", timeout=300))
            sh(DUT, f"rm -f /data/local/tmp/night-{rid}.{kind}.txt")
        result = analyze(trace, jsonl, lp)
    except Exception as e:  # noqa: BLE001
        result = {"valid": False, "invalid": [str(e)[:400]]}
    if scanner:
        text = adb(PHONES[scanner], "exec-out", "run-as", PKG, "cat", f"files/results/{rid}.jsonl", timeout=300).stdout
        (BIG_RAW / f"{rid}-{scanner}-scanner.jsonl").write_text(text)
        stop_app(scanner)
    result.update({"rid": rid, "scenario": "overnight", "hours": hours, "commit": git_commit(), "device": "Google Pixel 6",
                   "date": datetime.now(timezone.utc).isoformat(timespec="seconds")})
    with (RESULTS / "windows.jsonl").open("a") as f:
        f.write(json.dumps(result) + "\n")
    log(f"overnight {rid}: {json.dumps(result)[:800]}")


# ---------------------------------------------------------------- iOS battery sampler

IOS = {"ip15": "ip15", "se2016": "se2016"}
IOS_KEYS = ("InstantAmperage", "Amperage", "Voltage", "AppleRawCurrentCapacity", "CurrentCapacity", "ExternalConnected",
            "IsCharging", "FullyCharged", "UpdateTime", "Temperature", "AppleRawBatteryVoltage")


def ios_sample(name, seconds, period, out_path):
    udid = IOS[name]
    out_path.parent.mkdir(parents=True, exist_ok=True)
    end = time.time() + seconds
    last = None
    with out_path.open("a") as f:
        while time.time() < end:
            t = time.time()
            rec = {"t": round(t, 3), "device": name}
            try:
                # iOS 17 (iPhone 15): AppleSmartBattery; iOS 15 (SE 2016): empty there, data under AppleARMPMUCharger.
                p = {}
                for entry in ("AppleSmartBattery", "AppleARMPMUCharger"):
                    r = subprocess.run(["idevicediagnostics", "-n", "-u", udid, "ioregentry", entry], capture_output=True, timeout=60)
                    if b"<?xml" in r.stdout:
                        p = plistlib.loads(r.stdout[r.stdout.find(b"<?xml"):]).get("IORegistry", {})
                    if "Voltage" in p:
                        rec["entry"] = entry
                        break
                for k in IOS_KEYS:
                    if k in p:
                        rec[k] = p[k]
                for k in ("InstantAmperage", "Amperage"):
                    if isinstance(rec.get(k), int) and rec[k] >= 2**63:
                        rec[k] -= 2**64
                rec["new_update"] = rec.get("UpdateTime") != last
                last = rec.get("UpdateTime")
            except Exception as e:  # noqa: BLE001
                rec["error"] = str(e)[:300]
            f.write(json.dumps(rec) + "\n")
            f.flush()
            time.sleep(max(0.0, period - (time.time() - t)))
    return out_path


IOS_BUNDLE = {"ip15": "ch.trancee.lab.host", "se2016": "ch.trancee.lab.host.b"}
IOS_SCENARIOS = {
    "adv": ["disc=adv"],
    "scan_low": ["disc=scan", "filter=mesh"],
    "comb_med": ["disc=both", "filter=mesh", "scan_on_s=4", "scan_off_s=4"],
    "idle_gatt_3": None,  # iPhone is the peripheral; three Android centrals hold one link each (iOS caps 3 inbound)
}
IOS_CENTRALS = os.environ.get("IOS_CENTRALS", "nothing2,flip4,gx6").split(",")


def ios_launch(name, args):
    udid = IOS[name]
    if name == "ip15":
        cmd = ["xcrun", "devicectl", "device", "process", "launch", "--device", udid, "--terminate-existing", IOS_BUNDLE[name], *args]
    else:
        cmd = ["idevicedebug", "-u", udid, "--detach", "run", IOS_BUNDLE[name], *args]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    if r.returncode != 0:
        raise RuntimeError(f"iOS launch failed: {' '.join(cmd)}: {r.stdout[-300:]} {r.stderr[-300:]}")


def ios_power(samples):
    """Mean |InstantAmperage| x Voltage (mW) over distinct gauge updates; validity flags."""
    ups = [r for r in samples if "Voltage" in r and r.get("new_update")]
    bad = [r for r in samples if r.get("ExternalConnected") or r.get("IsCharging")]
    errors = [r for r in samples if "error" in r]
    p = [abs(r["InstantAmperage"]) * r["Voltage"] / 1000 for r in ups if isinstance(r.get("InstantAmperage"), int)]
    pa = [abs(r["Amperage"]) * r["Voltage"] / 1000 for r in ups if isinstance(r.get("Amperage"), int)]
    invalid = []
    if bad:
        invalid.append(f"plugged/charging in {len(bad)} samples")
    if len(errors) > 0.1 * max(1, len(samples)):
        invalid.append(f"{len(errors)} sampler errors")
    if len(p) < 20:
        invalid.append(f"only {len(p)} gauge updates")
    return {"power_mw": round(sum(p) / len(p), 2) if p else None, "power_avg_amperage_mw": round(sum(pa) / len(pa), 2) if pa else None,
            "updates": len(p), "samples": len(samples), "signs": sorted({(r.get("InstantAmperage") or 0) > 0 for r in ups}),
            "invalid": invalid, "valid": not invalid}


def ios_window(name, sid, rep, window_s=600, settle_s=45):
    rid = f"i{rep}-{name}-{sid}-{int(time.time()) % 100000}"
    out = RESULTS / "ios"
    out.mkdir(parents=True, exist_ok=True)
    centrals = []
    try:
        if sid == "idle_gatt_3":
            ios_launch(name, ["role=peripheral", f"run={rid}", f"tag={TAG + 100}", f"duration={window_s + settle_s + 400}"])
            time.sleep(8)
            for c in IOS_CENTRALS:
                adb(PHONES[c], "shell", "am", "force-stop", PKG)
                adb(PHONES[c], "shell", "am", "start-foreground-service", "-n", f"{PKG}/.EnergyService", "--es", "args",
                    f"energy=links,run={rid},n=1,want=iPhone,tag={TAG + 100},bearer=gatt,prio=none,phy=2m,settle_s=5,window_s={window_s + settle_s + 60}")
                centrals.append(c)
                time.sleep(3)
            t0 = time.time()
            while time.time() - t0 < 300:
                ups = [c for c in centrals if '"test":"window_start"' in adb(PHONES[c], "exec-out", "run-as", PKG, "cat", f"files/results/{rid}.jsonl").stdout]
                if len(ups) == len(centrals):
                    break
                time.sleep(10)
            else:
                raise RuntimeError(f"iOS links: only {len(ups)}/3 up")
        else:
            args = ["mode=discovery", *(IOS_SCENARIOS.get(sid) or ["disc=idle"]), f"run={rid}", "tag=ios", f"duration={window_s + settle_s + 60}"]
            ios_launch(name, args)
        time.sleep(settle_s)
        path = out / f"{rid}.gauge.jsonl"
        ios_sample(name, window_s, 2, path)
        samples = [json.loads(l) for l in path.read_text().splitlines() if l.strip()]
        result = ios_power(samples)
        import sys
        sys.path.insert(0, str(ENERGY.parent / "bearer-bakeoff" / "host"))
        import bakeoff
        result["app_log"] = bakeoff.fetch(name, rid, out / f"{rid}.app.jsonl")
        for c in centrals:
            text = adb(PHONES[c], "exec-out", "run-as", PKG, "cat", f"files/results/{rid}.jsonl").stdout
            (out / f"{rid}-{c}.jsonl").write_text(text)
            if '"link_lost"' in text:
                result["valid"] = False
                result["invalid"].append(f"link lost ({c})")
    except Exception as e:  # noqa: BLE001
        result = {"valid": False, "invalid": [str(e)[:300]]}
    finally:
        for c in centrals:
            if not (out / f"{rid}-{c}.jsonl").exists():
                (out / f"{rid}-{c}.jsonl").write_text(adb(PHONES[c], "exec-out", "run-as", PKG, "cat", f"files/results/{rid}.jsonl").stdout)
            adb(PHONES[c], "shell", "am", "force-stop", PKG)
    result.update({"rid": rid, "scenario": sid, "rep": rep, "device": name, "platform": "ios",
                   "date": datetime.now(timezone.utc).isoformat(timespec="seconds"), "commit": git_commit()})
    with (RESULTS / "ios-windows.jsonl").open("a") as f:
        f.write(json.dumps(result) + "\n")
    log(f"ios window {rid}: valid={result['valid']} {result['invalid']} power={result.get('power_mw')} mW updates={result.get('updates')}")
    return result


def ios_run(name, rep):
    ids = list(IOS_SCENARIOS)
    random.Random(100 + rep).shuffle(ids)
    log(f"ios rep {rep} on {name}: base + {ids} with a baseline after every 2 windows")
    ios_window(name, "base", rep)
    for i, sid in enumerate(ids, 1):
        ios_window(name, sid, rep)
        if i % 2 == 0 or i == len(ids):
            ios_window(name, "base", rep)


def ios_overnight(name, hours):
    rid = f"inight-{name}-{int(time.time()) % 100000}"
    ios_launch(name, ["mode=discovery", "disc=both", "filter=mesh", "scan_on_s=4", "scan_off_s=4", "bg=1", f"run={rid}", "tag=ios",
                      f"duration={int(hours * 3600) + 600}"])
    path = ios_sample(name, hours * 3600, 20, RESULTS / "ios" / f"{rid}.gauge.jsonl")
    samples = [json.loads(l) for l in path.read_text().splitlines() if l.strip()]
    result = ios_power(samples)
    result.update({"rid": rid, "scenario": "overnight", "device": name, "platform": "ios", "hours": hours,
                   "date": datetime.now(timezone.utc).isoformat(timespec="seconds")})
    with (RESULTS / "ios-windows.jsonl").open("a") as f:
        f.write(json.dumps(result) + "\n")
    log(f"ios overnight {rid}: {json.dumps(result)}")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("trial")
    w = sub.add_parser("window")
    w.add_argument("sid")
    w.add_argument("--rep", type=int, default=0)
    r = sub.add_parser("run")
    r.add_argument("--rep", type=int, required=True)
    r.add_argument("--only", nargs="*")
    sub.add_parser("throttle")
    pr = sub.add_parser("probe")
    pr.add_argument("sid")
    pr.add_argument("--window-s", type=int, default=60)
    pr.add_argument("--extra", nargs="*", default=[])
    x = sub.add_parser("crosscheck")
    x.add_argument("phone")
    x.add_argument("--peers", nargs=3, default=["op6", "nord2", "rmx3710"])
    x.add_argument("--only", nargs="*")
    o = sub.add_parser("overnight")
    o.add_argument("--hours", type=float, default=8)
    i = sub.add_parser("ios-sample")
    i.add_argument("name")
    i.add_argument("--seconds", type=float, default=120)
    i.add_argument("--period", type=float, default=2)
    i.add_argument("--out", type=Path)
    ir = sub.add_parser("ios-run")
    ir.add_argument("name")
    ir.add_argument("--rep", type=int, required=True)
    iw = sub.add_parser("ios-window")
    iw.add_argument("name")
    iw.add_argument("sid")
    iw.add_argument("--rep", type=int, default=0)
    iw.add_argument("--window-s", type=int, default=600)
    io = sub.add_parser("ios-overnight")
    io.add_argument("name")
    io.add_argument("--hours", type=float, default=8)
    a = ap.parse_args()
    RESULTS.mkdir(parents=True, exist_ok=True)
    if a.cmd == "trial":
        rid = f"trial-{int(time.time()) % 100000}"
        trace, jsonl, lp = window(rid, "adv+scan", ["adv=fast", *SCAN["med"]], window_s=60, settle_s=10, out_dir=RESULTS / "trial")
        print(json.dumps(analyze(trace, jsonl, lp), indent=1))
    elif a.cmd == "window":
        print(json.dumps(run_window(a.sid, a.rep), indent=1))
    elif a.cmd == "run":
        run_rep(a.rep, a.only)
    elif a.cmd == "throttle":
        throttle()
    elif a.cmd == "probe":
        probe(a.sid, a.window_s, a.extra)
    elif a.cmd == "crosscheck":
        crosscheck(a.phone, a.peers, a.only)
    elif a.cmd == "overnight":
        overnight(a.hours)
    elif a.cmd == "ios-run":
        ios_run(a.name, a.rep)
    elif a.cmd == "ios-window":
        print(json.dumps(ios_window(a.name, a.sid, a.rep, window_s=a.window_s), indent=1))
    elif a.cmd == "ios-overnight":
        ios_overnight(a.name, a.hours)
    elif a.cmd == "ios-sample":
        print(ios_sample(a.name, a.seconds, a.period, a.out or RESULTS / "ios" / f"{a.name}-sample-{int(time.time())}.jsonl"))


if __name__ == "__main__":
    main()
