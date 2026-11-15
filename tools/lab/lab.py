#!/usr/bin/env python3
"""Drive the MeshLink lab apps on real phones (ADR 0017 decision 7; standard library only).

`run` builds the lab apps from this worktree, installs them on the named lab phones, launches each with
`key=value` launch arguments, waits for every phone's `done` record, pulls the JSON Lines results into
`build/lab/<run>/` and prints a pass or fail summary. `evidence` turns a run folder into a T9 record under
`docs/evidence/`. `grant` accepts the iOS Bluetooth alert through the UI-test runner; `stop` stops the app.

Phones are named by lab alias only. Aliases resolve through the local, untracked file
`~/.config/meshlink-lab/devices.json` (or `$MESHLINK_LAB_CONFIG/devices.json`); see `tools/lab/README.md`.

Usage (from a dedicated worktree with a clean tree):
  python3 tools/lab/lab.py run --scenario direct-message --devices pixel6,ip15 --profile unsecured
  python3 tools/lab/lab.py evidence --run build/lab/<run>
  python3 tools/lab/lab.py grant --devices ip15
  python3 tools/lab/lab.py stop --devices pixel6,ip15
"""

import argparse
import datetime
import gzip
import hashlib
import hmac
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RESULTS = ROOT / "build" / "lab"
EVIDENCE = ROOT / "docs" / "evidence"
APK = ROOT / "labapp/android/build/outputs/apk/debug/labapp-android-debug.apk"
IOS_PROJECT = ROOT / "labapp/ios"
ANDROID_PACKAGE = "ch.trancee.lab.host"
IOS_EXECUTABLE = "/LabHost.app/LabHost"
SCENARIOS = ("direct-message",)
# Lab profile names (ADR 0007 decision 5). The apps fail fast on a profile their build lacks.
PROFILES = ("unsecured", "classical", "post-quantum")
ROLES = ("any", "central", "peripheral")
TOOLS = ("devicectl", "imd")
NEARBY_DEVICES_API = 31
RUN_ID = re.compile(r"[A-Za-z0-9._-]{1,64}")
MAC = re.compile(r"(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f:])")
DURATION_S = range(5, 3601)
KEY_BYTES = 32
GZIP_ABOVE_BYTES = 64 * 1024
DONE_GRACE_S = 120
POLL_S = 10


class LabError(Exception):
    """A lab command cannot run; the message says what to fix."""


def log(message):
    print(f"[{time.strftime('%H:%M:%S')}] {message}", flush=True)


# ---------------------------------------------------------------- configuration

def config_dir(environ=os.environ):
    """The local lab configuration folder: `$MESHLINK_LAB_CONFIG`, else `~/.config/meshlink-lab`."""
    override = environ.get("MESHLINK_LAB_CONFIG")
    return Path(override) if override else Path.home() / ".config" / "meshlink-lab"


def load_config(directory):
    """The validated `devices.json` in `directory`."""
    path = directory / "devices.json"
    if not path.exists():
        raise LabError(f"no lab configuration at {path}; create it as tools/lab/README.md describes")
    config = json.loads(path.read_text())
    teams = config.get("teams", {})
    for alias, device in config.get("devices", {}).items():
        platform = device.get("platform")
        if platform == "android":
            required = ("serial",)
        elif platform == "ios":
            required = ("udid", "team", "tool")
        else:
            raise LabError(f"device {alias}: platform must be android or ios")
        missing = [key for key in required if not device.get(key)]
        if missing:
            raise LabError(f"device {alias}: missing {missing}")
        if platform == "ios" and device["tool"] not in TOOLS:
            raise LabError(f"device {alias}: tool must be one of {list(TOOLS)}")
        if platform == "ios" and not {"id", "bundle"} <= set(teams.get(device["team"], {})):
            raise LabError(f"device {alias}: team {device['team']!r} needs an id and a bundle under \"teams\"")
    return config


def split_list(text):
    return [item.strip() for item in text.split(",") if item.strip()]


def resolve(config, aliases):
    """The configured devices for `aliases`, in order; fails naming every alias that is missing."""
    devices = config.get("devices", {})
    if len(aliases) != len(set(aliases)):
        raise LabError(f"a phone is named twice: {aliases}")
    missing = [alias for alias in aliases if alias not in devices]
    if missing:
        raise LabError(
            f"unknown lab alias {missing}: add it to devices.json in the lab configuration folder "
            f"(known: {sorted(devices) or 'none'})"
        )
    return [dict(devices[alias], alias=alias) for alias in aliases]


def bundle_id(config, device):
    if device["platform"] == "android":
        return ANDROID_PACKAGE
    return config["teams"][device["team"]]["bundle"]


# ---------------------------------------------------------------- launch arguments

def launch_args(scenario, role, peer, mesh, profile, run_id, duration_s):
    """The `key=value` launch arguments the lab apps parse (`labapp/shared`, `LabArgs`)."""
    if scenario not in SCENARIOS:
        raise LabError(f"scenario must be one of {list(SCENARIOS)}, not {scenario}")
    if profile not in PROFILES:
        raise LabError(f"profile must be one of {list(PROFILES)}, not {profile}")
    if role not in ROLES:
        raise LabError(f"role must be one of {list(ROLES)}, not {role}")
    if not RUN_ID.fullmatch(run_id):
        raise LabError(f"run id must match {RUN_ID.pattern}, not {run_id}")
    if duration_s not in DURATION_S:
        raise LabError(f"duration must be {DURATION_S.start}-{DURATION_S.stop - 1} s, not {duration_s}")
    return [
        f"scenario={scenario}",
        f"role={role}",
        f"peer={peer}",
        f"mesh={mesh}",
        f"profile={profile}",
        f"run={run_id}",
        f"duration={duration_s}",
    ]


def plan(aliases, roles, scenario, profile, run_id, duration_s):
    """Launch arguments per alias: each phone's peer is the other phone; both share one mesh ID."""
    if len(aliases) != 2:
        raise LabError(f"{scenario} needs exactly two phones, not {len(aliases)}")
    roles = roles or ["any"] * len(aliases)
    if len(roles) != len(aliases):
        raise LabError(f"--roles needs one role per phone, not {len(roles)} for {len(aliases)}")
    mesh = f"lab-{run_id}"[:64]
    return {
        alias: launch_args(scenario, role, aliases[1 - index], mesh, profile, run_id, duration_s)
        for index, (alias, role) in enumerate(zip(aliases, roles))
    }


def default_run_id(scenario, now):
    return f"{scenario}-{now.strftime('%Y%m%d-%H%M%S')}"


# ---------------------------------------------------------------- pseudonyms

def pseudonym(key, address):
    """A lowercase keyed pseudonym of a Bluetooth `address` that keeps its 2 address-type bits."""
    raw = bytes.fromhex(address.replace(":", ""))
    digest = hmac.new(key, raw, hashlib.sha256).digest()[:6]
    first = (raw[0] & 0xC0) | (digest[0] & 0x3F)
    return ":".join(f"{byte:02x}" for byte in bytes([first]) + digest[1:])


def pseudonymize(key, text):
    return MAC.sub(lambda match: pseudonym(key, match.group(0)), text)


def load_key(directory):
    path = directory / "pseudonym.key"
    if not path.exists():
        raise LabError(f"no pseudonym key at {path}; create {KEY_BYTES} random bytes as tools/lab/README.md describes")
    key = path.read_bytes()
    if len(key) < KEY_BYTES:
        raise LabError(f"pseudonym key {path} has {len(key)} bytes; it needs at least {KEY_BYTES}")
    return key


# ---------------------------------------------------------------- results

def records(path):
    out = []
    if not path.exists():
        return out
    for line in path.read_text().splitlines():
        line = line.strip()
        if line.startswith("{"):
            try:
                out.append(json.loads(line))
            except json.JSONDecodeError:
                out.append({"event": "unparsable", "line": line})
    return out


def outcome(rows):
    """`pass`, `fail` or `incomplete` (no `done` record) and the failures the phone reported."""
    done = [row for row in rows if row.get("event") == "done"]
    if not done:
        return "incomplete", ["no done record"]
    return done[-1].get("result", "fail"), list(done[-1].get("failures", []))


def summary(results):
    """One line per phone plus the overall result; `results` maps alias to its records."""
    lines, passed = [], True
    for alias, rows in results.items():
        result, failures = outcome(rows)
        link = next((row for row in rows if row.get("event") == "link"), None)
        passed &= result == "pass"
        role = f" role={link.get('role')}" if link else ""
        lines.append(f"  {alias:10} {result:10}{role} {'; '.join(failures)}".rstrip())
    lines.append(f"result: {'pass' if passed else 'fail'}")
    return "\n".join(lines), passed


def android_permissions(api):
    """The runtime permissions MeshLink's manifest asks for on this API level: Nearby devices from 12, else location."""
    if api >= NEARBY_DEVICES_API:
        return ("BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE")
    return ("ACCESS_FINE_LOCATION",)


def first(rows, event, **fields):
    return next((row for row in rows if row.get("event") == event
                 and all(row.get(k) == v for k, v in fields.items())), None)


def timings(rows):
    """Connect, send-to-Sent and link-to-received times in ms, each on this phone's own clock."""
    def between(a, b):
        return b["t_ms"] - a["t_ms"] if a and b else None
    link = first(rows, "link")
    return {
        "connect_ms": between(first(rows, "start"), link),
        "send_to_sent_ms": between(first(rows, "send"), first(rows, "status", status="Sent")),
        "link_to_received_ms": between(link, first(rows, "received")),
    }


def identities(runs):
    """Maps each phone to the NodeIds its partner recorded for it, in run order; `runs` hold two phones each."""
    ids = {}
    for results in runs:
        for alias, partner in zip(results, reversed(list(results))):
            link = first(results[partner], "link")
            seen = ids.setdefault(alias, [])
            if link and link["peer_node"] not in seen:
                seen.append(link["peer_node"])
    return ids


# ---------------------------------------------------------------- git

def git(*args):
    return subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, text=True, check=True).stdout.strip()


def require_worktree():
    """The commit to record; refuses the main checkout and dirty trees (ADR 0017, 2026-11-13 amendment)."""
    if git("rev-parse", "--git-dir") == git("rev-parse", "--git-common-dir"):
        raise LabError("run the lab from a dedicated worktree: git worktree add ../meshlink-lab <ref>")
    if git("status", "--porcelain"):
        raise LabError("the worktree has uncommitted changes; commit or stash them so the run names one commit")
    return git("rev-parse", "HEAD")


# ---------------------------------------------------------------- device commands

def command(cmd, timeout=300, check=True, env=None, quiet=False, stdin=None):
    if not quiet:
        log("$ " + " ".join(str(part) for part in cmd))
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, env=env, input=stdin)
    if check and result.returncode != 0:
        raise LabError(f"command failed ({result.returncode}): {' '.join(map(str, cmd))}\n{result.stdout}{result.stderr}")
    return result


class Lab:
    """The lab phones of one configuration and the host tools that drive them."""

    def __init__(self, config):
        self.config = config

    def adb(self, device, *args, timeout=120, check=True, quiet=False):
        env = dict(os.environ)
        if self.config.get("adb_server"):
            env["ADB_SERVER_SOCKET"] = self.config["adb_server"]
        return command(["adb", "-s", device["serial"], *args], timeout=timeout, check=check, env=env, quiet=quiet)

    @staticmethod
    def products(device):
        return IOS_PROJECT / f"build/dd-{device['team']}/Build/Products"

    def build(self, devices):
        platforms = {device["platform"] for device in devices}
        gradle = [str(ROOT / "gradlew"), "-p", str(ROOT), "-q"]
        if "android" in platforms:
            command([*gradle, ":labapp-android:assembleDebug"], timeout=1800)
        if "ios" in platforms:
            command([*gradle, ":labapp-shared:linkReleaseFrameworkIosArm64"], timeout=1800)
            command(["xcodegen", "generate", "-q", "--spec", str(IOS_PROJECT / "project.yml")], timeout=120)
            for team in sorted({device["team"] for device in devices if device["platform"] == "ios"}):
                command([
                    "xcodebuild", "-project", str(IOS_PROJECT / "LabHost.xcodeproj"), "-scheme", "LabHost",
                    "-configuration", "Release", "-destination", "generic/platform=iOS",
                    "-derivedDataPath", str(IOS_PROJECT / f"build/dd-{team}"),
                    f"DEVELOPMENT_TEAM={self.config['teams'][team]['id']}",
                    f"LAB_BUNDLE_ID={self.config['teams'][team]['bundle']}",
                    "-allowProvisioningUpdates", "-allowProvisioningDeviceRegistration", "-quiet", "build-for-testing",
                ], timeout=1800)

    def install(self, device):
        if device["platform"] == "android":
            self.adb(device, "install", "-r", "-g", str(APK), timeout=300)
            api = int(self.adb(device, "shell", "getprop", "ro.build.version.sdk", quiet=True).stdout.strip())
            for permission in android_permissions(api):
                self.adb(device, "shell", "pm", "grant", ANDROID_PACKAGE, f"android.permission.{permission}", quiet=True)
            return
        app = self.products(device) / "Release-iphoneos/LabHost.app"
        if device["tool"] == "devicectl":
            command(["xcrun", "devicectl", "device", "install", "app", "--device", device["udid"], str(app)], timeout=600)
        else:
            command(["ideviceinstaller", "-u", device["udid"], "install", str(app)], timeout=600)

    def launch(self, device, args):
        bundle = bundle_id(self.config, device)
        if device["platform"] == "android":
            self.adb(device, "shell", "am", "start", "-S", "-n", f"{bundle}/.MainActivity", "--es", "args", ",".join(args))
        elif device["tool"] == "devicectl":
            command(["xcrun", "devicectl", "device", "process", "launch", "--device", device["udid"],
                     "--terminate-existing", bundle, *args], timeout=120)
        else:
            # An attached debugserver session can stop the app right after launch; detached launches run.
            command(["idevicedebug", "-u", device["udid"], "--detach", "run", bundle, *args], timeout=120)

    def stop(self, device):
        bundle = bundle_id(self.config, device)
        if device["platform"] == "android":
            self.adb(device, "shell", "am", "force-stop", bundle, check=False, quiet=True)
            return
        if device["tool"] == "imd":
            command(["idevicedebug", "-u", device["udid"], "kill", bundle], timeout=60, check=False, quiet=True)
            return
        processes = self.devicectl_json(device, "info", "processes").get("runningProcesses", [])
        for process in processes:
            if process.get("executable", "").endswith(IOS_EXECUTABLE):
                command(["xcrun", "devicectl", "device", "process", "terminate", "--device", device["udid"],
                         "--pid", str(process["processIdentifier"]), "-q"], timeout=60, check=False, quiet=True)

    def devicectl_json(self, device, *args):
        """The `result` object of a `devicectl device` command's JSON output; empty if it failed."""
        listing = RESULTS / f".devicectl-{device['alias']}.json"
        listing.parent.mkdir(parents=True, exist_ok=True)
        command(["xcrun", "devicectl", "device", *args, "--device", device["udid"], "--json-output", str(listing),
                 "-q"], timeout=60, check=False, quiet=True)
        try:
            return json.loads(listing.read_text()).get("result", {})
        except (OSError, json.JSONDecodeError):
            return {}
        finally:
            listing.unlink(missing_ok=True)

    def fetch(self, device, run_id, destination):
        """Copies the phone's `<run_id>.jsonl` to `destination`; `False` if it does not exist yet."""
        destination.parent.mkdir(parents=True, exist_ok=True)
        bundle = bundle_id(self.config, device)
        source = f"results/{run_id}.jsonl"
        if device["platform"] == "android":
            result = self.adb(device, "exec-out", "run-as", bundle, "cat", f"files/{source}", check=False, quiet=True)
            if result.returncode != 0 or "No such file" in result.stdout:
                return False
            destination.write_text(result.stdout)
            return True
        if device["tool"] == "devicectl":
            result = command(["xcrun", "devicectl", "device", "copy", "from", "--device", device["udid"],
                              "--domain-type", "appDataContainer", "--domain-identifier", bundle,
                              "--source", f"Documents/{source}", "--destination", str(destination), "-q"],
                             timeout=120, check=False, quiet=True)
            return result.returncode == 0 and destination.exists()
        # afcclient exits 0 on errors and refuses to overwrite without -f.
        result = command(["afcclient", "-u", device["udid"], "--container", bundle], timeout=60, check=False,
                         quiet=True, stdin=f"get -f Documents/{source} {destination}\nquit\n")
        failed = "Error" in result.stdout or "Error" in result.stderr
        return result.returncode == 0 and not failed and destination.exists() and destination.stat().st_size > 0

    def describe(self, device):
        """Model and OS version, read from the phone (T9)."""
        if device["platform"] == "android":
            prop = lambda name: self.adb(device, "shell", "getprop", name, quiet=True).stdout.strip()  # noqa: E731
            release, api = prop("ro.build.version.release"), prop("ro.build.version.sdk")
            return {"model": prop("ro.product.model"), "os": f"Android {release} (API {api})"}
        if device["tool"] == "devicectl":
            details = self.devicectl_json(device, "info", "details")
            model = details.get("hardwareProperties", {}).get("productType", "unknown")
            version = details.get("deviceProperties", {}).get("osVersionNumber", "unknown")
            return {"model": model, "os": f"iOS {version}"}
        info = lambda key: command(["ideviceinfo", "-u", device["udid"], "-k", key], timeout=60,  # noqa: E731
                                   quiet=True).stdout.strip()
        return {"model": info("ProductType"), "os": f"iOS {info('ProductVersion')}"}


# ---------------------------------------------------------------- commands

def run(lab, devices, roles, scenario, profile, duration_s, run_id, build):
    """Runs one scenario on `devices`; returns whether every phone passed."""
    commit = require_worktree()
    aliases = [device["alias"] for device in devices]
    launches = plan(aliases, roles, scenario, profile, run_id, duration_s)
    out_dir = RESULTS / run_id
    if out_dir.exists():
        raise LabError(f"{out_dir} exists; pick another --run-id")
    if build:
        lab.build(devices)
    for device in devices:
        lab.install(device)
        lab.stop(device)
    meta = {
        "run": run_id,
        "scenario": scenario,
        "profile": profile,
        "duration_s": duration_s,
        "commit": commit,
        "date": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
        "method": f"python3 tools/lab/lab.py run --scenario {scenario} --devices {','.join(aliases)} --profile {profile}",
        "xcode": command(["xcodebuild", "-version"], quiet=True).stdout.splitlines()[0],
        "devices": {
            device["alias"]: {"platform": device["platform"], **lab.describe(device), "args": launches[device["alias"]]}
            for device in devices
        },
    }
    out_dir.mkdir(parents=True)
    (out_dir / "run.json").write_text(json.dumps(meta, indent=2) + "\n")
    for device in devices:
        lab.launch(device, launches[device["alias"]])
    deadline = time.time() + duration_s + DONE_GRACE_S
    results = {}
    for device in devices:
        destination = out_dir / f"{device['alias']}.jsonl"
        while not (lab.fetch(device, run_id, destination) and outcome(records(destination))[0] != "incomplete"):
            if time.time() > deadline:
                break
            time.sleep(POLL_S)
        results[device["alias"]] = records(destination)
    for device in devices:
        lab.stop(device)
    text, passed = summary(results)
    log(f"run {run_id} at {commit[:12]}; results in {out_dir.relative_to(ROOT)}")
    print(text)
    return passed


def evidence(run_dirs, name, key, today):
    """Writes `docs/evidence/<date>-<name>/` from run folders: a README with every T9 field and the raw files."""
    if len(run_dirs) > 1 and not name:
        raise LabError("a record covering several runs needs --name")
    metas = [json.loads((run_dir / "run.json").read_text()) for run_dir in run_dirs]
    folder = EVIDENCE / f"{today}-{name or metas[0]['run']}"
    if folder.exists():
        raise LabError(f"{folder} exists")
    runs, sections, rows, devices, pairs, passed = [], [], [], {}, [], True
    for run_dir, meta in zip(run_dirs, metas):
        raw = folder / "raw" / meta["run"]
        raw.mkdir(parents=True)
        results = {}
        for path in sorted(run_dir.glob("*.jsonl")):
            text = pseudonymize(key, path.read_text())
            results[path.stem] = [json.loads(line) for line in text.splitlines() if line.startswith("{")]
            data = text.encode()
            if len(data) > GZIP_ABOVE_BYTES:
                (raw / f"{path.name}.gz").write_bytes(gzip.compress(data, mtime=0))
            else:
                (raw / path.name).write_bytes(data)
        shutil.copyfile(run_dir / "run.json", raw / "run.json")
        runs.append(results)
        text, run_passed = summary(results)
        passed &= run_passed
        devices.update(meta["devices"])
        pair = "-".join(sorted(device["platform"] for device in meta["devices"].values()))
        if pair not in pairs:
            pairs.append(pair)
        for alias, records_ in results.items():
            link = first(records_, "link")
            timing = timings(records_)
            cells = " | ".join("–" if value is None else str(value) for value in timing.values())
            rows.append(f"| `{meta['run']}` | `{alias}` | {link.get('role') if link else '–'} "
                        f"| {outcome(records_)[0]} | {cells} |")
        files = "\n".join(f"- [`raw/{meta['run']}/{path.name}`](raw/{meta['run']}/{path.name})"
                          for path in sorted(raw.iterdir()))
        args = "\n".join(f"- `{alias}`: `{' '.join(device['args'])}`" for alias, device in meta["devices"].items())
        sections.append(f"""### `{meta['run']}`

- **Date:** {meta['date']}
- **Commit:** `{meta['commit']}`
- **Method:** `{meta['method']}`; iOS apps built with {meta['xcode']}
- **Profile:** `{meta['profile']}`, {meta['duration_s']} s per phone

Launch arguments:

{args}

```text
{text}
```

Raw results:

{files}
""")
    device_rows = "\n".join(f"| `{alias}` | {device['model']} | {device['os']} |" for alias, device in devices.items())
    id_rows = "\n".join(f"| `{alias}` | {', '.join(f'`{i}`' for i in ids)} | {'same' if len(ids) == 1 else 'changed'} |"
                         for alias, ids in identities(runs).items())
    run_rows = "\n".join(rows)
    commits = ", ".join(sorted({f"`{meta['commit']}`" for meta in metas}))
    readme = f"""# Lab record `{name or metas[0]['run']}`: `{metas[0]['scenario']}`

- **Date:** {metas[0]['date']}
- **Commit:** {commits}
- **Platform pairs:** {' and '.join(pairs)}
- **Runs:** {len(metas)}
- **Result:** {'pass' if passed else 'fail'}

Observed on real phones by the lab apps in `labapp/`. Bluetooth addresses are keyed pseudonyms that keep
the 2 address-type bits; the key stays on the lab Mac.

## Devices

| Alias | Model | OS |
|---|---|---|
{device_rows}

## Runs

Times are in ms, each on the phone's own monotonic clock: connect is app start to first link, send to
`Sent` is on the sender, link to received is on the receiver and includes the peer's own link and send.
The phones' clocks are not synchronized, so one-way latency is not measured.

| Run | Phone | Role | Result | Connect | Send to `Sent` | Link to received |
|---|---|---|---|---|---|---|
{run_rows}

## Identity across relaunches

Each run relaunches both apps. A phone's NodeId is the one its partner recorded on linking.

| Phone | NodeIds seen | Across runs |
|---|---|---|
{id_rows}

## Run details

{chr(10).join(sections)}"""
    (folder / "README.md").write_text(readme)
    return folder


def grant_report(output):
    """Picks the lines worth printing from xcodebuild output: grant steps, errors and the failure summary."""
    report, summary = [], False
    for line in output.splitlines():
        text = line.strip()
        summary = (summary and bool(text)) or text == "Testing failed:"
        if summary or "LAB-GRANT" in text or "error:" in text or text.startswith("** TEST"):
            report.append(text[:300])
    return report


def grant(lab, device):
    """Runs the UI-test runner that accepts the Bluetooth alert by exact button label."""
    if device["platform"] != "ios":
        raise LabError(f"{device['alias']} is not an iPhone")
    xctestrun = sorted(lab.products(device).glob("*.xctestrun"))
    if not xctestrun:
        raise LabError(f"no .xctestrun in {lab.products(device)}; build with `lab.py run` first")
    env = dict(os.environ)
    if device.get("grant_attach"):
        # On some iOS releases XCUIApplication.launch() fails; the host launches the app and the runner attaches.
        env["TEST_RUNNER_GRANT_ATTACH"] = "1"
        lab.stop(device)
        lab.launch(device, ["grant=1"])
        time.sleep(3)
    result = command(["xcodebuild", "test-without-building", "-xctestrun", str(xctestrun[0]),
                      "-destination", f"id={device['udid']}", "-allowProvisioningUpdates"],
                     timeout=600, check=False, env=env)
    output = result.stdout + result.stderr
    for line in grant_report(output):
        print("  " + line)
    return result.returncode == 0 and "** TEST EXECUTE SUCCEEDED **" in output


def parser():
    root = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = root.add_subparsers(dest="command", required=True)
    r = sub.add_parser("run", help="build, install, launch, wait and fetch one scenario")
    r.add_argument("--scenario", required=True, choices=SCENARIOS)
    r.add_argument("--devices", required=True, type=split_list, help="two lab aliases, comma-separated")
    r.add_argument("--profile", default="unsecured", choices=PROFILES)
    r.add_argument("--roles", type=split_list, help="one role per phone (any, central, peripheral), comma-separated")
    r.add_argument("--duration", type=int, default=60, help="seconds each phone runs (5-3600)")
    r.add_argument("--run-id", help="defaults to <scenario>-<date>-<time>")
    r.add_argument("--no-build", action="store_true", help="install the apps already built in this worktree")
    e = sub.add_parser("evidence", help="write a T9 record under docs/evidence/ from a run folder")
    e.add_argument("--run", required=True, type=Path, action="append",
                   help="build/lab/<run>; repeat it to cover several runs in one record")
    e.add_argument("--name", help="evidence folder name after the date; defaults to the run id of a single run")
    g = sub.add_parser("grant", help="accept the iOS Bluetooth alert through the UI-test runner")
    g.add_argument("--devices", required=True, type=split_list)
    s = sub.add_parser("stop", help="stop the lab app")
    s.add_argument("--devices", required=True, type=split_list)
    return root


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        if args.command == "evidence":
            folder = evidence(args.run, args.name, load_key(config_dir()), datetime.date.today().isoformat())
            print(folder.relative_to(ROOT))
            return 0
        lab = Lab(load_config(config_dir()))
        devices = resolve(lab.config, args.devices)
        if args.command == "run":
            run_id = args.run_id or default_run_id(args.scenario, datetime.datetime.now())
            passed = run(lab, devices, args.roles, args.scenario, args.profile, args.duration, run_id, not args.no_build)
            return 0 if passed else 2
        if args.command == "grant":
            return 0 if all([grant(lab, device) for device in devices]) else 2
        for device in devices:
            lab.stop(device)
        return 0
    except LabError as error:
        print(f"lab: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
