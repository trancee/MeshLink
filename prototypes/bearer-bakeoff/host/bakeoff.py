#!/usr/bin/env python3
"""Host orchestrator for the MeshLink bearer bake-off PROTOTYPE (stdlib only).

Builds, installs, launches the bake-off app with role/test arguments on Android (remote adb server) and iOS
(devicectl for iOS 17+, libimobiledevice for iOS 15/16), waits for the `done` record and pulls JSONL results into
host/results/<run_id>/<device>.jsonl.
"""

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RESULTS = ROOT / "host" / "results"
APK = ROOT / "androidApp/build/outputs/apk/debug/androidApp-debug.apk"
JAVA_HOME = os.environ.get("BAKEOFF_JAVA_HOME", "/opt/homebrew/opt/openjdk@21")
ADB_SERVER = os.environ.get("ADB_SERVER_SOCKET", "tcp:adb-server:5037")
ANDROID_PACKAGE = "ch.trancee.lab.host"

# Free personal teams cannot share one App ID, so team apple-team-2 signs a suffixed bundle ID.
TEAMS = {
    "apple-team-1": "ch.trancee.lab.host",
    "apple-team-2": "ch.trancee.lab.host.b",
}

DEVICES = {
    # Android: adb transport (Pixel 6 also shows an mDNS duplicate; use the IP transport).
    "pixel6": {"platform": "android", "serial": "pixel6-wifi:5555", "api": 37},
    "nothing2": {"platform": "android", "serial": "nothing2", "api": 36},
    "s10e": {"platform": "android", "serial": "s10e", "api": 31},
    "redmi11": {"platform": "android", "serial": "lab-wifi:5555", "api": 33},
    "oppo": {"platform": "android", "serial": "oppo-wifi:5555", "api": 34},
    "mi8": {"platform": "android", "serial": "mi8", "api": 29},
    "p10lite": {"platform": "android", "serial": "p10lite", "api": 26},
    "xcover4": {"platform": "android", "serial": "xcover4", "api": 28},
    # iOS: tool = devicectl (iOS 17+) or imd (libimobiledevice, iOS 15/16).
    "ip15": {"platform": "ios", "udid": "ip15", "team": "apple-team-1", "tool": "devicectl"},
    "se2020": {"platform": "ios", "udid": "se2020", "team": "apple-team-1", "tool": "devicectl"},
    "ip12mini": {"platform": "ios", "udid": "ip12mini", "team": "apple-team-1", "tool": "devicectl", "grant_attach": True},
    "se2016": {"platform": "ios", "udid": "se2016", "team": "apple-team-2", "tool": "imd"},
    "ipx": {"platform": "ios", "udid": "ipx", "team": "apple-team-2", "tool": "imd"},
}

_background = []


def log(message):
    print(f"[{time.strftime('%H:%M:%S')}] {message}", flush=True)


def run(cmd, timeout=300, check=True, env=None, quiet=False):
    if not quiet:
        log("$ " + " ".join(str(c) for c in cmd))
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, env=env, stdin=subprocess.DEVNULL)
    if check and result.returncode != 0:
        raise RuntimeError(f"command failed ({result.returncode}): {' '.join(map(str, cmd))}\n{result.stdout}\n{result.stderr}")
    return result


def adb(device, *args, timeout=120, check=True, quiet=False):
    env = dict(os.environ, ADB_SERVER_SOCKET=ADB_SERVER)
    return run(["adb", "-s", device["serial"], *args], timeout=timeout, check=check, env=env, quiet=quiet)


def git_commit():
    return run(["git", "-C", str(ROOT), "rev-parse", "--short", "HEAD"], quiet=True).stdout.strip()


def ios_app_path(team):
    return ROOT / f"iosApp/build/dd-{team}/Build/Products/Release-iphoneos/BakeoffHost.app"


def bundle_id(device):
    return TEAMS[device["team"]] if device["platform"] == "ios" else ANDROID_PACKAGE


# ---------------------------------------------------------------- build

def build(platforms):
    env = dict(os.environ, JAVA_HOME=JAVA_HOME)
    if "android" in platforms:
        run([str(ROOT / "gradlew"), "-p", str(ROOT), ":androidApp:assembleDebug", "-q"], timeout=1800, env=env)
    if "ios" in platforms:
        run([str(ROOT / "gradlew"), "-p", str(ROOT), ":shared:linkReleaseFrameworkIosArm64", "-q"], timeout=1800, env=env)
        run(["xcodegen", "generate", "-q", "--spec", str(ROOT / "iosApp/project.yml")], timeout=120)
        for team, bundle in TEAMS.items():
            run([
                "xcodebuild", "-project", str(ROOT / "iosApp/BakeoffHost.xcodeproj"), "-scheme", "BakeoffHost",
                "-configuration", "Release", "-destination", "generic/platform=iOS",
                "-derivedDataPath", str(ROOT / f"iosApp/build/dd-{team}"), f"DEVELOPMENT_TEAM={team}",
                f"LAB_BUNDLE_ID={bundle}", "-allowProvisioningUpdates",
                "-allowProvisioningDeviceRegistration", "-quiet", "build-for-testing",
            ], timeout=1800)


# ---------------------------------------------------------------- install

def install(name):
    device = DEVICES[name]
    if device["platform"] == "android":
        adb(device, "install", "-r", "-g", str(APK), timeout=300)
        perms = (["BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE"] if device["api"] >= 31 else ["ACCESS_FINE_LOCATION"])
        for perm in perms:
            adb(device, "shell", "pm", "grant", ANDROID_PACKAGE, f"android.permission.{perm}", check=False, quiet=True)
    elif device["tool"] == "devicectl":
        run(["xcrun", "devicectl", "device", "install", "app", "--device", device["udid"], str(ios_app_path(device["team"]))], timeout=600)
    else:
        run(["ideviceinstaller", "-u", device["udid"], "install", str(ios_app_path(device["team"]))], timeout=600)


# ---------------------------------------------------------------- launch / stop

def launch(name, args):
    device = DEVICES[name]
    if device["platform"] == "android":
        adb(device, "shell", "am", "start", "-S", "-n", f"{ANDROID_PACKAGE}/.MainActivity", "--es", "args", ",".join(args))
    elif device["tool"] == "devicectl":
        run(["xcrun", "devicectl", "device", "process", "launch", "--device", device["udid"], "--terminate-existing",
             bundle_id(device), *args], timeout=120)
    else:
        # idevicedebug keeps a debugserver session open while the app runs; it is our own child, stopped by PID.
        cmd = ["idevicedebug", "-u", device["udid"], "run", bundle_id(device), *args]
        log("$ " + " ".join(cmd) + " &")
        process = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, stdin=subprocess.DEVNULL)
        _background.append(process)
        device["debug_process"] = process


def stop(name):
    device = DEVICES[name]
    if device["platform"] == "android":
        adb(device, "shell", "am", "force-stop", ANDROID_PACKAGE, check=False, quiet=True)
    elif device["tool"] == "imd":
        process = device.pop("debug_process", None)
        if process and process.poll() is None:
            process.kill()
            process.wait(timeout=10)
    # devicectl: the next launch uses --terminate-existing; peripherals also stop after `duration`.


# ---------------------------------------------------------------- results

def fetch(name, run_id, destination):
    device = DEVICES[name]
    destination.parent.mkdir(parents=True, exist_ok=True)
    if device["platform"] == "android":
        result = adb(device, "exec-out", "run-as", ANDROID_PACKAGE, "cat", f"files/results/{run_id}.jsonl", check=False, quiet=True)
        if result.returncode != 0 or "No such file" in result.stdout:
            return False
        destination.write_text(result.stdout)
        return True
    if device["tool"] == "devicectl":
        result = run(["xcrun", "devicectl", "device", "copy", "from", "--device", device["udid"], "--domain-type", "appDataContainer",
                      "--domain-identifier", bundle_id(device), "--source", f"Documents/results/{run_id}.jsonl",
                      "--destination", str(destination)], check=False, quiet=True, timeout=120)
        return result.returncode == 0 and destination.exists()
    script = f"get Documents/results/{run_id}.jsonl {destination}\nquit\n"
    result = subprocess.run(["afcclient", "-u", device["udid"], "--container", bundle_id(device)], input=script,
                            capture_output=True, text=True, timeout=60)
    return result.returncode == 0 and destination.exists() and destination.stat().st_size > 0


def records(path):
    if not path.exists():
        return []
    out = []
    for line in path.read_text().splitlines():
        line = line.strip()
        if line.startswith("{"):
            try:
                out.append(json.loads(line))
            except json.JSONDecodeError:
                pass
    return out


def wait_done(name, run_id, destination, timeout_s, poll_s=10):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        time.sleep(poll_s)
        if fetch(name, run_id, destination) and any(r.get("test") == "done" for r in records(destination)):
            return True
    return False


def summarize(path):
    for r in records(path):
        test = r.get("test")
        if test in ("peripheral_event",):
            continue
        timings = r.get("timings") or {}
        if "samples_ms" in timings:
            timings = {k: v for k, v in timings.items() if k != "samples_ms"}
        print(f"  {test:34} {str(r.get('bearer')):28} {r.get('outcome'):6} mtu={r.get('mtu')} phy={r.get('phy')} "
              f"{json.dumps(timings)} {r.get('error') or ''}")


# ---------------------------------------------------------------- pair run

def pair_run(central, peripheral, run_id, central_args, peripheral_args, timeout_s, settle_s):
    commit = git_commit()
    base = [f"run={run_id}", f"commit={commit}"]
    out_dir = RESULTS / run_id
    out_dir.mkdir(parents=True, exist_ok=True)
    stop(central)
    stop(peripheral)
    launch(peripheral, ["role=peripheral", *base, f"duration={timeout_s + 60}", *peripheral_args])
    time.sleep(settle_s)
    launch(central, ["role=central", *base, *central_args])
    central_file = out_dir / f"{central}-central.jsonl"
    finished = wait_done(central, run_id, central_file, timeout_s)
    peripheral_file = out_dir / f"{peripheral}-peripheral.jsonl"
    fetch(peripheral, run_id, peripheral_file)
    stop(central)
    stop(peripheral)
    log(f"run {run_id}: central finished={finished}; results in {out_dir}")
    summarize(central_file)
    return finished


def grant_ios(name):
    """Runs the XCUITest runner that accepts the Bluetooth alert by exact label; logs every label it saw."""
    device = DEVICES[name]
    if device["platform"] != "ios":
        raise SystemExit(f"{name} is not an iPhone")
    products = ROOT / f"iosApp/build/dd-{device['team']}/Build/Products"
    xctestrun = sorted(products.glob("*.xctestrun"))
    if not xctestrun:
        raise SystemExit(f"no .xctestrun in {products}; run `build ios` first")
    out_dir = RESULTS / "grant"
    out_dir.mkdir(parents=True, exist_ok=True)
    log_file = out_dir / f"{name}-{time.strftime('%Y%m%dT%H%M%S')}.log"
    cmd = ["xcodebuild", "test-without-building", "-xctestrun", str(xctestrun[0]),
           "-destination", f"id={device['udid']}", "-allowProvisioningUpdates"]
    env = dict(os.environ)
    if device.get("grant_attach"):
        # XCUIApplication.launch() fails on this iOS with Xcode 16.4; launch the app ourselves, the runner attaches.
        env["TEST_RUNNER_GRANT_ATTACH"] = "1"
        stop(name)
        launch(name, ["grant=1"])
        time.sleep(3)
    log("$ " + " ".join(cmd))
    with open(log_file, "w") as handle:
        result = subprocess.run(cmd, stdout=handle, stderr=subprocess.STDOUT, timeout=600, env=env)
    text = log_file.read_text(errors="replace")
    for line in text.splitlines():
        if "BAKEOFF-GRANT" in line or "error:" in line or "Test Case" in line:
            print("  " + line.strip()[:300])
    ok = result.returncode == 0 and "** TEST EXECUTE SUCCEEDED **" in text
    log(f"grant-ios {name}: {'passed' if ok else 'FAILED (exit %d)' % result.returncode}; log {log_file}")
    return ok


def solo_run(name, run_id, args, timeout_s):
    commit = git_commit()
    out_dir = RESULTS / run_id
    stop(name)
    launch(name, [f"run={run_id}", f"commit={commit}", *args])
    destination = out_dir / f"{name}.jsonl"
    finished = wait_done(name, run_id, destination, timeout_s)
    stop(name)
    log(f"run {run_id} on {name}: finished={finished}")
    summarize(destination)
    return finished


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    b = sub.add_parser("build")
    b.add_argument("platforms", nargs="*", default=["android", "ios"])
    i = sub.add_parser("install")
    i.add_argument("devices", nargs="+", choices=sorted(DEVICES))
    p = sub.add_parser("pair", help="peripheral on one device, central on another")
    p.add_argument("--central", required=True, choices=sorted(DEVICES))
    p.add_argument("--peripheral", required=True, choices=sorted(DEVICES))
    p.add_argument("--run", required=True)
    p.add_argument("--timeout", type=int, default=900)
    p.add_argument("--settle", type=int, default=6, help="seconds between peripheral and central launch")
    p.add_argument("--peripheral-arg", action="append", default=[])
    p.add_argument("central_args", nargs="*", help="key=value tokens, e.g. n=5 bearers=gatt+coc phy=2m plan=suite")
    s = sub.add_parser("solo", help="launch one device with explicit role args and collect its results")
    s.add_argument("device", choices=sorted(DEVICES))
    s.add_argument("--run", required=True)
    s.add_argument("--timeout", type=int, default=900)
    s.add_argument("args", nargs="+")
    g = sub.add_parser("grant-ios", help="accept the iOS Bluetooth permission alert via the XCUITest runner")
    g.add_argument("devices", nargs="+", choices=[n for n, d in DEVICES.items() if d["platform"] == "ios"])
    f = sub.add_parser("fetch")
    f.add_argument("device", choices=sorted(DEVICES))
    f.add_argument("--run", required=True)
    args = parser.parse_args()
    try:
        if args.command == "build":
            build(args.platforms)
        elif args.command == "install":
            for name in args.devices:
                install(name)
        elif args.command == "pair":
            ok = pair_run(args.central, args.peripheral, args.run, args.central_args, args.peripheral_arg, args.timeout, args.settle)
            sys.exit(0 if ok else 2)
        elif args.command == "solo":
            sys.exit(0 if solo_run(args.device, args.run, args.args, args.timeout) else 2)
        elif args.command == "grant-ios":
            results = {name: grant_ios(name) for name in args.devices}
            sys.exit(0 if all(results.values()) else 2)
        elif args.command == "fetch":
            destination = RESULTS / args.run / f"{args.device}.jsonl"
            ok = fetch(args.device, args.run, destination)
            print(destination if ok else "not found")
    finally:
        for process in _background:
            if process.poll() is None:
                process.kill()


if __name__ == "__main__":
    main()
