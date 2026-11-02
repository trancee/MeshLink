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
        # --detach: an attached debugserver session sometimes reports a stop (T11) right after launch and
        # idevicedebug then kills the app, leaving it idle/argument-less; detached launches run reliably.
        run(["idevicedebug", "-u", device["udid"], "--detach", "run", bundle_id(device), *args], timeout=120)


def stop(name):
    device = DEVICES[name]
    if device["platform"] == "android":
        adb(device, "shell", "am", "force-stop", ANDROID_PACKAGE, check=False, quiet=True)
    elif device["tool"] == "imd":
        subprocess.run(["idevicedebug", "-u", device["udid"], "kill", bundle_id(device)], capture_output=True, timeout=60)
    elif device["tool"] == "devicectl":
        listing = RESULTS / f".processes-{name}.json"
        RESULTS.mkdir(parents=True, exist_ok=True)
        subprocess.run(["xcrun", "devicectl", "device", "info", "processes", "--device", device["udid"],
                        "--json-output", str(listing), "-q"], capture_output=True, timeout=60)
        try:
            processes = json.loads(listing.read_text())["result"]["runningProcesses"]
        except (OSError, KeyError, json.JSONDecodeError):
            processes = []
        finally:
            listing.unlink(missing_ok=True)
        for process in processes:
            if process.get("executable", "").endswith("/BakeoffHost.app/BakeoffHost"):
                subprocess.run(["xcrun", "devicectl", "device", "process", "terminate", "--device", device["udid"],
                                "--pid", str(process["processIdentifier"]), "-q"], capture_output=True, timeout=60)


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
    # afcclient refuses to overwrite without -f (and still exits 0), so progress polls would freeze the first copy.
    script = f"get -f Documents/results/{run_id}.jsonl {destination}\nquit\n"
    result = subprocess.run(["afcclient", "-u", device["udid"], "--container", bundle_id(device)], input=script,
                            capture_output=True, text=True, timeout=60)
    failed = "Error" in result.stdout or "Error" in result.stderr
    return result.returncode == 0 and not failed and destination.exists() and destination.stat().st_size > 0


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

def write_run_meta(out_dir, run_id, commit, roles, args, extra_args):
    """Host-side evidence metadata: which devices (registry name, serial/UDID), args, commit, date, launch tools."""
    values = dict(a.split("=", 1) for a in args if "=" in a)
    meta = {
        "run_id": run_id, "commit": commit, "date": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "phy": values.get("phy", "default"), "args": list(args), "peripheral_args": list(extra_args),
        "devices": {role: {"name": name, **{k: v for k, v in DEVICES[name].items() if k != "grant_attach"}} for role, name in roles.items()},
        "xcode": subprocess.run(["xcodebuild", "-version"], capture_output=True, text=True).stdout.split("\n")[0],
    }
    (out_dir / "run.json").write_text(json.dumps(meta, indent=2) + "\n")


def pair_run(central, peripheral, run_id, central_args, peripheral_args, timeout_s, settle_s):
    commit = git_commit()
    base = [f"run={run_id}", f"commit={commit}"]
    out_dir = RESULTS / run_id
    out_dir.mkdir(parents=True, exist_ok=True)
    write_run_meta(out_dir, run_id, commit, {"central": central, "peripheral": peripheral}, central_args, peripheral_args)
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


# ---------------------------------------------------------------- multi-device runs (step 4)

def group_run(run_id, servers, server_args, clients, client_args, timeout_s, stagger_s, settle_s=8):
    """Launch `servers` first, then `clients` staggered; wait for every client's `done`, then fetch servers.

    servers/clients are lists of (device, role, file_suffix). All devices share the run id, so runTag matches.
    """
    commit = git_commit()
    base = [f"run={run_id}", f"commit={commit}"]
    out_dir = RESULTS / run_id
    out_dir.mkdir(parents=True, exist_ok=True)
    roles = {f"{suffix}:{name}": name for name, _, suffix in servers + clients}
    write_run_meta(out_dir, run_id, commit, roles, client_args, server_args)
    for name, _, _ in servers + clients:
        stop(name)
    for name, role, _ in servers:
        launch(name, [f"role={role}", *base, *server_args])
    if servers:
        time.sleep(settle_s)
    for name, role, _ in clients:
        launch(name, [f"role={role}", *base, *client_args])
        time.sleep(stagger_s)
    deadline = time.time() + timeout_s
    finished = {}
    for name, _, suffix in clients:
        finished[name] = wait_done(name, run_id, out_dir / f"{name}-{suffix}.jsonl", max(30, deadline - time.time()))
    for name, _, suffix in servers:
        fetch(name, run_id, out_dir / f"{name}-{suffix}.jsonl")
    for name, _, _ in servers + clients:
        try:
            stop(name)
        except Exception as error:  # noqa: BLE001 - best effort
            log(f"stop {name}: {error}")
    log(f"run {run_id}: finished={finished}; results in {out_dir}")
    for name, _, suffix in clients:
        print(f"-- {name}-{suffix}")
        summarize(out_dir / f"{name}-{suffix}.jsonl")
    return all(finished.values())


def hub_run(hub, peers, run_id, args, timeout_s):
    """Central-role max concurrency: `hub` dials every advertising peer and holds the links."""
    return group_run(run_id, [(p, "peripheral", "peripheral") for p in peers], [f"duration={timeout_s + 120}"],
                     [(hub, "hub-central", "hub")], [*args, f"duration={timeout_s - 120}"], timeout_s, 0)


def phub_run(hub, peers, run_id, args, timeout_s, stagger_s=10):
    """Peripheral-role max concurrency: `hub` advertises; every peer connects as a holding central (echo every 2 s)."""
    hold = max(60, timeout_s - 60 - stagger_s * len(peers))
    return group_run(run_id, [(hub, "peripheral", "peripheral")], [f"duration={timeout_s + 120}"],
                     [(p, "hold", "hold") for p in peers], [*args, f"duration={hold}"], timeout_s, stagger_s)


def dual_run(a, b, run_id, args, timeout_s):
    """Crossed-connection probe: both nodes advertise and dial each other at wall-clock-aligned round starts."""
    return group_run(run_id, [], [], [(a, "dual", "dual"), (b, "dual", "dual")], args, timeout_s, 0)


def _survey(name, run_id, seconds, filtered, destination):
    """One scan probe on `name` appended to its `run_id` file; returns once a new `done` record appears."""
    before = sum(1 for r in records(destination) if r.get("test") == "done")
    stop(name)
    launch(name, ["role=central", f"run={run_id}", f"commit={git_commit()}", f"survey={seconds}", f"survey_filter={1 if filtered else 0}"])
    deadline = time.time() + seconds + 90
    while time.time() < deadline:
        time.sleep(5)
        if fetch(name, run_id, destination) and sum(1 for r in records(destination) if r.get("test") == "done") > before:
            return True
    return False


def _hold_health(name, run_id, out_dir):
    path = out_dir / f"{name}-hold.jsonl"
    fetch(name, run_id, path)
    rs = records(path)
    connect = [r for r in rs if r.get("test") == "hold_connect"]
    alive = [r for r in rs if r.get("test") == "hold_alive"]
    end = next((r for r in rs if r.get("test") in ("hold_end", "done") and r.get("outcome") != "ok"), None)
    return {
        "connected": any(r.get("outcome") == "ok" for r in connect),
        "connect_failures": [r.get("error") for r in connect if r.get("outcome") != "ok"],
        "echoes": alive[-1].get("echoes") if alive else 0,
        "last_rtt_ms": (alive[-1].get("timings") or {}).get("last_rtt_ms") if alive else None,
        "ended": bool(end), "end_error": end.get("error") if end else None,
    }


def _sightings(path):
    """Hub sightings per filter mode from a scanner's survey file: only the hub advertises the bake-off service.

    Probes run filtered first, then unfiltered, so the n-th `done` record closes the n-th probe; a probe without
    its `done` record is reported as not completed rather than as "not seen".
    """
    out = {}
    dones = [r for r in records(path) if r.get("test") == "done"]
    for index, filtered in enumerate((True, False)):
        if len(dones) <= index:
            out["filtered" if filtered else "unfiltered"] = {"completed": False, "seen": None}
            continue
        hits = [r for r in records(path) if r.get("test") == "scan_survey_advertiser" and r.get("filtered") == filtered
                and r.get("has_bakeoff_service")]
        out["filtered" if filtered else "unfiltered"] = {
            "completed": dones[index].get("outcome") == "ok",
            "seen": bool(hits),
            "first_ms": min((r.get("first_ms") for r in hits), default=None),
            "rssi": hits[0].get("rssi") if hits else None,
            "count": sum(r.get("count") or 0 for r in hits),
            "scan_errors": [dones[index].get("error")] if dones[index].get("outcome") != "ok" else [],
        }
    return out


def inbound_sweep(hub, centrals, ios_scanner, android_scanner, tag=None, probe_s=30, unfiltered_s=20, connect_wait_s=100):
    """iPhone inbound-link sweep: `hub` advertises; holding centrals join one at a time; after each join the
    held links must keep echoing (every 2 s) while separate scanners probe the hub's visibility."""
    from concurrent.futures import ThreadPoolExecutor
    commit = git_commit()
    base = f"m6-inbound-{hub}" + (f"-{tag}" if tag else "")
    out_dir = RESULTS / base
    out_dir.mkdir(parents=True, exist_ok=True)
    roles = {"hub": hub, "ios_scanner": ios_scanner, **{f"central{k}": c for k, c in enumerate(centrals, 1)}}
    if android_scanner:
        roles["android_scanner"] = android_scanner
    write_run_meta(out_dir, base, commit, roles, ["role=hold", "duration=1500"], ["role=peripheral", "duration=1800"])
    for name in {hub, ios_scanner, android_scanner, *centrals} - {None}:
        stop(name)
    launch(hub, ["role=peripheral", f"run={base}", f"commit={commit}", "duration=1800"])
    time.sleep(10)
    held, steps, queue = [], [], list(centrals)
    k = 0
    while queue:
        k += 1
        step_id = f"{base}-k{k}"
        step_dir = RESULTS / step_id
        step_dir.mkdir(parents=True, exist_ok=True)
        failed_candidates = []
        connected = False
        # A central that cannot join is replaced by the next candidate for the same k (pair-specific 133s are
        # common); the sweep ends after two candidates fail at the same k.
        while queue and not connected and len(failed_candidates) < 2:
            central = queue.pop(0)
            joined_at = time.time()
            launch(central, ["role=hold", f"run={base}", f"commit={commit}", "duration=1500", "scan_timeout=20", "connect_retries=3"])
            while time.time() - joined_at < connect_wait_s:
                time.sleep(5)
                health = _hold_health(central, base, out_dir)
                if health["connected"]:
                    connected = True
                    break
                if health["ended"]:
                    break
            connect_s = round(time.time() - joined_at, 1)
            if connected:
                held.append(central)
            else:
                failed_candidates.append({"central": central, "wait_s": connect_s, **_hold_health(central, base, out_dir)})
                stop(central)
        scanners = [s for s in (ios_scanner, android_scanner) if s and s not in held]
        before = {c: _hold_health(c, base, out_dir) for c in held}
        probe = {}
        for filtered, seconds in ((True, probe_s), (False, unfiltered_s)):
            with ThreadPoolExecutor(len(scanners) or 1) as pool:
                list(pool.map(lambda s: _survey(s, step_id, seconds, filtered, step_dir / f"{s}-scanner.jsonl"), scanners))
        for s in scanners:
            probe[s] = _sightings(step_dir / f"{s}-scanner.jsonl")
        after = {c: _hold_health(c, base, out_dir) for c in held}
        hub_path = out_dir / f"{hub}-peripheral.jsonl"
        fetch(hub, base, hub_path)
        hub_records = records(hub_path)
        closes = [{"date": r.get("date"), "peer": (r.get("central") or {}).get("model"), "error": r.get("error")}
                  for r in hub_records if r.get("test") == "peripheral_link_end"]
        opens = [r.get("active_links") for r in hub_records if r.get("test") == "peripheral_link_open" and r.get("bearer") == "gatt"]
        healthy = [c for c in held if after[c]["connected"] and not after[c]["ended"] and after[c]["echoes"] > before[c]["echoes"]]
        step = {
            "run_id": step_id, "k": k, "new_central": central, "connected": connected, "connect_wait_s": connect_s,
            "failed_candidates": failed_candidates,
            "held": list(held), "healthy": healthy, "links_healthy": f"{len(healthy)}/{len(held)}",
            "health_before_probe": before, "health_after_probe": after,
            "hub_link_opens_active_links": opens, "hub_link_closes": closes, "probe": probe,
            "probe_seconds": {"filtered": probe_s, "unfiltered": unfiltered_s}, "date": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        }
        (step_dir / "step.json").write_text(json.dumps(step, indent=2) + "\n")
        steps.append(step)
        vis = {s: (p["filtered"]["seen"], p["unfiltered"]["seen"], p["filtered"]["first_ms"], p["filtered"]["rssi"]) for s, p in probe.items()}
        log(f"{step_id}: connected={connected} ({connect_s}s) healthy={step['links_healthy']} visible(f,u,first_ms,rssi)={vis} closes={len(closes)}")
        if not connected:
            break
    for name in [*held, *[c for s in steps for c in (f["central"] for f in s["failed_candidates"])], hub]:
        suffix = "peripheral" if name == hub else "hold"
        fetch(name, base, out_dir / f"{name}-{suffix}.jsonl")
    for name in {hub, *held, *centrals}:
        try:
            stop(name)
        except Exception as error:  # noqa: BLE001 - best effort
            log(f"stop {name}: {error}")
    (out_dir / "sweep.json").write_text(json.dumps(steps, indent=2) + "\n")
    return steps


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
    out_dir.mkdir(parents=True, exist_ok=True)
    write_run_meta(out_dir, run_id, commit, {"device": name}, args, [])
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
    h = sub.add_parser("hub", help="central-role max concurrency: --hub dials all --peers")
    h.add_argument("--hub", required=True, choices=sorted(DEVICES))
    h.add_argument("--peers", nargs="+", required=True, choices=sorted(DEVICES))
    h.add_argument("--run", required=True)
    h.add_argument("--timeout", type=int, default=900)
    h.add_argument("args", nargs="*")
    ph = sub.add_parser("phub", help="peripheral-role max concurrency: all --peers connect to --hub and hold")
    ph.add_argument("--hub", required=True, choices=sorted(DEVICES))
    ph.add_argument("--peers", nargs="+", required=True, choices=sorted(DEVICES))
    ph.add_argument("--run", required=True)
    ph.add_argument("--timeout", type=int, default=600)
    ph.add_argument("--stagger", type=int, default=10)
    ph.add_argument("args", nargs="*")
    du = sub.add_parser("dual", help="crossed-connection rounds between two devices")
    du.add_argument("a", choices=sorted(DEVICES))
    du.add_argument("b", choices=sorted(DEVICES))
    du.add_argument("--run", required=True)
    du.add_argument("--timeout", type=int, default=900)
    du.add_argument("args", nargs="*")
    st = sub.add_parser("stop", help="stop the lab app on devices (default: all)")
    st.add_argument("devices", nargs="*", choices=sorted(DEVICES))
    g = sub.add_parser("grant-ios", help="accept the iOS Bluetooth permission alert via the XCUITest runner")
    g.add_argument("devices", nargs="+", choices=[n for n, d in DEVICES.items() if d["platform"] == "ios"])
    ib = sub.add_parser("inbound", help="iPhone inbound-link sweep: centrals join --hub one at a time; scanners probe visibility")
    ib.add_argument("--hub", required=True, choices=sorted(DEVICES))
    ib.add_argument("--centrals", nargs="+", required=True, choices=sorted(DEVICES))
    ib.add_argument("--ios-scanner", required=True, choices=sorted(DEVICES))
    ib.add_argument("--android-scanner", choices=sorted(DEVICES))
    ib.add_argument("--tag", help="suffix for a fresh run id (device result files are append-only per run id)")
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
        elif args.command == "hub":
            sys.exit(0 if hub_run(args.hub, args.peers, args.run, args.args, args.timeout) else 2)
        elif args.command == "phub":
            sys.exit(0 if phub_run(args.hub, args.peers, args.run, args.args, args.timeout, args.stagger) else 2)
        elif args.command == "dual":
            sys.exit(0 if dual_run(args.a, args.b, args.run, args.args, args.timeout) else 2)
        elif args.command == "stop":
            for name in args.devices or sorted(DEVICES):
                try:
                    stop(name)
                except Exception as error:  # noqa: BLE001 - best effort, offline devices are reported
                    log(f"stop {name}: {error}")
        elif args.command == "grant-ios":
            results = {name: grant_ios(name) for name in args.devices}
            sys.exit(0 if all(results.values()) else 2)
        elif args.command == "inbound":
            inbound_sweep(args.hub, args.centrals, args.ios_scanner, args.android_scanner, args.tag)
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
