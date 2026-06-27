#!/usr/bin/env python3

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
TOOLS = ROOT / "tools" / "android"
APK = ROOT / "meshlink-proof" / "android" / "build" / "outputs" / "apk" / "debug" / "android-debug.apk"
PACKAGE_NAME = "ch.trancee.meshlink.proof.android"
ACTIVITY_NAME = ".MainActivity"
DEFAULT_POWER_MODE = "performance"
DEFAULT_PAYLOAD_BYTES = 64
DEFAULT_WAIT_SECONDS = 120
DEFAULT_RUN_ROOT = ROOT / "reports" / "android-proof-fleet" / "runs"
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
    return parser.parse_args(argv)


def run_command(command: list[str], *, check: bool = False) -> subprocess.CompletedProcess[str]:
    completed = subprocess.run(command, capture_output=True, text=True)
    if check and completed.returncode != 0:
        raise subprocess.CalledProcessError(
            completed.returncode,
            command,
            output=completed.stdout,
            stderr=completed.stderr,
        )
    return completed


def android_devices() -> list[dict[str, Any]]:
    completed = run_command([str(TOOLS), "device", "list", "--json"], check=True)
    payload = json.loads(completed.stdout)
    return payload.get("devices", [])


@lru_cache(maxsize=None)
def device_info(serial: str) -> dict[str, Any]:
    completed = run_command([str(TOOLS), "device", "info", "--device", serial, "--json"], check=True)
    return json.loads(completed.stdout)


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


def collect_devices() -> list[DeviceRecord]:
    seen: set[str] = set()
    records: list[DeviceRecord] = []
    for entry in android_devices():
        listed_id = str(entry.get("id") or "").strip()
        if not listed_id:
            continue
        resolved_serial, info = normalize_android_serial(listed_id)
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


def install_apk(serial: str) -> dict[str, Any]:
    completed = run_command([
        str(TOOLS),
        "app",
        "install",
        "--device",
        serial,
        "--apk",
        str(APK),
        "--package",
        PACKAGE_NAME,
    ])
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
    completed = run_command([str(TOOLS), "debug", "logs", "--device", serial, "--lines", "2000"])
    text = completed.stdout + ("\n" + completed.stderr if completed.stderr else "")
    for line in text.splitlines():
        if "MeshLinkReferenceAutomation:" not in line:
            continue
        match = MESH_MODE_RE.search(line) or TRANSPORT_RE.search(line)
        if match:
            return {"serial": serial, "mode": match.group(1), "line": line}
    return {"serial": serial, "mode": None, "line": None}


def canonical_transport_label(modes: list[str | None]) -> str:
    candidates = [mode for mode in modes if mode]
    if not candidates:
        return "unknown"
    if any("L2CAP" in mode for mode in candidates):
        return "L2CAP"
    if any("GATT" in mode for mode in candidates):
        return "GATT"
    return candidates[0]


def canonical_transport_label(modes: list[str | None]) -> str:
    candidates = [mode for mode in modes if mode]
    if not candidates:
        return "unknown"
    if any("L2CAP" in mode for mode in candidates):
        return "L2CAP"
    if any("GATT" in mode for mode in candidates):
        return "GATT"
    return candidates[0]


def summarize_pair(pair: PairRecord, run_root: Path, mode_map: dict[str, str | None]) -> dict[str, Any]:
    initiator_log = (run_root / "logs" / f"{pair.initiator.resolved_serial}.proof.log").read_text(encoding="utf-8", errors="ignore").splitlines()
    receiver_log = (run_root / "logs" / f"{pair.receiver.resolved_serial}.proof.log").read_text(encoding="utf-8", errors="ignore").splitlines()
    combined = initiator_log + receiver_log
    ack_lines = [
        line
        for line in combined
        if "DELIVERY_SUCCEEDED" in line or "auto-send attempt 1 -> Sent" in line or "SendResult.Sent" in line
    ]
    receipt_lines = [
        line
        for line in combined
        if "BENCHMARK receipt sent" in line or "BENCHMARK receipt timeout" in line or "BENCHMARK receipt failed" in line
    ]
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
    lines.append("| Pair | Initiator | Receiver | Transport | ACK evidence | Receipt evidence |")
    lines.append("|---|---|---|---|---|---|")
    for row in summary["pairRows"]:
        transport = row.get("transportMode") or "unknown"
        ack = "yes" if row["ackLines"] else "no"
        receipt = "yes" if row["receiptLines"] else "no"
        lines.append(
            f"| {row['label']} | {row['initiator']['model']} | {row['receiver']['model']} | {transport} | {ack} | {receipt} |"
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
    return "\n".join(lines)


def render_human_summary(summary: dict[str, Any]) -> str:
    lines: list[str] = []
    lines.append("# MeshLink proof-app run note")
    lines.append("")
    lines.append(
        f"Bluetooth preflight was checked before launch on {summary.get('deviceCount', len(summary.get('selectedDevices', [])))} devices across {summary.get('pairCount', len(summary.get('pairs', [])))} pairs."
    )
    lines.append("Weak pairs can be rerun with `--pair-label`.")
    lines.append("")
    lines.append("## Pair snapshot")
    for row in summary["pairRows"]:
        transport = row.get("transportMode") or "unknown"
        ack = "yes" if row["ackLines"] else "no"
        lines.append(f"- {row['label']}: {transport}; ACK {ack}")
    return "\n".join(lines)



def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    timestamp_value = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    run_root = Path(args.run_root) if args.run_root else DEFAULT_RUN_ROOT / timestamp_value
    run_root.mkdir(parents=True, exist_ok=True)
    (run_root / "logs").mkdir(exist_ok=True)

    app_id = args.app_id or f"demo.meshlink.proof.{timestamp_value}"

    devices = collect_devices()
    all_pairs = cross_generation_pairs(devices)
    if args.pair_labels:
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

    with ThreadPoolExecutor(max_workers=min(len(selected_devices), 16)) as executor:
        install_results = list(executor.map(install_apk, [device.resolved_serial for device in selected_devices]))

    with ThreadPoolExecutor(max_workers=min(len(selected_devices), 16)) as executor:
        prep_results = list(executor.map(grant_permissions, selected_devices))

    with ThreadPoolExecutor(max_workers=min(len(selected_devices), 16)) as executor:
        bluetooth_checks = list(executor.map(ensure_bluetooth_on, selected_devices))

    launch_results: list[dict[str, Any]] = []
    with ThreadPoolExecutor(max_workers=max(2, len(selected_pairs) * 2)) as executor:
        futures = []
        for pair in selected_pairs:
            futures.append(executor.submit(launch_app, pair.initiator.resolved_serial, app_id=app_id, power_mode=args.power_mode, initiator=True, payload_bytes=args.payload_bytes))
            futures.append(executor.submit(launch_app, pair.receiver.resolved_serial, app_id=app_id, power_mode=args.power_mode, initiator=False, payload_bytes=args.payload_bytes))
        for future in as_completed(futures):
            launch_results.append(future.result())

    time.sleep(args.wait_seconds)

    with ThreadPoolExecutor(max_workers=min(len(selected_devices), 16)) as executor:
        capture_results = list(executor.map(lambda device: capture_proof_log(device.resolved_serial, run_root=run_root), selected_devices))

    with ThreadPoolExecutor(max_workers=min(len(selected_devices), 16)) as executor:
        mode_results = list(executor.map(lambda device: capture_transport_mode(device.resolved_serial), selected_devices))

    mode_map = {result["serial"]: result["mode"] for result in mode_results}
    pair_rows = [summarize_pair(pair, run_root, mode_map) for pair in selected_pairs]

    summary = {
        **inventory,
        "installResults": install_results,
        "prepResults": prep_results,
        "bluetoothChecks": bluetooth_checks,
        "launchResults": launch_results,
        "captureResults": capture_results,
        "modeResults": mode_results,
        "pairRows": pair_rows,
    }
    (run_root / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    (run_root / "summary.md").write_text(render_compact_summary(summary), encoding="utf-8")
    (run_root / "compact-summary.md").write_text(render_compact_summary(summary), encoding="utf-8")
    (run_root / "human-summary.md").write_text(render_human_summary(summary), encoding="utf-8")

    print(json.dumps({
        "runRoot": str(run_root),
        "appId": app_id,
        "fleetDevices": len(devices),
        "devices": len(selected_devices),
        "pairs": len(selected_pairs),
        "summary": str(run_root / "summary.md"),
        "compactSummary": str(run_root / "compact-summary.md"),
        "humanSummary": str(run_root / "human-summary.md"),
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
