#!/usr/bin/env python3
"""Run the MeshLink proof app across the attached Android fleet.

REQUIRED before every physical-device run (AGENTS.md rule; see
meshlink-benchmark/history.md's 2026-07-09 stale-build false-negative entry
for why this matters and 2026-07-10 for a repeat of the same mistake against
this exact script):

    1. ./gradlew :meshlink-proof:android:assembleDebug
    2. adb -s <serial> uninstall ch.trancee.meshlink.proof.android   (each device)
    3. Then run this script.

This script does NOT rebuild the APK and only reinstalls in place
(`adb install -r`), so an install left over from days/weeks ago is silently
reused otherwise. `check_apk_freshness()` below prints a WARNING (and records
`apkFreshnessWarning` in summary.json) if the APK on disk predates the newest
commit under `meshlink-proof/` or `meshlink/`, but it cannot force step 1/2
for you - do not skip them.

Troubleshooting: ADVERTISE_FAILED_TOO_MANY_ADVERTISERS / SCAN_FAILED_*
-----------------------------------------------------------------------
A device that has been advertising/scanning for a long time (many hours) can
accumulate stuck BLE advertiser/scanner registrations in its Bluetooth stack,
even though `dumpsys bluetooth_manager` reports `enabled: true` / `state: ON`.
This shows up in the proof app's own diagnostics log (`proof.log`) as repeated

    DIAG DISCOVERY_ADVERTISE_FAILED ... errorName=ADVERTISE_FAILED_TOO_MANY_ADVERTISERS willRetry=true
    DIAG DISCOVERY_ADVERTISE_FAILED ... willRetry=false   <- retry budget exhausted

(or the scan-side equivalent, `DISCOVERY_SCAN_FAILED` with
`errorName=SCAN_FAILED_*`), followed by `HOP_SESSION_FAILED` /
`routeAvailable=false` because the affected device can never re-advertise or
re-scan long enough to complete the handshake.

The reliable fix observed on the bench (confirmed on a Nothing A063 stuck for
76+ hours) is a full Bluetooth stack restart on the affected device:

    adb -s <serial> shell cmd bluetooth_manager disable
    adb -s <serial> shell cmd bluetooth_manager enable

This clears the stuck advertiser/scanner slots without needing a full device
reboot. This script automates that recovery: after the first capture pass, it
scans each device's proof.log for exhausted (`willRetry=false`) advertise/scan
failures, restarts the Bluetooth stack on any affected device, relaunches the
proof app for every pair that includes it, waits again, and recaptures logs
before finalizing the summary. Pass --no-auto-recover-bluetooth to disable
this and inspect the raw first-pass failure instead.

Troubleshooting: the BLE_ON limbo state
----------------------------------------
`dumpsys bluetooth_manager` can report a third state beyond `ON`/`OFF`:
`BLE_ON`, a hybrid state where "classic" Bluetooth is disabled
(`enabled: false`) but a BLE-only client registration (scan/advertise/GATT)
keeps the stack partially alive. This was observed reproducibly on a Nothing
A063 right after a proof-app run: issuing `cmd bluetooth_manager disable`
while the proof app still held a live BLE registration left the device stuck
in `BLE_ON` instead of transitioning fully to `OFF`. The naive
`cmd bluetooth_manager wait-for-state:STATE_OFF` command does not tolerate
this - it fails outright with exit status 255 rather than waiting/timing out,
crashing the whole run.

Manual recovery from `BLE_ON` is simple (`cmd bluetooth_manager enable` +
`wait-for-state:STATE_ON` brings the device straight back to a clean `ON`
state), so [restart_bluetooth_stack] is hardened against this: it force-stops
the proof app *before* issuing `disable` (releasing any BLE registration held
by our own app), and uses [poll_bluetooth_state] - which polls
`dumpsys bluetooth_manager` directly instead of relying on the brittle
`wait-for-state` subcommand - to accept either `OFF` or `BLE_ON` as a valid
"disabled enough" outcome before re-enabling. See
docs/explanation/reference-app-physical-integration-findings.md for the full
investigation.

Logcat evidence
---------------
Every run also persists the full MeshLinkReferenceAutomation-tagged logcat per
device to logs/<serial>.logcat.log. proof.log only contains diagnostics the
proof app explicitly writes; lower-level BLE/GATT/L2CAP transport detail -
including the receive-path evidence needed to diagnose a missing inbound
delivery - only ever reaches logcat. Use --logcat-tail-lines to widen the
capture window for long --wait-seconds runs (default 20000, raised from an
original 4000).

A noisy device - one producing heavy non-MeshLink log traffic from other
apps/system services, observed on an OPPO CPH2359 - can push every
MeshLinkReferenceAutomation-tagged line out of even a generous tail window,
producing a silent, misleading 0-line capture that looks like "the app
logged nothing" when it actually logged plenty, just further back in the
buffer than the requested tail reached. This exact blind spot delayed
diagnosing a real CPH2359<->DN2103 BLE-scan-miss bug (see
docs/explanation/reference-app-physical-integration-findings.md, "The BLE
scanner can silently miss a specific peer's advertisements"). To guard
against this, capture_full_logcat() automatically retries once with a much
wider tail (LOGCAT_ZERO_LINE_RETRY_TAIL_LINES, 100000 lines) whenever the
requested --logcat-tail-lines window captures 0 matching lines, before
concluding the device genuinely emitted nothing.

Post-run cleanup
-----------------
As its last step, every run force-stops the proof app (`am force-stop`) on
every device it exercised. A device left running after a prior run keeps
scanning/advertising/holding GATT connections in the background under the old
run's app ID, which produces stray cross-app-id BLE traffic and occupied GATT
server slots that can make a later, otherwise-isolated `--device` rerun look
like it is failing for protocol reasons when it is really just interference
from a still-running earlier instance.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import datetime, timezone
from functools import lru_cache
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
APK = ROOT / "meshlink-proof" / "android" / "build" / "outputs" / "apk" / "debug" / "android-debug.apk"
PACKAGE_NAME = "ch.trancee.meshlink.proof.android"
ACTIVITY_NAME = ".MainActivity"
DEFAULT_POWER_MODE = "performance"
DEFAULT_PAYLOAD_BYTES = 64
DEFAULT_WAIT_SECONDS = 30
DEFAULT_MAX_CONCURRENT_PAIRS = 2
# A full-fleet run that launches every pair (and therefore every device)
# simultaneously was found to cause severe BLE RF congestion: peers flap
# found/lost within milliseconds and handshakes fail with AEADBadTagException
# (corrupted crypto payloads consistent with BLE packet collisions), so
# almost no pair completes a genuine end-to-end delivery. Running only a
# couple of pairs at a time keeps the airtime/connection-slot pressure low
# enough for handshakes to actually complete. See
# docs/explanation/reference-app-physical-integration-findings.md.
DEFAULT_RUN_ROOT = ROOT / "reports" / "android-proof-fleet" / "runs"
DEFAULT_LOGCAT_TAIL_LINES = 20_000
# Raised from the original 4000 default: a noisy device (heavy non-MeshLink
# log traffic, observed on an OPPO CPH2359) can push every
# MeshLinkReferenceAutomation-tagged line out of a short tail entirely,
# producing a silent, misleading 0-line capture. See capture_full_logcat().
LOGCAT_ZERO_LINE_RETRY_TAIL_LINES = 100_000
MESH_MODE_RE = re.compile(r"\bmode=([A-Z_][A-Z0-9_\-]*)\b")
TRANSPORT_RE = re.compile(r"\btransport(?:Mode)?=([A-Z_][A-Z0-9_\-]*)\b")
WIRELESS_SUFFIX = "._adb-tls-connect._tcp"


@dataclass(frozen=True)
class DeviceRecord:
    listed_id: str
    resolved_serial: str
    model: str
    api_level: int
    raw: str


@dataclass(frozen=True)
class PairRecord:
    label: str
    initiator: DeviceRecord
    receiver: DeviceRecord


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run the MeshLink proof app across the attached Android fleet.")
    parser.add_argument(
        "--run-root",
        help="Directory for the run bundle. Defaults to reports/android-proof-fleet/runs/<timestamp>",
    )
    parser.add_argument(
        "--app-id",
        help="Mesh domain used for the proof run. Defaults to a timestamped demo.meshlink.proof.* value.",
    )
    parser.add_argument(
        "--power-mode",
        default=DEFAULT_POWER_MODE,
        help="Power mode extra passed to the proof app (default: performance)",
    )
    parser.add_argument(
        "--payload-bytes",
        type=int,
        default=DEFAULT_PAYLOAD_BYTES,
        help="Benchmark payload size for the initiator side (default: 64)",
    )
    parser.add_argument(
        "--wait-seconds",
        type=int,
        default=DEFAULT_WAIT_SECONDS,
        help="How long to wait for peer discovery and delivery before logs are captured",
    )
    parser.add_argument(
        "--pair-label",
        action="append",
        dest="pair_labels",
        default=[],
        help="Only run the named pair label(s). May be repeated.",
    )
    parser.add_argument(
        "--device",
        action="append",
        dest="devices",
        default=[],
        help=(
            "Force an explicit initiator/receiver pair by adb serial or listed device id, "
            "bypassing fleet-wide cross-generation pairing. Pass exactly twice: "
            "first occurrence is the initiator, second is the receiver. "
            "Cannot be combined with --pair-label."
        ),
    )
    parser.add_argument(
        "--no-auto-recover-bluetooth",
        action="store_false",
        dest="auto_recover_bluetooth",
        default=True,
        help=(
            "Disable automatic Bluetooth stack restart + retry when a device's proof.log shows "
            "exhausted DISCOVERY_ADVERTISE_FAILED/DISCOVERY_SCAN_FAILED retries (default: enabled). "
            "See ADVERTISE_FAILED_TOO_MANY_ADVERTISERS troubleshooting notes at the top of this file."
        ),
    )
    parser.add_argument(
        "--max-concurrent-pairs",
        type=int,
        default=DEFAULT_MAX_CONCURRENT_PAIRS,
        help=(
            "How many pairs to launch and run simultaneously (default: "
            f"{DEFAULT_MAX_CONCURRENT_PAIRS}). A full 14-device/7-pair fleet run with all "
            "pairs launched at once was found to cause severe BLE congestion: peers flap "
            "found/lost within a few milliseconds and handshakes fail with "
            "AEADBadTagException (corrupted crypto payloads consistent with BLE packet "
            "collisions), so almost no pair completes a genuine delivery. Pairs are run in "
            "sequential batches of this size, with each batch's proof app stopped before the "
            "next batch launches, to free BLE connection slots and reduce RF contention. Pass "
            "a higher value (e.g. the full pair count) to intentionally reproduce the "
            "congested-fleet scenario."
        ),
    )
    parser.add_argument(
        "--logcat-tail-lines",
        type=int,
        default=DEFAULT_LOGCAT_TAIL_LINES,
        help=(
            "How many recent logcat lines to scan per device when persisting "
            "MeshLinkReferenceAutomation-tagged logcat evidence to logs/<serial>.logcat.log "
            f"(default: {DEFAULT_LOGCAT_TAIL_LINES}). Increase this if the receive-path evidence "
            "you need scrolled out of the tail during a long --wait-seconds run. Noisy devices "
            "are also auto-retried at a much wider tail if this window captures 0 matching lines "
            "- see capture_full_logcat()."
        ),
    )
    args = parser.parse_args(argv)
    if args.devices and args.pair_labels:
        parser.error("--device cannot be combined with --pair-label")
    if args.devices and len(args.devices) != 2:
        parser.error("--device must be passed exactly twice (initiator, then receiver)")
    if args.max_concurrent_pairs < 1:
        parser.error("--max-concurrent-pairs must be at least 1")
    return args


def run_command(command: list[str], *, check: bool = False) -> subprocess.CompletedProcess[str]:
    # `errors="replace"` is required because `adb logcat` output can legitimately
    # contain non-UTF-8 bytes (e.g. from a native crash dump embedded in a log
    # line), which would otherwise crash the whole fleet run with a
    # UnicodeDecodeError deep inside subprocess output decoding.
    completed = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and completed.returncode != 0:
        raise subprocess.CalledProcessError(
            completed.returncode,
            command,
            output=completed.stdout,
            stderr=completed.stderr,
        )
    return completed


DEVICE_LINE_RE = re.compile(r"^(?P<id>\S+)\s+(?P<state>\S+)(?P<rest>.*)$")
MODEL_TOKEN_RE = re.compile(r"model:(\S+)")
KNOWN_STATES = ("device", "offline", "unauthorized", "no permissions", "recovery", "sideload", "bootloader")
# adb can assign a "name (N)" de-dup suffix (with a literal space) to wireless
# device IDs when it sees a duplicate mDNS advertisement, so the id itself may
# contain whitespace. Split on the first known state keyword instead of the
# first whitespace run to avoid truncating those IDs.
DEVICE_LINE_RE = re.compile(
    r"^(?P<id>.+?)\s+(?P<state>" + "|".join(re.escape(state) for state in KNOWN_STATES) + r")\b(?P<rest>.*)$"
)


def android_devices() -> list[dict[str, Any]]:
    completed = run_command(["adb", "devices", "-l"], check=True)
    devices: list[dict[str, Any]] = []
    for raw_line in completed.stdout.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("List of devices"):
            continue
        match = DEVICE_LINE_RE.match(line)
        if not match:
            continue
        if match.group("state") != "device":
            continue
        model_match = MODEL_TOKEN_RE.search(match.group("rest"))
        devices.append(
            {
                "id": match.group("id"),
                "state": match.group("state"),
                "model": model_match.group(1) if model_match else None,
                "raw": line,
            }
        )
    return devices


@lru_cache(maxsize=None)
def device_info(serial: str) -> dict[str, Any]:
    model = run_command(["adb", "-s", serial, "shell", "getprop", "ro.product.model"], check=True).stdout.strip()
    api_level_raw = run_command(["adb", "-s", serial, "shell", "getprop", "ro.build.version.sdk"], check=True).stdout.strip()
    return {"model": model, "apiLevel": int(api_level_raw) if api_level_raw.isdigit() else 0}


def normalize_android_serial(listed_id: str) -> tuple[str, dict[str, Any]]:
    candidates = [listed_id]
    if listed_id.startswith("adb-") and WIRELESS_SUFFIX not in listed_id:
        candidates.append(f"{listed_id}{WIRELESS_SUFFIX}")
    last_error: Exception | None = None
    for candidate in candidates:
        try:
            info = device_info(candidate)
            return candidate, info
        except Exception as error:  # noqa: BLE001 - we want to try the next candidate.
            last_error = error
    raise RuntimeError(f"Unable to resolve Android device serial {listed_id!r}: {last_error}")


BLUETOOTH_ENABLED_RE = re.compile(r"^\s*enabled:\s*(true|false)\s*$", re.MULTILINE | re.IGNORECASE)
BLUETOOTH_STATE_RE = re.compile(r"^\s*state:\s*([A-Z_]+)\s*$", re.MULTILINE)


def bluetooth_manager_state(serial: str) -> dict[str, Any]:
    completed = run_command(["adb", "-s", serial, "shell", "dumpsys", "bluetooth_manager"])
    stdout = completed.stdout or ""
    enabled_match = BLUETOOTH_ENABLED_RE.search(stdout)
    state_match = BLUETOOTH_STATE_RE.search(stdout)
    return {
        "serial": serial,
        "returncode": completed.returncode,
        "enabled": enabled_match.group(1).lower() == "true" if enabled_match else None,
        "state": state_match.group(1) if state_match else None,
        "stdout": stdout,
        "stderr": completed.stderr,
    }


def ensure_bluetooth_on(device: DeviceRecord) -> dict[str, Any]:
    serial = device.resolved_serial
    before = bluetooth_manager_state(serial)
    action = "already-on"
    if not before["enabled"] or before["state"] != "ON":
        action = "enable"
        run_command(["adb", "-s", serial, "shell", "cmd", "bluetooth_manager", "enable"], check=True)
        run_command(["adb", "-s", serial, "shell", "cmd", "bluetooth_manager", "wait-for-state:STATE_ON"], check=True)
    after = bluetooth_manager_state(serial)
    if after["enabled"] is not True or after["state"] != "ON":
        raise RuntimeError(
            f"Bluetooth preflight failed for {serial}: before={before['state']!r}/{before['enabled']!r} after={after['state']!r}/{after['enabled']!r}"
        )
    return {
        "serial": serial,
        "model": device.model,
        "apiLevel": device.api_level,
        "action": action,
        "before": {"enabled": before["enabled"], "state": before["state"]},
        "after": {"enabled": after["enabled"], "state": after["state"]},
    }


# Matches an exhausted (no further retry) advertise or scan failure diagnostic
# line, e.g.:
#   DIAG DISCOVERY_ADVERTISE_FAILED ... errorName=ADVERTISE_FAILED_TOO_MANY_ADVERTISERS willRetry=false
#   DIAG DISCOVERY_SCAN_FAILED ... errorName=SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES willRetry=false
# See the module docstring for why this happens and how the restart fixes it.
STUCK_BLUETOOTH_STACK_RE = re.compile(
    r"DIAG DISCOVERY_(?:ADVERTISE|SCAN)_FAILED\b.*\bwillRetry=false\b"
)


def proof_log_shows_stuck_bluetooth_stack(proof_log_text: str) -> bool:
    return any(STUCK_BLUETOOTH_STACK_RE.search(line) for line in proof_log_text.splitlines())


def poll_bluetooth_state(
    serial: str, acceptable_states: set[str], *, timeout_seconds: float = 10.0, poll_interval_seconds: float = 0.5
) -> dict[str, Any]:
    """Poll `dumpsys bluetooth_manager` until `state` is one of
    [acceptable_states] or [timeout_seconds] elapses, returning the final
    observed state either way (never raises). Used instead of the
    `bluetooth_manager wait-for-state:...` shell subcommand, which fails
    outright (exit 255) rather than waiting/timing out gracefully when the
    stack settles into a state the subcommand doesn't recognize as terminal
    (see BLE_ON limbo state notes on [restart_bluetooth_stack])."""
    deadline = time.monotonic() + timeout_seconds
    state = bluetooth_manager_state(serial)
    while state["state"] not in acceptable_states and time.monotonic() < deadline:
        time.sleep(poll_interval_seconds)
        state = bluetooth_manager_state(serial)
    return state


_BLUETOOTH_MANAGER_CMD_UNSUPPORTED_MARKER = "No shell command implementation."


def restart_bluetooth_stack_via_airplane_mode(serial: str) -> dict[str, Any]:
    """Recover a device's Bluetooth stack by toggling airplane mode instead of
    `cmd bluetooth_manager`. Used as a fallback when that shell interface is
    unsupported (see [restart_bluetooth_stack]).

    Writing `settings put global airplane_mode_on 1`/`0` directly is enough to
    make the platform's radio-power manager toggle Bluetooth off and back on,
    even without a matching `ACTION_AIRPLANE_MODE_CHANGED` broadcast (shell
    lacks permission to send that broadcast, but the settings write alone
    still triggers the observer that drives the radios). Confirmed live on a
    POCOPHONE F1 (SDK 29) stuck with exhausted advertiser slots where `cmd
    bluetooth_manager disable`/`enable` silently no-op: this path reliably
    drives the state through BLE_TURNING_OFF -> OFF -> BLE_TURNING_ON/
    TURNING_ON -> ON and clears the stuck advertiser registrations.
    """
    before = bluetooth_manager_state(serial)
    run_command(["adb", "-s", serial, "shell", "am", "force-stop", PACKAGE_NAME])
    run_command(["adb", "-s", serial, "shell", "settings", "put", "global", "airplane_mode_on", "1"], check=True)
    off_state = poll_bluetooth_state(serial, {"OFF", "BLE_ON"})
    if off_state["state"] not in {"OFF", "BLE_ON"}:
        raise RuntimeError(
            f"Airplane-mode Bluetooth restart failed to reach OFF/BLE_ON for {serial}: "
            f"before={before['state']!r}/{before['enabled']!r} stuckAt={off_state['state']!r}/{off_state['enabled']!r}"
        )
    run_command(["adb", "-s", serial, "shell", "settings", "put", "global", "airplane_mode_on", "0"], check=True)
    after = poll_bluetooth_state(serial, {"ON"})
    if after["enabled"] is not True or after["state"] != "ON":
        raise RuntimeError(
            f"Airplane-mode Bluetooth restart failed to reach ON for {serial}: "
            f"before={before['state']!r}/{before['enabled']!r} after={after['state']!r}/{after['enabled']!r}"
        )
    return {
        "serial": serial,
        "method": "airplane_mode",
        "before": {"enabled": before["enabled"], "state": before["state"]},
        "intermediate": {"enabled": off_state["enabled"], "state": off_state["state"]},
        "after": {"enabled": after["enabled"], "state": after["state"]},
    }


def restart_bluetooth_stack(serial: str) -> dict[str, Any]:
    """Recover a device whose Bluetooth stack is holding stuck advertiser/scanner
    slots by fully disabling then re-enabling it (see module docstring).

    Hardened against the BLE_ON limbo state: after `disable`, a device can
    settle into `state=BLE_ON` (Android's "classic Bluetooth is off but a
    BLE-only client is still registered" hybrid state) instead of fully `OFF`,
    if some component still holds an active BLE scan/advertise/GATT
    registration when `disable` is issued. The most likely holder is the proof
    app itself, so it is force-stopped before `disable` to release its own
    registrations. `wait-for-state:STATE_OFF` treats anything other than a
    literal `OFF` transition as a hard failure (exit 255) rather than timing
    out gracefully, so this uses [poll_bluetooth_state] instead and accepts
    `BLE_ON` as an equally valid "disabled enough" outcome - re-enabling from
    `BLE_ON` works exactly the same as from `OFF`. See
    docs/explanation/reference-app-physical-integration-findings.md for the
    full investigation.

    Also detects a distinct, unrelated failure mode: on some devices
    (observed on Xiaomi/POCO/Samsung/Huawei models at API level <= 31 in this
    fleet, e.g. POCOPHONE F1 SDK 29) the `cmd bluetooth_manager` shell
    interface itself is not implemented at all - `adb shell cmd
    bluetooth_manager disable` exits 0 but prints "No shell command
    implementation." and never changes the Bluetooth state. Left undetected,
    this looks identical to the BLE_ON limbo state (both eventually report
    "stuck at ON") but has a completely different root cause and cannot be
    recovered by retrying the same command. This is checked for immediately
    after issuing `disable`/`enable`, and falls back to
    [restart_bluetooth_stack_via_airplane_mode] instead of failing outright -
    that path does not depend on `cmd bluetooth_manager` at all and was
    confirmed to work on POCOPHONE F1.
    """
    before = bluetooth_manager_state(serial)
    run_command(["adb", "-s", serial, "shell", "am", "force-stop", PACKAGE_NAME])
    disable_result = run_command(["adb", "-s", serial, "shell", "cmd", "bluetooth_manager", "disable"], check=True)
    if _BLUETOOTH_MANAGER_CMD_UNSUPPORTED_MARKER in (disable_result.stdout or "") or _BLUETOOTH_MANAGER_CMD_UNSUPPORTED_MARKER in (disable_result.stderr or ""):
        return restart_bluetooth_stack_via_airplane_mode(serial)
    off_state = poll_bluetooth_state(serial, {"OFF", "BLE_ON"})
    if off_state["state"] not in {"OFF", "BLE_ON"}:
        raise RuntimeError(
            f"Bluetooth restart failed to reach OFF/BLE_ON for {serial}: "
            f"before={before['state']!r}/{before['enabled']!r} stuckAt={off_state['state']!r}/{off_state['enabled']!r}"
        )
    enable_result = run_command(["adb", "-s", serial, "shell", "cmd", "bluetooth_manager", "enable"], check=True)
    if _BLUETOOTH_MANAGER_CMD_UNSUPPORTED_MARKER in (enable_result.stdout or "") or _BLUETOOTH_MANAGER_CMD_UNSUPPORTED_MARKER in (enable_result.stderr or ""):
        return restart_bluetooth_stack_via_airplane_mode(serial)
    after = poll_bluetooth_state(serial, {"ON"})
    if after["enabled"] is not True or after["state"] != "ON":
        raise RuntimeError(
            f"Bluetooth restart failed to reach ON for {serial}: "
            f"before={before['state']!r}/{before['enabled']!r} after={after['state']!r}/{after['enabled']!r}"
        )
    return {
        "serial": serial,
        "before": {"enabled": before["enabled"], "state": before["state"]},
        "intermediate": {"enabled": off_state["enabled"], "state": off_state["state"]},
        "after": {"enabled": after["enabled"], "state": after["state"]},
    }



DUP_SUFFIX_RE = re.compile(r"\s+\(\d+\)(?=\._adb-tls-connect\._tcp$)")


def collect_devices() -> list[DeviceRecord]:
    seen: set[str] = set()
    records: list[DeviceRecord] = []
    entries = android_devices()
    # adb assigns a "id (N)" de-dup suffix to a wireless device when it has more
    # than one mDNS registration for the same physical device. Prefer the
    # canonical (unsuffixed) id when both are present so we don't double-count
    # a single physical device as two fleet entries.
    listed_ids = {str(entry.get("id") or "").strip() for entry in entries}
    for entry in entries:
        listed_id = str(entry.get("id") or "").strip()
        if not listed_id:
            continue
        canonical_id = DUP_SUFFIX_RE.sub("", listed_id)
        if canonical_id != listed_id and canonical_id in listed_ids:
            continue
        try:
            resolved_serial, info = normalize_android_serial(listed_id)
        except RuntimeError as error:
            print(f"[warn] skipping unresolved Android device {listed_id!r}: {error}", file=sys.stderr)
            continue
        if resolved_serial in seen:
            continue
        seen.add(resolved_serial)
        records.append(
            DeviceRecord(
                listed_id=listed_id,
                resolved_serial=resolved_serial,
                model=str(info.get("model") or entry.get("model") or listed_id),
                api_level=int(info.get("apiLevel") or 0),
                raw=str(entry.get("raw") or ""),
            )
        )
    return records


def cross_generation_pairs(devices: list[DeviceRecord]) -> list[PairRecord]:
    ordered = sorted(devices, key=lambda device: (-device.api_level, device.resolved_serial))
    pairs: list[PairRecord] = []
    half = len(ordered) // 2
    for index in range(half):
        initiator = ordered[index]
        receiver = ordered[-(index + 1)]
        initiator_slug = slugify(initiator.model)
        receiver_slug = slugify(receiver.model)
        label = f"{initiator_slug}__{receiver_slug}" if initiator_slug != receiver_slug else f"{initiator_slug}_{index + 1}"
        pairs.append(PairRecord(label=label, initiator=initiator, receiver=receiver))
    return pairs


def slugify(value: str) -> str:
    normalized = re.sub(r"[^a-z0-9]+", "_", value.lower()).strip("_")
    return normalized or "device"


def check_apk_freshness() -> str | None:
    """Warn loudly if the APK on disk predates the newest relevant source commit.

    AGENTS.md requires a fresh `./gradlew :meshlink-proof:android:assembleDebug`
    plus `adb uninstall` before every physical-device proof run - stale builds
    silently miss recent fixes and produce misleading "real hardware"
    evidence (see meshlink-benchmark/history.md, 2026-07-09 stale-build
    false-negative entry, and the 2026-07-10 repeat of the same mistake
    against this exact script). This check cannot force a rebuild (the APK
    may be intentionally pinned for a specific investigation), but it makes
    forgetting the step impossible to miss in the run output.
    """
    if not APK.exists():
        return (
            f"APK not found at {APK}. Build it first: "
            "./gradlew :meshlink-proof:android:assembleDebug"
        )
    try:
        latest_commit_epoch = float(
            subprocess.run(
                ["git", "log", "-1", "--format=%ct", "--", "meshlink-proof", "meshlink"],
                cwd=ROOT,
                capture_output=True,
                text=True,
                check=True,
            ).stdout.strip()
        )
    except (subprocess.CalledProcessError, ValueError, OSError):
        return None
    apk_mtime = APK.stat().st_mtime
    if apk_mtime < latest_commit_epoch:
        apk_age = datetime.fromtimestamp(apk_mtime, tz=timezone.utc).isoformat()
        commit_age = datetime.fromtimestamp(latest_commit_epoch, tz=timezone.utc).isoformat()
        return (
            f"STALE APK: {APK} was built {apk_age}, but source under "
            f"meshlink-proof/ or meshlink/ was committed as recently as {commit_age}. "
            "Rebuild before trusting this run: "
            "./gradlew :meshlink-proof:android:assembleDebug -- then uninstall the "
            f"previous install on every target device (adb -s <serial> uninstall {PACKAGE_NAME}) "
            "before rerunning. See AGENTS.md's physical-device rule."
        )
    return None


def install_apk(serial: str) -> dict[str, Any]:
    completed = run_command(["adb", "-s", serial, "install", "-r", str(APK)])
    return {
        "serial": serial,
        "returncode": completed.returncode,
        "stdout": completed.stdout,
        "stderr": completed.stderr,
    }


def grant_permissions(device: DeviceRecord) -> dict[str, Any]:
    serial = device.resolved_serial
    permissions = ["android.permission.ACCESS_FINE_LOCATION"]
    if device.api_level >= 31:
        permissions = [
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_ADVERTISE",
        ]
    steps: list[dict[str, Any]] = []
    clear_result = run_command(["adb", "-s", serial, "shell", "pm", "clear", PACKAGE_NAME])
    steps.append(
        {
            "step": "clear",
            "command": clear_result.args,
            "returncode": clear_result.returncode,
            "stdout": clear_result.stdout,
            "stderr": clear_result.stderr,
        }
    )
    for permission in permissions:
        grant_result = run_command(["adb", "-s", serial, "shell", "pm", "grant", PACKAGE_NAME, permission])
        steps.append(
            {
                "step": f"grant:{permission}",
                "command": grant_result.args,
                "returncode": grant_result.returncode,
                "stdout": grant_result.stdout,
                "stderr": grant_result.stderr,
            }
        )
    return {"serial": serial, "steps": steps}


def launch_app(serial: str, *, app_id: str, power_mode: str, initiator: bool, payload_bytes: int) -> dict[str, Any]:
    command = [
        "adb",
        "-s",
        serial,
        "shell",
        "am",
        "start",
        "-n",
        f"{PACKAGE_NAME}/{ACTIVITY_NAME}",
        "--es",
        "meshlink.appId",
        app_id,
        "--es",
        "meshlink.powerMode",
        power_mode,
    ]
    if initiator:
        command.extend([
            "--ei",
            "meshlink.benchmarkPayloadBytes",
            str(payload_bytes),
            "--ez",
            "meshlink.forceInitiator",
            "true",
        ])
    completed = run_command(command)
    return {
        "serial": serial,
        "initiator": initiator,
        "returncode": completed.returncode,
        "stdout": completed.stdout,
        "stderr": completed.stderr,
        "command": command,
    }


def capture_proof_log(serial: str, *, run_root: Path) -> dict[str, Any]:
    completed = run_command(["adb", "-s", serial, "exec-out", "run-as", PACKAGE_NAME, "cat", "files/proof.log"])
    proof_text = completed.stdout if completed.stdout else completed.stderr or "(no proof log output)\n"
    proof_path = run_root / "logs" / f"{serial}.proof.log"
    proof_path.write_text(proof_text, encoding="utf-8")
    return {
        "serial": serial,
        "returncode": completed.returncode,
        "path": str(proof_path),
        "bytes": len(proof_text.encode("utf-8")),
    }


def capture_transport_mode(serial: str) -> dict[str, Any]:
    completed = run_command(["adb", "-s", serial, "logcat", "-d", "-t", "2000"])
    text = completed.stdout + ("\n" + completed.stderr if completed.stderr else "")
    for line in text.splitlines():
        if "MeshLinkReferenceAutomation:" not in line:
            continue
        match = MESH_MODE_RE.search(line) or TRANSPORT_RE.search(line)
        if match:
            return {"serial": serial, "mode": match.group(1), "line": line}
    return {"serial": serial, "mode": None, "line": None}


def _read_meshlink_logcat_lines(serial: str, tail_lines: int) -> list[str]:
    completed = run_command(["adb", "-s", serial, "logcat", "-d", "-t", str(tail_lines)])
    text = completed.stdout + ("\n" + completed.stderr if completed.stderr else "")
    return [line for line in text.splitlines() if "MeshLinkReferenceAutomation:" in line]


def capture_full_logcat(serial: str, *, run_root: Path, tail_lines: int) -> dict[str, Any]:
    """Persist every `MeshLinkReferenceAutomation`-tagged logcat line to disk.

    `proof.log` only records the diagnostics the proof app explicitly writes
    (mesh state, hop sessions, delivery). Lower-level transport detail -
    including the Android BLE/L2CAP receive-path logging that is the only
    evidence for diagnosing a missing inbound delivery - is only ever written
    to Android's own logcat under this tag. capture_transport_mode() reads
    that logcat but discards every line that isn't a mode/transport match;
    this function keeps the full filtered stream so it survives the run.

    A noisy device (heavy non-MeshLink log traffic from other apps/system
    services) can push every MeshLinkReferenceAutomation-tagged line out of
    the last [tail_lines] lines entirely, producing a silent, misleading
    0-line capture that looks like "the app logged nothing" when it actually
    logged plenty - just further back in the buffer than we looked. This was
    the reason a real CPH2359<->DN2103 BLE-scan-miss bug went undiagnosed for
    a while: the default 4000-line tail captured 0 matching lines on the
    noisy CPH2359 device, and only widening it (`--logcat-tail-lines 40000`)
    revealed the actual evidence. So: if the requested tail_lines produced no
    matching lines at all, automatically retry once with a much wider tail
    (`LOGCAT_ZERO_LINE_RETRY_TAIL_LINES`) before concluding the device really
    emitted nothing.
    """
    filtered_lines = _read_meshlink_logcat_lines(serial, tail_lines)
    widened = False
    if not filtered_lines and tail_lines < LOGCAT_ZERO_LINE_RETRY_TAIL_LINES:
        filtered_lines = _read_meshlink_logcat_lines(serial, LOGCAT_ZERO_LINE_RETRY_TAIL_LINES)
        widened = True
    filtered_text = "\n".join(filtered_lines) + ("\n" if filtered_lines else "")
    if not filtered_text:
        filtered_text = "(no MeshLinkReferenceAutomation logcat lines captured)\n"
    logcat_path = run_root / "logs" / f"{serial}.logcat.log"
    logcat_path.write_text(filtered_text, encoding="utf-8")
    return {
        "serial": serial,
        "path": str(logcat_path),
        "lines": len(filtered_lines),
        "bytes": len(filtered_text.encode("utf-8")),
        "widenedTailLines": LOGCAT_ZERO_LINE_RETRY_TAIL_LINES if widened else None,
    }


def stop_proof_app(serial: str) -> dict[str, Any]:
    """Force-stop the proof app on a device so it never keeps scanning,
    advertising, or holding GATT connections/foreground services after a run
    finishes. Without this, a device from an earlier run keeps interfering
    with later isolated reruns (stray cross-app-id BLE traffic, occupied GATT
    server slots) even though it is no longer part of the selected devices.

    Always called from a `finally` block for every exercised device, so this
    must never raise: a single device's adb hiccup must not prevent any other
    device from being force-stopped.
    """
    try:
        completed = run_command(["adb", "-s", serial, "shell", "am", "force-stop", PACKAGE_NAME])
        return {"serial": serial, "returncode": completed.returncode}
    except Exception as error:  # noqa: BLE001 - cleanup must never raise.
        return {"serial": serial, "returncode": None, "error": str(error)}


_OWN_KEY_HASH_RE = re.compile(r"\bkeyHash=([0-9a-fA-F]+)")


def _extract_own_key_hash(log_lines: list[str]) -> str | None:
    """Extract a device's own peer identity from its proof.log startup line.

    Every proof.log begins with a line like `... keyHash=<hex>`, printed once at
    app start. Other devices refer to this identity two ways in their own logs:
    the full hex value in `DIAG`-style structured lines (`peerId=<hex>`,
    `destinationPeerId=<hex>`, `originPeerId=<hex>`), and the last 6 hex
    characters (`peerId.value.takeLast(6)`) in free-text lines like
    `auto-send attempt 1 -> Sent for <hint>` or `BENCHMARK receipt sent
    peer=<hint>`. See summarize_pair() for why this matters.
    """
    for line in log_lines:
        match = _OWN_KEY_HASH_RE.search(line)
        if match:
            return match.group(1)
    return None


def _run_pair_batch(
    pairs_batch: list["PairRecord"],
    *,
    run_root: Path,
    app_id: str,
    args: argparse.Namespace,
) -> dict[str, Any]:
    """Launch, wait, capture, and auto-recover exactly one batch of pairs.

    Only the devices belonging to `pairs_batch` are launched simultaneously -
    not the whole fleet. This keeps BLE airtime/connection-slot pressure low
    enough for handshakes to actually complete (see DEFAULT_MAX_CONCURRENT_PAIRS).
    The proof app is always stopped on this batch's devices before returning,
    per device, regardless of success or failure, so a later batch does not
    inherit stray BLE scanning/advertising/GATT connections from this one.
    """
    batch_devices: list[DeviceRecord] = []
    seen_serials: set[str] = set()
    for pair in pairs_batch:
        for device in (pair.initiator, pair.receiver):
            if device.resolved_serial not in seen_serials:
                seen_serials.add(device.resolved_serial)
                batch_devices.append(device)

    launch_results: list[dict[str, Any]] = []
    capture_results: list[dict[str, Any]] = []
    mode_results: list[dict[str, Any]] = []
    logcat_results: list[dict[str, Any]] = []
    bluetooth_recovery_actions: list[dict[str, Any]] = []
    pair_rows: list[dict[str, Any]] = []

    try:
        with ThreadPoolExecutor(max_workers=max(2, len(pairs_batch) * 2)) as executor:
            futures = []
            for pair in pairs_batch:
                futures.append(executor.submit(launch_app, pair.initiator.resolved_serial, app_id=app_id, power_mode=args.power_mode, initiator=True, payload_bytes=args.payload_bytes))
                futures.append(executor.submit(launch_app, pair.receiver.resolved_serial, app_id=app_id, power_mode=args.power_mode, initiator=False, payload_bytes=args.payload_bytes))
            for future in as_completed(futures):
                launch_results.append(future.result())

        time.sleep(args.wait_seconds)

        with ThreadPoolExecutor(max_workers=min(len(batch_devices), 16)) as executor:
            capture_results = list(executor.map(lambda device: capture_proof_log(device.resolved_serial, run_root=run_root), batch_devices))

        with ThreadPoolExecutor(max_workers=min(len(batch_devices), 16)) as executor:
            mode_results = list(executor.map(lambda device: capture_transport_mode(device.resolved_serial), batch_devices))

        with ThreadPoolExecutor(max_workers=min(len(batch_devices), 16)) as executor:
            logcat_results = list(
                executor.map(
                    lambda device: capture_full_logcat(device.resolved_serial, run_root=run_root, tail_lines=args.logcat_tail_lines),
                    batch_devices,
                )
            )

        mode_map = {result["serial"]: result["mode"] for result in mode_results}
        pair_rows = [summarize_pair(pair, run_root, mode_map) for pair in pairs_batch]

        # Auto-recovery: a device whose proof.log shows an exhausted advertise/scan
        # retry budget almost certainly has a stuck Bluetooth stack (see module
        # docstring). Restart it, relaunch every pair in this batch that includes
        # it, and recapture logs/transport mode once before finalizing.
        device_by_serial = {device.resolved_serial: device for device in batch_devices}
        if args.auto_recover_bluetooth:
            stuck_serials = {
                device.resolved_serial
                for device in batch_devices
                if proof_log_shows_stuck_bluetooth_stack(
                    (run_root / "logs" / f"{device.resolved_serial}.proof.log").read_text(
                        encoding="utf-8", errors="ignore"
                    )
                )
            }
            if stuck_serials:
                with ThreadPoolExecutor(max_workers=min(len(stuck_serials), 16)) as executor:
                    restart_results = list(executor.map(restart_bluetooth_stack, sorted(stuck_serials)))
                for restart_result, serial in zip(restart_results, sorted(stuck_serials)):
                    device = device_by_serial[serial]
                    bluetooth_recovery_actions.append(
                        {
                            "serial": serial,
                            "model": device.model,
                            "reason": "exhausted DISCOVERY_ADVERTISE_FAILED/DISCOVERY_SCAN_FAILED retries",
                            "before": restart_result["before"],
                            "after": restart_result["after"],
                        }
                    )

                affected_pairs = [
                    pair
                    for pair in pairs_batch
                    if pair.initiator.resolved_serial in stuck_serials or pair.receiver.resolved_serial in stuck_serials
                ]
                affected_serials = sorted(
                    {pair.initiator.resolved_serial for pair in affected_pairs}
                    | {pair.receiver.resolved_serial for pair in affected_pairs}
                )

                with ThreadPoolExecutor(max_workers=max(2, len(affected_pairs) * 2)) as executor:
                    retry_futures = []
                    for pair in affected_pairs:
                        retry_futures.append(executor.submit(launch_app, pair.initiator.resolved_serial, app_id=app_id, power_mode=args.power_mode, initiator=True, payload_bytes=args.payload_bytes))
                        retry_futures.append(executor.submit(launch_app, pair.receiver.resolved_serial, app_id=app_id, power_mode=args.power_mode, initiator=False, payload_bytes=args.payload_bytes))
                    for future in as_completed(retry_futures):
                        launch_results.append(future.result())

                time.sleep(args.wait_seconds)

                with ThreadPoolExecutor(max_workers=min(len(affected_serials), 16)) as executor:
                    retry_capture_results = list(executor.map(lambda serial: capture_proof_log(serial, run_root=run_root), affected_serials))
                capture_results.extend(retry_capture_results)

                with ThreadPoolExecutor(max_workers=min(len(affected_serials), 16)) as executor:
                    retry_mode_results = list(executor.map(capture_transport_mode, affected_serials))
                mode_results.extend(retry_mode_results)
                mode_map.update({result["serial"]: result["mode"] for result in retry_mode_results})

                with ThreadPoolExecutor(max_workers=min(len(affected_serials), 16)) as executor:
                    retry_logcat_results = list(
                        executor.map(
                            lambda serial: capture_full_logcat(serial, run_root=run_root, tail_lines=args.logcat_tail_lines),
                            affected_serials,
                        )
                    )
                logcat_results.extend(retry_logcat_results)

                recovered_rows = {pair.label: summarize_pair(pair, run_root, mode_map) for pair in affected_pairs}
                pair_rows = [recovered_rows.get(row["label"], row) for row in pair_rows]
    finally:
        with ThreadPoolExecutor(max_workers=min(len(batch_devices), 16)) as executor:
            list(executor.map(stop_proof_app, [device.resolved_serial for device in batch_devices]))

    return {
        "launchResults": launch_results,
        "captureResults": capture_results,
        "modeResults": mode_results,
        "logcatResults": logcat_results,
        "bluetoothRecoveryActions": bluetooth_recovery_actions,
        "pairRows": pair_rows,
    }


def canonical_transport_label(modes: list[str | None]) -> str:
    candidates = [mode for mode in modes if mode]
    if not candidates:
        return "unknown"
    if len(candidates) == 2 and all("L2CAP" in mode for mode in candidates):
        return "L2CAP"
    return "GATT"


def summarize_pair(pair: PairRecord, run_root: Path, mode_map: dict[str, str | None]) -> dict[str, Any]:
    initiator_log = (run_root / "logs" / f"{pair.initiator.resolved_serial}.proof.log").read_text(encoding="utf-8", errors="ignore").splitlines()
    receiver_log = (run_root / "logs" / f"{pair.receiver.resolved_serial}.proof.log").read_text(encoding="utf-8", errors="ignore").splitlines()

    # In a full-fleet run with 3+ devices, every phone can hear BLE traffic
    # from *other* pairs as well as its own assigned partner. Naively scanning
    # for generic marker strings anywhere in either device's log (the original
    # approach) can pick up unrelated cross-talk and report false-positive
    # ACK/receipt evidence for a pair whose actual handshake failed. Scope the
    # search to lines that also reference the *other side's own peer identity*
    # (full keyHash for structured DIAG lines, or its last-6-char hint form for
    # free-text lines) so evidence is only counted if it truly involves this
    # pair. See docs/explanation/reference-app-physical-integration-findings.md
    # for the investigation that found this (motorola edge 30 fusion <->
    # CPH2385: report showed "ACK: yes" from an unrelated DN2103 delivery that
    # CPH2385's proof.log happened to also contain).
    initiator_own_hash = _extract_own_key_hash(initiator_log)
    receiver_own_hash = _extract_own_key_hash(receiver_log)

    def scoped_lines(log_lines: list[str], markers: tuple[str, ...], other_own_hash: str | None) -> list[str]:
        matches = [line for line in log_lines if any(marker in line for marker in markers)]
        if not other_own_hash:
            # Could not establish the other side's identity (e.g. a truncated
            # or missing startup line) - fall back to unscoped matching rather
            # than silently reporting no evidence at all.
            return matches
        other_hint = other_own_hash[-6:]
        return [line for line in matches if other_own_hash in line or other_hint in line]

    ack_markers = ("DELIVERY_SUCCEEDED", "auto-send attempt 1 -> Sent", "SendResult.Sent")
    receipt_markers = ("BENCHMARK receipt sent", "BENCHMARK receipt confirmed")
    receipt_failure_markers = ("BENCHMARK receipt timeout", "BENCHMARK receipt failed")

    ack_lines = scoped_lines(initiator_log, ack_markers, receiver_own_hash) + scoped_lines(
        receiver_log, ack_markers, initiator_own_hash
    )
    receipt_lines = scoped_lines(initiator_log, receipt_markers, receiver_own_hash) + scoped_lines(
        receiver_log, receipt_markers, initiator_own_hash
    )
    receipt_failure_lines = scoped_lines(initiator_log, receipt_failure_markers, receiver_own_hash) + scoped_lines(
        receiver_log, receipt_failure_markers, initiator_own_hash
    )

    transport_modes = [mode_map.get(pair.initiator.resolved_serial), mode_map.get(pair.receiver.resolved_serial)]
    return {
        "label": pair.label,
        "initiator": {
            "serial": pair.initiator.resolved_serial,
            "model": pair.initiator.model,
            "apiLevel": pair.initiator.api_level,
        },
        "receiver": {
            "serial": pair.receiver.resolved_serial,
            "model": pair.receiver.model,
            "apiLevel": pair.receiver.api_level,
        },
        "transportMode": canonical_transport_label(transport_modes),
        "transportModes": sorted({mode for mode in transport_modes if mode}),
        "ackLines": ack_lines[:8],
        "receiptLines": receipt_lines[:8],
        "receiptFailureLines": receipt_failure_lines[:8],
        # True only when *both* devices' own keyHash startup lines were found,
        # meaning ack/receipt evidence above was scoped to this pair's actual
        # peer identities. False means one side's startup line was missing
        # (e.g. truncated by logcat buffer wraparound in a busy fleet run) and
        # matching silently fell back to unscoped search for that direction -
        # evidence should be treated with reduced confidence in that case.
        "evidenceVerified": bool(initiator_own_hash) and bool(receiver_own_hash),
        "initiatorOwnKeyHash": initiator_own_hash,
        "receiverOwnKeyHash": receiver_own_hash,
        "initiatorLog": str(run_root / "logs" / f"{pair.initiator.resolved_serial}.proof.log"),
        "receiverLog": str(run_root / "logs" / f"{pair.receiver.resolved_serial}.proof.log"),
    }


def render_compact_summary(summary: dict[str, Any]) -> str:
    lines: list[str] = []
    lines.append("# MeshLink proof-app compact summary")
    lines.append("")
    lines.append(f"- Run root: `{summary['runRoot']}`")
    lines.append(f"- App ID: `{summary['appId']}`")
    lines.append(f"- Fleet devices discovered: `{summary.get('fleetDeviceCount', len(summary.get('devices', [])))}`")
    lines.append(f"- Devices exercised: `{summary.get('deviceCount', len(summary.get('selectedDevices', [])))}`")
    lines.append(f"- Pairs: `{summary.get('pairCount', len(summary.get('pairs', [])))}`")
    lines.append(f"- Power mode: `{summary['powerMode']}`")
    lines.append(f"- Payload bytes: `{summary['payloadBytes']}`")
    lines.append(f"- Bluetooth preflight: `{len(summary.get('bluetoothChecks', []))}` devices checked")
    lines.append("")
    lines.append("## Pairs")
    lines.append("")
    lines.append("| Pair | Initiator | Receiver | Transport | ACK evidence | Receipt evidence | Peer identity verified |")
    lines.append("|---|---|---|---|---|---|---|")
    for row in summary["pairRows"]:
        transport = row.get("transportMode") or "unknown"
        ack = "yes" if row["ackLines"] else "no"
        receipt = "yes" if row["receiptLines"] else "no"
        verified = "yes" if row.get("evidenceVerified") else "no (reduced confidence)"
        lines.append(
            f"| {row['label']} | {row['initiator']['model']} | {row['receiver']['model']} | {transport} | {ack} | {receipt} | {verified} |"
        )
    lines.append("")
    lines.append("## Bluetooth preflight")
    lines.append("")
    for check in summary.get("bluetoothChecks", []):
        before = check.get("before", {})
        after = check.get("after", {})
        lines.append(
            f"- {check['serial']}: before={before.get('enabled')}/{before.get('state')} after={after.get('enabled')}/{after.get('state')} action={check.get('action')}"
        )
    recovery_actions = summary.get("bluetoothRecoveryActions", [])
    if recovery_actions:
        lines.append("")
        lines.append("## Bluetooth stack recovery")
        lines.append("")
        lines.append(
            "The following device(s) showed exhausted advertise/scan retries "
            "(`willRetry=false`) in proof.log, so their Bluetooth stack was "
            "restarted and the affected pair(s) were relaunched and recaptured."
        )
        lines.append("")
        for action in recovery_actions:
            before = action.get("before", {})
            after = action.get("after", {})
            lines.append(
                f"- {action['serial']} ({action['model']}): reason={action['reason']} "
                f"before={before.get('enabled')}/{before.get('state')} after={after.get('enabled')}/{after.get('state')}"
            )
    batch_errors = summary.get("batchErrors", [])
    if batch_errors:
        lines.append("")
        lines.append("## Batch errors")
        lines.append("")
        lines.append(
            "The following pair batch(es) raised an unrecoverable error (e.g. a device "
            "stuck mid-Bluetooth-restart) and were skipped; their proof apps were still "
            "stopped, but no evidence was captured for the pairs listed. All other "
            "batches ran unaffected."
        )
        lines.append("")
        for batch_error in batch_errors:
            lines.append(f"- {', '.join(batch_error['pairLabels'])}: {batch_error['error']}")
    logcat_results = summary.get("logcatResults", [])
    if logcat_results:
        lines.append("")
        lines.append("## Logcat evidence")
        lines.append("")
        lines.append(
            "Full `MeshLinkReferenceAutomation`-tagged logcat per device, including the "
            "BLE/L2CAP receive-path detail that proof.log does not capture "
            "(see `--logcat-tail-lines` to widen the capture window)."
        )
        lines.append("")
        for result in logcat_results:
            lines.append(f"- {result['serial']}: `{result['path']}` ({result['lines']} lines)")
    return "\n".join(lines)



def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    apk_freshness_warning = check_apk_freshness()
    if apk_freshness_warning:
        print(f"WARNING: {apk_freshness_warning}", file=sys.stderr)
    timestamp_value = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    run_root = Path(args.run_root) if args.run_root else DEFAULT_RUN_ROOT / timestamp_value
    run_root.mkdir(parents=True, exist_ok=True)
    (run_root / "logs").mkdir(exist_ok=True)

    app_id = args.app_id or f"demo.meshlink.proof.{timestamp_value}"

    devices = collect_devices()
    all_pairs = cross_generation_pairs(devices)
    if args.devices:
        device_lookup = {device.resolved_serial: device for device in devices}
        device_lookup.update({device.listed_id: device for device in devices})
        resolved: list[DeviceRecord] = []
        for token in args.devices:
            device = device_lookup.get(token)
            if device is None:
                try:
                    listed_id, info = normalize_android_serial(token)
                except RuntimeError as error:
                    raise SystemExit(f"Unknown --device {token!r}: {error}")
                device = DeviceRecord(
                    listed_id=listed_id,
                    resolved_serial=listed_id,
                    model=str(info.get("model") or token),
                    api_level=int(info.get("apiLevel") or 0),
                    raw="",
                )
            resolved.append(device)
        initiator, receiver = resolved
        initiator_slug = slugify(initiator.model)
        receiver_slug = slugify(receiver.model)
        label = f"{initiator_slug}__{receiver_slug}" if initiator_slug != receiver_slug else f"{initiator_slug}_pair"
        selected_pairs = [PairRecord(label=label, initiator=initiator, receiver=receiver)]
    elif args.pair_labels:
        pair_lookup = {pair.label: pair for pair in all_pairs}
        missing = [label for label in args.pair_labels if label not in pair_lookup]
        if missing:
            raise SystemExit(f"Unknown pair label(s): {', '.join(missing)}")
        selected_pairs = [pair_lookup[label] for label in args.pair_labels]
    else:
        selected_pairs = all_pairs
    selected_serials = sorted({pair.initiator.resolved_serial for pair in selected_pairs} | {pair.receiver.resolved_serial for pair in selected_pairs})
    selected_devices = [device for device in devices if device.resolved_serial in selected_serials]

    inventory = {
        "runRoot": str(run_root),
        "capturedAt": datetime.now(timezone.utc).isoformat(),
        "appId": app_id,
        "apk": str(APK),
        "apkFreshnessWarning": apk_freshness_warning,
        "package": PACKAGE_NAME,
        "activity": ACTIVITY_NAME,
        "powerMode": args.power_mode,
        "payloadBytes": args.payload_bytes,
        "waitSeconds": args.wait_seconds,
        "fleetDeviceCount": len(devices),
        "deviceCount": len(selected_devices),
        "pairCount": len(selected_pairs),
        "selectedPairLabels": [pair.label for pair in selected_pairs],
        "devices": [
            {
                "listedId": device.listed_id,
                "resolvedSerial": device.resolved_serial,
                "model": device.model,
                "apiLevel": device.api_level,
                "raw": device.raw,
            }
            for device in devices
        ],
        "selectedDevices": [
            {
                "listedId": device.listed_id,
                "resolvedSerial": device.resolved_serial,
                "model": device.model,
                "apiLevel": device.api_level,
                "raw": device.raw,
            }
            for device in selected_devices
        ],
        "pairs": [
            {
                "label": pair.label,
                "initiator": pair.initiator.resolved_serial,
                "receiver": pair.receiver.resolved_serial,
            }
            for pair in selected_pairs
        ],
    }
    (run_root / "inventory.json").write_text(json.dumps(inventory, indent=2) + "\n", encoding="utf-8")

    install_results: list[dict[str, Any]] = []
    prep_results: list[dict[str, Any]] = []
    bluetooth_checks: list[dict[str, Any]] = []
    launch_results: list[dict[str, Any]] = []
    capture_results: list[dict[str, Any]] = []
    mode_results: list[dict[str, Any]] = []
    logcat_results: list[dict[str, Any]] = []
    bluetooth_recovery_actions: list[dict[str, Any]] = []
    pair_rows: list[dict[str, Any]] = []
    batch_errors: list[dict[str, Any]] = []

    try:
        with ThreadPoolExecutor(max_workers=min(len(selected_devices), 16)) as executor:
            install_results = list(executor.map(install_apk, [device.resolved_serial for device in selected_devices]))

        with ThreadPoolExecutor(max_workers=min(len(selected_devices), 16)) as executor:
            prep_results = list(executor.map(grant_permissions, selected_devices))

        with ThreadPoolExecutor(max_workers=min(len(selected_devices), 16)) as executor:
            bluetooth_checks = list(executor.map(ensure_bluetooth_on, selected_devices))

        # Run pairs in sequential batches rather than all at once. See
        # DEFAULT_MAX_CONCURRENT_PAIRS / --max-concurrent-pairs: launching every
        # pair simultaneously floods the shared BLE spectrum and connection
        # slots, causing peers to flap found/lost within milliseconds and
        # handshakes to fail with corrupted crypto payloads.
        for batch_start in range(0, len(selected_pairs), args.max_concurrent_pairs):
            pairs_batch = selected_pairs[batch_start : batch_start + args.max_concurrent_pairs]
            try:
                batch_results = _run_pair_batch(pairs_batch, run_root=run_root, app_id=app_id, args=args)
            except Exception as error:  # noqa: BLE001 - one batch's failure (e.g. a
                # device stuck mid-Bluetooth-restart) must not sink every other
                # batch's already-collected evidence. Record it and keep going;
                # _run_pair_batch's own finally block has already stopped this
                # batch's proof apps before the exception reached here.
                batch_errors.append(
                    {
                        "pairLabels": [pair.label for pair in pairs_batch],
                        "error": str(error),
                    }
                )
                continue
            launch_results.extend(batch_results["launchResults"])
            capture_results.extend(batch_results["captureResults"])
            mode_results.extend(batch_results["modeResults"])
            logcat_results.extend(batch_results["logcatResults"])
            bluetooth_recovery_actions.extend(batch_results["bluetoothRecoveryActions"])
            pair_rows.extend(batch_results["pairRows"])
    finally:
        # Safety net: even though each batch already stops its own devices
        # (per device, regardless of that batch's success/failure), make sure
        # every selected device is stopped here too in case a device never
        # reached a batch (e.g. install/prep/Bluetooth-preflight raised before
        # any batch ran). A device left running keeps scanning/advertising/
        # holding GATT connections in the background and interferes with
        # later runs.
        with ThreadPoolExecutor(max_workers=min(len(selected_devices), 16)) as executor:
            list(executor.map(stop_proof_app, [device.resolved_serial for device in selected_devices]))


    summary = {
        **inventory,
        "installResults": install_results,
        "prepResults": prep_results,
        "bluetoothChecks": bluetooth_checks,
        "bluetoothRecoveryActions": bluetooth_recovery_actions,
        "batchErrors": batch_errors,
        "launchResults": launch_results,
        "captureResults": capture_results,
        "modeResults": mode_results,
        "logcatResults": logcat_results,
        "pairRows": pair_rows,
    }
    (run_root / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    (run_root / "summary.md").write_text(render_compact_summary(summary), encoding="utf-8")
    (run_root / "compact-summary.md").write_text(render_compact_summary(summary), encoding="utf-8")

    print(json.dumps({
        "runRoot": str(run_root),
        "appId": app_id,
        "fleetDevices": len(devices),
        "devices": len(selected_devices),
        "pairs": len(selected_pairs),
        "summary": str(run_root / "summary.md"),
        "compactSummary": str(run_root / "compact-summary.md"),
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
