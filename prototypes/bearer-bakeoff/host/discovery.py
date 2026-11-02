#!/usr/bin/env python3
"""Host orchestration for the ticket-13 discovery PROTOTYPE (stdlib only; reuses bakeoff.py helpers).

  python3 host/discovery.py install NAME...
  python3 host/discovery.py launch RUN NAME key=value...     # one device, adds run/commit/tag
  python3 host/discovery.py fetch RUN NAME...
  python3 host/discovery.py stop NAME...
  python3 host/discovery.py clock NAME...                     # device-minus-host clock offsets
  python3 host/discovery.py screen NAME on|off|home
  python3 host/discovery.py battery NAME unplug|reset
  python3 host/discovery.py caps NAME...                      # Android controller capabilities

Results land in host/results/<run>/<device>.jsonl with host events in host/results/<run>/host.jsonl.
"""

import argparse
import json
import subprocess
import sys
import time

import bakeoff
from bakeoff import DEVICES, RESULTS, adb, fetch, log, records, run

# Extra Android phones for capability and reception checks (D3); Xiaomi/Redmi/POCO may refuse installs.
EXTRA_ANDROID = {
    "zflip4": ("flip4", 36), "moto30": ("moto30", 34), "nova9": ("nova9", 31),
    "nothing1": ("lab-wifi:5555", 35), "pixel4a": ("lab-wifi:5555", 33), "nokiax20": ("lab-wifi:5555", 34),
    "pocof1": ("pocof1", 29), "op6": ("lab-wifi:5555", 30), "minote3": ("minote3", 28),
    "nord2": ("lab-wifi:5555", 33), "gx6": ("gx6", 33), "pocom5": ("lab-wifi:5555", 34),
    "rmx3263": ("lab-wifi:5555", 30), "p30lite": ("p30lite", 29), "cph2251": ("lab-wifi:5555", 33),
    "rmx3710": ("rmx3710", 35), "pocom4": ("lab-wifi:5555", 33), "redmi12": ("lab-wifi:5555", 33),
    "blackview": ("blackview", 29), "cph2135": ("lab-wifi:5555", 31),
}
for _name, (_serial, _api) in EXTRA_ANDROID.items():
    DEVICES.setdefault(_name, {"platform": "android", "serial": _serial, "api": _api})

TAGS = {"nothing2": "noth2", "nothing1": "noth1", "ip12mini": "ip12m", "blackview": "blackv"}


def tag(name):
    return TAGS.get(name, name[:6])


def host_event(run_id, event, **fields):
    out = RESULTS / run_id
    out.mkdir(parents=True, exist_ok=True)
    with open(out / "host.jsonl", "a") as handle:
        handle.write(json.dumps({"t": int(time.time() * 1000), "event": event, **fields}) + "\n")


def install(name):
    bakeoff.install(name)
    device = DEVICES[name]
    if device["platform"] == "android" and device["api"] >= 33:
        adb(device, "shell", "pm", "grant", bakeoff.ANDROID_PACKAGE, "android.permission.POST_NOTIFICATIONS", check=False, quiet=True)


def launch(run_id, name, args):
    full = [f"run={run_id}", f"commit={bakeoff.git_commit()}", f"tag={tag(name)}", *args]
    host_event(run_id, "launch", device=name, args=full)
    bakeoff.launch(name, full)


def stop(name, run_id=None):
    bakeoff.stop(name)
    if DEVICES[name]["platform"] == "android":
        # force-stop does not always tear down a foreground service's BLE state on every vendor; make it explicit.
        adb(DEVICES[name], "shell", "am", "stopservice", "-n", f"{bakeoff.ANDROID_PACKAGE}/.DiscoveryService", check=False, quiet=True)
    if run_id:
        host_event(run_id, "stop", device=name)


def pull(run_id, name):
    destination = RESULTS / run_id / f"{name}.jsonl"
    ok = fetch(name, run_id, destination)
    log(f"fetch {name}: {'ok' if ok else 'FAILED'} ({len(records(destination)) if ok else 0} records)")
    return ok


def clock_offset(name):
    """Device clock minus host clock in ms (Android: date +%s%N midpoint; iOS: idevicedate, 1 s resolution)."""
    device = DEVICES[name]
    if device["platform"] == "android":
        best = None
        for _ in range(3):
            t0 = time.time()
            text = adb(device, "shell", "date", "+%s.%N", quiet=True, check=False).stdout.strip()
            t1 = time.time()
            try:
                value = float(text)
            except ValueError:
                return {"device": name, "error": text}
            if best is None or (t1 - t0) < best[1]:
                best = ((value - (t0 + t1) / 2) * 1000, t1 - t0)
        return {"device": name, "offset_ms": round(best[0]), "rtt_ms": round(best[1] * 1000), "method": "adb date +%s.%N"}
    # idevicedate has 1 s resolution: poll until the device second ticks; the tick lies between the two calls.
    samples = []
    deadline = time.time() + 3
    while time.time() < deadline:
        t0 = time.time()
        text = subprocess.run(["idevicedate", "-u", device["udid"]], capture_output=True, text=True, timeout=30).stdout.strip()
        t1 = time.time()
        try:
            parts = text.split()
            value = time.mktime(time.strptime(" ".join(parts[:4] + parts[5:]), "%a %b %d %H:%M:%S %Y"))
        except (ValueError, IndexError):
            return {"device": name, "error": text}
        if samples and value > samples[-1][2]:
            lo, hi = samples[-1][0], t1
            return {"device": name, "offset_ms": round((value - (lo + hi) / 2) * 1000), "rtt_ms": round((hi - lo) * 1000),
                    "method": "idevicedate second tick"}
        samples.append((t0, t1, value))
    return {"device": name, "error": "no tick", "raw": text}


def screen(name, state):
    device = DEVICES[name]
    if state == "off":
        adb(device, "shell", "input", "keyevent", "KEYCODE_SLEEP", quiet=True)
    elif state == "home":
        adb(device, "shell", "input", "keyevent", "KEYCODE_HOME", quiet=True)
    else:
        adb(device, "shell", "input", "keyevent", "KEYCODE_WAKEUP", quiet=True)
        adb(device, "shell", "wm", "dismiss-keyguard", check=False, quiet=True)


def battery(name, state):
    adb(DEVICES[name], "shell", "dumpsys", "battery", "unplug" if state == "unplug" else "reset", quiet=True)


def caps(names, run_id):
    for name in names:
        stop(name)
        launch(run_id, name, ["disc=caps"])
    time.sleep(8)
    for name in names:
        pull(run_id, name)
        for r in records(RESULTS / run_id / f"{name}.jsonl"):
            if r.get("test") == "disc_start":
                print(f"  {name:10} {r['reporter']['model']:34} {r['reporter']['os']:22} {json.dumps(r.get('caps'))}")
        stop(name)


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("install"); p.add_argument("names", nargs="+")
    p = sub.add_parser("launch"); p.add_argument("run"); p.add_argument("name"); p.add_argument("args", nargs="*")
    p = sub.add_parser("fetch"); p.add_argument("run"); p.add_argument("names", nargs="+")
    p = sub.add_parser("stop"); p.add_argument("names", nargs="+"); p.add_argument("--run")
    p = sub.add_parser("clock"); p.add_argument("names", nargs="+"); p.add_argument("--run")
    p = sub.add_parser("screen"); p.add_argument("name"); p.add_argument("state", choices=["on", "off", "home"]); p.add_argument("--run")
    p = sub.add_parser("battery"); p.add_argument("name"); p.add_argument("state", choices=["unplug", "reset"]); p.add_argument("--run")
    p = sub.add_parser("caps"); p.add_argument("names", nargs="+"); p.add_argument("--run", default="caps")
    p = sub.add_parser("event"); p.add_argument("run"); p.add_argument("event"); p.add_argument("fields", nargs="*")
    a = parser.parse_args()
    if a.cmd == "install":
        failures = []
        for name in a.names:
            try:
                install(name)
            except Exception as error:  # keep installing the rest; report at the end
                failures.append(name)
                log(f"install {name} FAILED: {str(error).splitlines()[0]} {str(error)[-200:]}")
        if failures:
            sys.exit(f"install failed: {failures}")
    elif a.cmd == "launch":
        launch(a.run, a.name, a.args)
    elif a.cmd == "fetch":
        for name in a.names:
            pull(a.run, name)
    elif a.cmd == "stop":
        for name in a.names:
            stop(name, a.run)
    elif a.cmd == "clock":
        for name in a.names:
            result = clock_offset(name)
            print(json.dumps(result))
            if a.run:
                host_event(a.run, "clock", **result)
    elif a.cmd == "screen":
        screen(a.name, a.state)
        if a.run:
            host_event(a.run, "screen", device=a.name, state=a.state)
    elif a.cmd == "battery":
        battery(a.name, a.state)
        if a.run:
            host_event(a.run, "battery", device=a.name, state=a.state)
    elif a.cmd == "caps":
        caps(a.names, a.run)
    elif a.cmd == "event":
        host_event(a.run, a.event, **dict(f.split("=", 1) for f in a.fields))


if __name__ == "__main__":
    main()
