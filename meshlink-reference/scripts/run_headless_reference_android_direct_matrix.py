#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from functools import lru_cache
from pathlib import Path
from typing import Any

from run_headless_reference_live_proof import shell_join, timestamp

PROOF_SCRIPT = Path("meshlink-reference/scripts/run_headless_reference_android_direct_proof.py")
TARGET_PEER = "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_TARGET_PEER_ID"
DEFAULT_ANDROID_READY_SECONDS = 20.0
DEFAULT_CAPTURE_TIMEOUT_SECONDS = 30.0
DEFAULT_PAIR_TIMEOUT_SECONDS = 300.0
DEFAULT_MIN_ANDROID_API_LEVEL = 33
DEFAULT_FALLBACK_TRANSPORT = "gatt"
DEFAULT_PRIMARY_TRANSPORT = "meshlink"
REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MATRIX_RUN_ROOT = REPO_ROOT / "reports" / "android-direct-proof-fleet" / "runs"

PAIRS = [
    {"label": "a065_nam_lx9", "sender": "1f1dad34", "passive": "2ASVB21B09005117"},
    {"label": "a065_xcover", "sender": "1f1dad34", "passive": "42004386e43c8589"},
    {"label": "a065_mi_note3", "sender": "1f1dad34", "passive": "42c2cf"},
    {"label": "a065_cph2359", "sender": "1f1dad34", "passive": "EQUGS85LJNEIO7Z5"},
    {"label": "a065_e940", "sender": "1f1dad34", "passive": "GX6CTR500184"},
    {"label": "nam_lx9_a065", "sender": "2ASVB21B09005117", "passive": "1f1dad34"},
    {"label": "nam_lx9_xcover", "sender": "2ASVB21B09005117", "passive": "42004386e43c8589"},
    {"label": "nam_lx9_mi_note3", "sender": "2ASVB21B09005117", "passive": "42c2cf"},
    {"label": "nam_lx9_cph2359", "sender": "2ASVB21B09005117", "passive": "EQUGS85LJNEIO7Z5"},
    {"label": "nam_lx9_e940", "sender": "2ASVB21B09005117", "passive": "GX6CTR500184"},
    {"label": "xcover_a065", "sender": "42004386e43c8589", "passive": "1f1dad34"},
    {"label": "xcover_nam_lx9", "sender": "42004386e43c8589", "passive": "2ASVB21B09005117"},
    {"label": "xcover_mi_note3", "sender": "42004386e43c8589", "passive": "42c2cf"},
    {"label": "xcover_cph2359", "sender": "42004386e43c8589", "passive": "EQUGS85LJNEIO7Z5"},
    {"label": "xcover_e940", "sender": "42004386e43c8589", "passive": "GX6CTR500184"},
    {"label": "mi_note3_a065", "sender": "42c2cf", "passive": "1f1dad34"},
    {"label": "mi_note3_nam_lx9", "sender": "42c2cf", "passive": "2ASVB21B09005117"},
    {"label": "mi_note3_xcover", "sender": "42c2cf", "passive": "42004386e43c8589"},
    {"label": "mi_note3_cph2359", "sender": "42c2cf", "passive": "EQUGS85LJNEIO7Z5"},
    {"label": "mi_note3_e940", "sender": "42c2cf", "passive": "GX6CTR500184"},
    {"label": "cph2359_a065", "sender": "EQUGS85LJNEIO7Z5", "passive": "1f1dad34"},
    {"label": "cph2359_nam_lx9", "sender": "EQUGS85LJNEIO7Z5", "passive": "2ASVB21B09005117"},
    {"label": "cph2359_xcover", "sender": "EQUGS85LJNEIO7Z5", "passive": "42004386e43c8589"},
    {"label": "cph2359_mi_note3", "sender": "EQUGS85LJNEIO7Z5", "passive": "42c2cf"},
    {"label": "cph2359_e940", "sender": "EQUGS85LJNEIO7Z5", "passive": "GX6CTR500184"},
    {"label": "e940_a065", "sender": "GX6CTR500184", "passive": "1f1dad34"},
    {"label": "e940_nam_lx9", "sender": "GX6CTR500184", "passive": "2ASVB21B09005117"},
    {"label": "e940_xcover", "sender": "GX6CTR500184", "passive": "42004386e43c8589"},
    {"label": "e940_mi_note3", "sender": "GX6CTR500184", "passive": "42c2cf"},
    {"label": "e940_cph2359", "sender": "GX6CTR500184", "passive": "EQUGS85LJNEIO7Z5"},
]

ANDROID_MODELS = {
    "1f1dad34": "A065",
    "2ASVB21B09005117": "NAM-LX9",
    "42004386e43c8589": "SM-G390F",
    "42c2cf": "Mi Note 3",
    "EQUGS85LJNEIO7Z5": "CPH2359",
    "GX6CTR500184": "E940-2849-00",
}


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Run the directed Android sender→passive direct-proof matrix with checkpointed progress."
        )
    )
    parser.add_argument("--run-root", help="Directory for the checkpointed matrix run")
    parser.add_argument(
        "--sender-passive-limit",
        type=int,
        help="Optional cap on the number of directed pairs to run for a partial sweep",
    )
    parser.add_argument(
        "--android-ready-seconds",
        type=float,
        default=DEFAULT_ANDROID_READY_SECONDS,
        help="Passive startup wait per pair",
    )
    parser.add_argument(
        "--capture-timeout-seconds",
        type=float,
        default=DEFAULT_CAPTURE_TIMEOUT_SECONDS,
        help="Proof completion timeout per pair",
    )
    parser.add_argument(
        "--pair-timeout-seconds",
        type=float,
        default=DEFAULT_PAIR_TIMEOUT_SECONDS,
        help="Outer timeout per pair so a hung proof script cannot stall the whole sweep",
    )
    parser.add_argument(
        "--min-android-api-level",
        type=int,
        default=DEFAULT_MIN_ANDROID_API_LEVEL,
        help="Android API level below which a directed pair falls back to GATT transport",
    )
    parser.add_argument(
        "--resume",
        action="store_true",
        help="Resume from the last checkpoint if the run root already contains progress",
    )
    parser.add_argument(
        "--fail-fast",
        action="store_true",
        help="Stop immediately after the first failed pair",
    )
    parser.add_argument(
        "--max-failures",
        type=int,
        default=5,
        help="Stop the sweep once failures exceed this threshold when fail-fast is disabled",
    )
    parser.set_defaults(fail_fast=False)
    return parser.parse_args(argv)


def adb_devices() -> list[str]:
    result = subprocess.run(["adb", "devices"], check=True, capture_output=True, text=True)
    devices: list[str] = []
    for line in result.stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])
    return devices


@lru_cache(maxsize=None)
def adb_device_api_level(serial: str) -> int | None:
    try:
        result = subprocess.run(
            ["adb", "-s", serial, "shell", "getprop", "ro.build.version.sdk"],
            check=True,
            capture_output=True,
            text=True,
        )
    except subprocess.CalledProcessError:
        return None
    raw_value = result.stdout.strip()
    if not raw_value:
        return None
    try:
        return int(raw_value)
    except ValueError:
        return None


def select_pair_transport(
    sender_api_level: int | None,
    passive_api_level: int | None,
    *,
    fallback_android_api_level: int,
) -> tuple[str, dict[str, Any] | None]:
    if sender_api_level is None or passive_api_level is None:
        return DEFAULT_PRIMARY_TRANSPORT, None
    if sender_api_level < fallback_android_api_level or passive_api_level < fallback_android_api_level:
        return DEFAULT_FALLBACK_TRANSPORT, {
            "senderApiLevel": sender_api_level,
            "passiveApiLevel": passive_api_level,
            "reason": (
                f"android API below {fallback_android_api_level}; using {DEFAULT_FALLBACK_TRANSPORT.upper()} fallback"
            ),
        }
    return DEFAULT_PRIMARY_TRANSPORT, None


def run_pair(
    *,
    sender: str,
    passive: str,
    app_id: str,
    run_dir: Path,
    target_peer_id: str | None,
    capture_timeout: float,
    android_ready_seconds: float,
    pair_timeout_seconds: float,
    skip_install: bool,
) -> dict[str, Any]:
    command = [
        sys.executable,
        str(PROOF_SCRIPT),
        "--sender-android-serial",
        sender,
        "--passive-android-serial",
        passive,
        "--app-id",
        app_id,
        "--run-dir",
        str(run_dir),
        "--android-ready-seconds",
        str(android_ready_seconds),
        "--capture-timeout-seconds",
        str(capture_timeout),
        "--advertisement-carrier",
        "uuid-pair-plus-service-data",
    ]
    if skip_install:
        command.append("--skip-android-install")
    if target_peer_id is not None:
        command.extend(["--target-peer-id", target_peer_id])

    print(f"==> Running: {shell_join(command)}", flush=True)
    started_at = time.monotonic()
    try:
        completed = subprocess.run(command, capture_output=True, text=True, timeout=pair_timeout_seconds)
    except subprocess.TimeoutExpired as error:
        elapsed = round(time.monotonic() - started_at, 1)
        stdout_value = getattr(error, "stdout", None) or getattr(error, "output", None) or ""
        stderr_value = getattr(error, "stderr", None) or ""
        stdout_tail = stdout_value.strip()[-1000:] if isinstance(stdout_value, str) else ""
        stderr_tail = stderr_value.strip()[-1000:] if isinstance(stderr_value, str) else ""
        stage = "preflight" if not skip_install else "capture"
        reason = f"{stage} timed out after {pair_timeout_seconds:.1f}s"
        return {
            "status": "failed",
            "failureStage": stage,
            "failureReason": reason,
            "routeStage": None,
            "routeEvidence": None,
            "senderRouteStage": None,
            "passiveRouteStage": None,
            "timings": {"totalSeconds": elapsed, "pairTimeoutSeconds": pair_timeout_seconds},
            "startupTiming": {},
            "htmlReportPath": None,
            "exportRelativePath": None,
            "evidence": {},
            "captured": {},
            "summaryPath": str(run_dir / "summary.json"),
            "runDir": str(run_dir),
            "stdoutTail": stdout_tail,
            "stderrTail": stderr_tail,
            "elapsedSeconds": elapsed,
            "exitCode": 124,
            "timedOut": True,
            "timeoutSeconds": pair_timeout_seconds,
        }
    elapsed = round(time.monotonic() - started_at, 1)
    summary_path = run_dir / "summary.json"
    summary: dict[str, Any]
    if summary_path.exists():
        summary = json.loads(summary_path.read_text(encoding="utf-8"))
    else:
        summary = {
            "status": "failed",
            "failureStage": "no-summary",
            "failureReason": (completed.stderr or completed.stdout or "").strip(),
        }
    return {
        "status": summary.get("status"),
        "failureStage": summary.get("failureStage"),
        "failureReason": summary.get("failureReason"),
        "routeStage": summary.get("routeStage"),
        "routeEvidence": summary.get("routeEvidence"),
        "senderRouteStage": summary.get("senderRouteStage"),
        "passiveRouteStage": summary.get("passiveRouteStage"),
        "timings": summary.get("timings"),
        "startupTiming": summary.get("startupTiming"),
        "htmlReportPath": summary.get("htmlReportPath"),
        "exportRelativePath": summary.get("exportRelativePath"),
        "evidence": summary.get("evidence"),
        "captured": summary.get("captured"),
        "summaryPath": str(summary_path),
        "runDir": str(run_dir),
        "stdoutTail": (completed.stdout or "")[-1000:],
        "stderrTail": (completed.stderr or "")[-1000:],
        "elapsedSeconds": elapsed,
        "exitCode": completed.returncode,
    }


def read_passive_peer_id(serial: str, app_id: str, retries: int = 60, delay_s: float = 1.0) -> str | None:
    xml_path = f"shared_prefs/meshlink-{app_id}.xml"
    for _ in range(retries):
        result = subprocess.run(
            ["adb", "-s", serial, "shell", "run-as", "ch.trancee.meshlink.reference", "cat", xml_path],
            check=False,
            capture_output=True,
            text=True,
        )
        if result.returncode == 0 and "x25519-public" in result.stdout:
            try:
                root = ET.fromstring(result.stdout)
            except ET.ParseError:
                time.sleep(delay_s)
                continue
            for item in root.findall(".//string"):
                if item.get("name") == TARGET_PEER and item.text:
                    return item.text.strip()
        time.sleep(delay_s)
    print(f"==> Passive peer id unavailable for {serial}; continuing without a seeded target peer")
    return None


def compact_status(summary: dict[str, Any]) -> dict[str, Any]:
    return {
        "status": summary.get("status"),
        "failureStage": summary.get("failureStage"),
        "failureReason": summary.get("failureReason"),
        "senderCompletion": summary.get("senderCompletion"),
        "passiveCompletion": summary.get("passiveCompletion"),
        "routeStage": summary.get("routeStage"),
        "routeEvidence": summary.get("routeEvidence"),
        "senderRouteStage": summary.get("senderRouteStage"),
        "senderRouteEvidence": summary.get("senderRouteEvidence"),
        "passiveRouteStage": summary.get("passiveRouteStage"),
        "passiveRouteEvidence": summary.get("passiveRouteEvidence"),
        "transportMode": summary.get("transportMode") or (summary.get("timings") or {}).get("transportMode"),
        "transportEvidence": summary.get("transportEvidence") or (summary.get("timings") or {}).get("transportEvidence"),
        "startupTiming": summary.get("startupTiming"),
        "timings": summary.get("timings"),
        "htmlReportPath": summary.get("htmlReportPath"),
        "exportRelativePath": summary.get("exportRelativePath"),
        "evidence": summary.get("evidence"),
        "captured": summary.get("captured"),
        "summaryPath": summary.get("summaryPath"),
        "runDir": summary.get("runDir"),
    }


def render_compact_report(results: list[dict[str, Any]], *, state: dict[str, Any] | None = None) -> str:
    passing_pairs = [
        f"| {row['senderModel']} | {row['passiveModel']} | passed |"
        for row in results
        if row["final"]["status"] == "passed"
    ]
    failure_counts: dict[str, dict[str, int]] = {}
    for row in results:
        device = row["senderModel"]
        final = row["final"]
        reason = final.get("failureReason") or ""
        stage = final.get("failureStage") or "passed"
        if final["status"] == "passed":
            bucket = "passed"
        elif stage == "preflight" or "install" in reason.lower():
            bucket = "preflight/install"
        elif stage == "launch" or "launch" in reason.lower():
            bucket = "launch timeout"
        else:
            bucket = "capture/route stall"
        failure_counts.setdefault(device, {})
        failure_counts[device][bucket] = failure_counts[device].get(bucket, 0) + 1
    top_failure_rows = []
    bucket_totals: dict[str, int] = {}
    for device, buckets in failure_counts.items():
        top_bucket = max(buckets.items(), key=lambda item: item[1])
        top_failure_rows.append(f"| {device} | {top_bucket[0]} | {top_bucket[1]} |")
        for bucket, count in buckets.items():
            bucket_totals[bucket] = bucket_totals.get(bucket, 0) + count
    top_failure_bucket = max(bucket_totals.items(), key=lambda item: item[1])[0] if bucket_totals else "—"

    completed_pairs = len(results)
    passing_count = sum(1 for row in results if row["final"]["status"] == "passed")
    failing_count = completed_pairs - passing_count
    fail_fast = bool(state.get("failFast")) if state is not None else False
    max_failures = state.get("maxFailures") if state is not None else None
    stopped_early = bool(state.get("stoppedEarly")) if state is not None else False
    stop_reason = state.get("stopReason") if state is not None else None
    total_pairs = state.get("totalPairs") if state is not None else None
    pending_pairs = state.get("pendingPairs") if state is not None else None
    fleet_inventory_path = state.get("fleetInventoryPath") if state is not None else None
    run_root = state.get("runRoot") if state is not None else None

    report = [
        "# Android direct-proof matrix",
        "",
        "## Overview",
        "",
        "| Metric | Value |",
        "|---|---|",
        f"| Completed pairs | {completed_pairs} |",
        f"| Passing pairs | {passing_count} |",
        f"| Failing pairs | {failing_count} |",
        f"| Pending pairs | {pending_pairs if pending_pairs is not None else '—'} |",
        f"| Fail-fast | {'enabled' if fail_fast else 'disabled'} |",
        f"| Max failures | {max_failures if max_failures is not None else '—'} |",
        f"| Stopped early | {'yes' if stopped_early else 'no'} |",
        f"| Stop reason | {stop_reason or '—'} |",
        "",
        "## Mermaid overview",
        "",
        "```mermaid",
        "sequenceDiagram",
        "    autonumber",
        "    participant Matrix",
        "    participant Fleet",
        "    participant Sweep",
        "    participant Stop",
        f"    note over Matrix: runRoot={run_root or '—'} · fleet={Path(fleet_inventory_path).name if fleet_inventory_path else '—'}",
        "    rect rgba(245, 247, 250, 0.55)",
        f"        Matrix->>Fleet: capture inventory ({total_pairs if total_pairs is not None else 'unknown'} pairs)",
        f"        Matrix->>Sweep: execute directed pairs ({completed_pairs} completed)",
        f"        note over Fleet,Sweep: passing={passing_count} · failing={failing_count}",
        "    end",
        "    rect rgba(236, 253, 245, 0.55)",
        "        Sweep->>Sweep: bucket outcomes",
        f"        note over Sweep: top failure bucket = {top_failure_bucket}",
        "        alt at least one passing pair",
        f"            Sweep-->>Matrix: {passing_count} passing pairs recorded",
        "        else no passing pairs",
        "            Sweep-->>Matrix: no successful pairs",
        "        end",
        "    end",
        "    rect rgba(254, 242, 242, 0.55)",
        "        alt stopped early",
        f"            Sweep->>Stop: {stop_reason or 'failure threshold reached'}",
        "        else sweep completed",
        "            Sweep->>Stop: all processed pairs recorded",
        "        end",
        "        note over Stop: next step = inspect failure buckets and route evidence",
        "    end",
        "```",
        "",
        "## Passing pairs",
        "",
        "| Sender | Passive | Result |",
        "|---|---|---|",
        *passing_pairs,
        "",
        "## Most common failure reason per device",
        "",
        "| Device | Most common failure reason | Count |",
        "|---|---|---|",
        *top_failure_rows,
        "",
    ]
    return "\n".join(report)


def format_elapsed_seconds(summary: dict[str, Any] | None) -> str:
    if summary is None:
        return "—"
    elapsed = summary.get("elapsedSeconds")
    if elapsed is None:
        timings = summary.get("timings") or {}
        elapsed = timings.get("totalSeconds")
    if elapsed is None:
        return "—"
    try:
        return f"{float(elapsed):.1f}s"
    except (TypeError, ValueError):
        return str(elapsed)


def build_fleet_inventory(
    *,
    attached_devices: list[str],
    available_pairs: list[dict[str, str]],
    fail_fast: bool,
    max_failures: int,
) -> dict[str, Any]:
    devices = [
        {
            "serial": serial,
            "model": ANDROID_MODELS.get(serial, serial),
            "apiLevel": adb_device_api_level(serial),
        }
        for serial in attached_devices
    ]
    return {
        "capturedAt": timestamp(),
        "failFast": fail_fast,
        "maxFailures": max_failures,
        "deviceCount": len(devices),
        "pairCount": len(available_pairs),
        "devices": devices,
        "availablePairs": [
            {
                "label": pair["label"],
                "sender": pair["sender"],
                "passive": pair["passive"],
                "senderModel": ANDROID_MODELS.get(pair["sender"], pair["sender"]),
                "passiveModel": ANDROID_MODELS.get(pair["passive"], pair["passive"]),
            }
            for pair in available_pairs
        ],
    }


def render_fleet_inventory_markdown(inventory: dict[str, Any]) -> str:
    device_rows = ["| Serial | Model | API level |", "|---|---|---|"]
    for device in inventory["devices"]:
        api_level = device.get("apiLevel")
        device_rows.append(
            f"| {device['serial']} | {device['model']} | {api_level if api_level is not None else 'unknown'} |"
        )

    pair_rows = ["| Label | Sender | Passive |", "|---|---|---|"]
    for pair in inventory["availablePairs"]:
        pair_rows.append(
            f"| {pair['label']} | {pair['senderModel']} ({pair['sender']}) | {pair['passiveModel']} ({pair['passive']}) |"
        )

    fail_fast_value = "enabled" if inventory.get("failFast", True) else "disabled"
    return "\n".join(
        [
            "# Android fleet inventory",
            "",
            f"- Captured: {inventory.get('capturedAt', '—')}",
            f"- Fail-fast: {fail_fast_value}",
            f"- Device count: {inventory.get('deviceCount', 0)}",
            f"- Pair count: {inventory.get('pairCount', 0)}",
            "",
            "## Devices",
            "",
            *device_rows,
            "",
            "## Available directed pairs",
            "",
            *pair_rows,
            "",
        ]
    )


def render_pair_report(
    *,
    index: int,
    pair: dict[str, str],
    sender_model: str,
    passive_model: str,
    sender_api_level: int | None,
    passive_api_level: int | None,
    target_peer_id: str | None,
    peer_lookup_seconds: float | None,
    initial: dict[str, Any],
    final: dict[str, Any],
    fleet_inventory_path: Path,
    pair_report_path: Path,
) -> str:
    def json_block(title: str, value: Any) -> list[str]:
        return [title, "", "```json", json.dumps(value, indent=2, sort_keys=True), "```", ""]

    def path_ref(label: str, value: str | None) -> str:
        return value or "—"

    def evidence_rows(summary: dict[str, Any], stage: str) -> list[str]:
        evidence = summary.get("evidence") or {}
        captured = summary.get("captured") or {}
        rows = [f"| {stage} artifact | Path | Captured |", "|---|---|---|"]
        for key in ("senderLogcat", "passiveLogcat", "senderStart", "passiveStart", "androidHistory", "androidExport"):
            rows.append(
                f"| {stage} {key} | `{evidence.get(key, '—')}` | {'yes' if captured.get(key) else 'no'} |"
            )
        return rows

    def observed_transport(summary: dict[str, Any]) -> str | None:
        return summary.get("transportMode") or (summary.get("timings") or {}).get("transportMode")

    def observed_transport_evidence(summary: dict[str, Any]) -> str | None:
        return summary.get("transportEvidence") or (summary.get("timings") or {}).get("transportEvidence")

    initial_elapsed = format_elapsed_seconds(initial)
    final_elapsed = format_elapsed_seconds(final)
    peer_lookup_elapsed = f"{peer_lookup_seconds:.1f}s" if peer_lookup_seconds is not None else "—"
    transport_label = observed_transport(final) or observed_transport(initial) or "unknown"
    transport_evidence = observed_transport_evidence(final) or observed_transport_evidence(initial)
    quirks = [f"Transport chosen by library: {transport_label}"]
    if transport_evidence:
        quirks.append(f"Transport evidence: {transport_evidence}")
    if sender_api_level is not None:
        quirks.append(f"Sender API level: {sender_api_level}")
    if passive_api_level is not None:
        quirks.append(f"Passive API level: {passive_api_level}")
    if initial.get("failureReason"):
        quirks.append(f"Initial run failure: {initial['failureReason']}")
    if final.get("failureReason"):
        quirks.append(f"Final run failure: {final['failureReason']}")
    if target_peer_id is not None:
        quirks.append(f"Passive peer id discovered: {target_peer_id}")

    initial_passed = initial.get("status") == "passed"
    final_status = final.get("status", "skipped")
    def failure_explanation(summary: dict[str, Any]) -> str:
        stage = summary.get("failureStage") or "unknown stage"
        reason = summary.get("failureReason") or "no failure reason recorded"
        route_stage = summary.get("routeStage") or "not observed"
        if summary.get("status") == "passed":
            return "pair completed successfully"
        if stage == "summary":
            return (
                f"route reached {route_stage} but the passive side never emitted proof.complete; "
                f"reason={reason}"
            )
        if "route-unavailable" in reason and "hop-failed" in reason:
            return (
                "sender waited for route availability while the passive side reported HOP_SESSION_FAILED; "
                f"reason={reason}"
            )
        if "route-unavailable" in reason:
            return f"sender never observed a stable route; reason={reason}"
        if "hop-failed" in reason:
            return f"passive side reported HOP_SESSION_FAILED before route stabilization; reason={reason}"
        return f"failureStage={stage}; reason={reason}"

    diagram_lines = [
        "```mermaid",
        "sequenceDiagram",
        "    autonumber",
        "    participant Matrix",
        f"    participant Sender as {sender_model}",
        f"    participant Passive as {passive_model}",
        f"    note over Matrix: transport {transport_label} · fleet {fleet_inventory_path.name} · report {pair_report_path.name}",
        "    rect rgba(245, 247, 250, 0.55)",
        f"        Matrix->>Sender: install and preflight initial run ({initial_elapsed})",
        f"        Sender-->>Matrix: startup markers observed ({initial.get('startupTiming', {}).get('sender', {}).get('elapsedSeconds', '—') if initial.get('startupTiming') else '—'})",
        f"        Matrix->>Passive: launch passive companion ({initial_elapsed})",
        f"        Passive-->>Matrix: startup markers observed ({initial.get('startupTiming', {}).get('passive', {}).get('elapsedSeconds', '—') if initial.get('startupTiming') else '—'})",
        f"        Matrix->>Matrix: wait for passive peer id ({peer_lookup_elapsed})",
        f"        note over Matrix: target peer {target_peer_id or 'not resolved'}",
        f"        note over Sender,Passive: initial startup={initial.get('status', 'unknown')} · stage={initial.get('failureStage') or 'no failure stage'}",
        "    end",
    ]
    if initial_passed:
        diagram_lines.extend(
            [
                "    rect rgba(236, 253, 245, 0.55)",
                f"        Matrix->>Sender: seed final run with peer id ({peer_lookup_elapsed})",
                f"        Sender-->>Matrix: final startup ready ({final_elapsed})",
                "        Sender->>Passive: discovery and route establishment",
                f"        note over Sender,Passive: routeStage={final.get('routeStage') or initial.get('routeStage') or 'unknown'}",
                "        Sender->>Passive: send guided payload",
                f"        note over Sender: sender completion in {final_elapsed}",
                f"        note over Passive: passive completion {final.get('passiveCompletion') or initial.get('passiveCompletion') or 'not observed'}",
                "        alt final passed",
                "            note over Matrix: pair completed successfully",
                "        else final failed",
                "            rect rgba(254, 242, 242, 0.55)",
                f"                note over Matrix: failure explanation: {failure_explanation(final)}",
                "            end",
                "        end",
                "    end",
            ]
        )
    else:
        diagram_lines.extend([
            "    rect rgba(254, 242, 242, 0.55)",
            f"        note over Matrix: failure explanation: {failure_explanation(initial)}",
            "    end",
        ])
    diagram_lines.extend(["```", ""])

    lines = [
        f"# Pair {index:02d} — {pair['label']}",
        "",
        "## Setup",
        "",
        f"- Sender: {sender_model} ({pair['sender']})",
        f"- Passive: {passive_model} ({pair['passive']})",
        f"- Sender API level: {sender_api_level if sender_api_level is not None else 'unknown'}",
        f"- Passive API level: {passive_api_level if passive_api_level is not None else 'unknown'}",
        f"- Transport: {transport_label}",
        f"- Fleet inventory: `{fleet_inventory_path}`",
        f"- Pair report path: `{pair_report_path}`",
        f"- Peer lookup time: {peer_lookup_elapsed}",
        f"- Initial run dir: `{path_ref('initial', initial.get('runDir'))}`",
        f"- Final run dir: `{path_ref('final', final.get('runDir'))}`",
        "",
        "## Result",
        "",
        f"- Initial status: {initial.get('status', 'unknown')} ({initial.get('failureStage') or 'no failure stage'}) in {initial_elapsed}",
        f"- Final status: {final_status} ({final.get('failureStage') or 'no failure stage'}) in {final_elapsed}",
        f"- Target peer id: {target_peer_id or 'not resolved'}",
        f"- Initial HTML report: `{path_ref('initial html', initial.get('htmlReportPath'))}`",
        f"- Final HTML report: `{path_ref('final html', final.get('htmlReportPath'))}`",
        f"- Initial summary JSON: `{path_ref('initial summary', initial.get('summaryPath'))}`",
        f"- Final summary JSON: `{path_ref('final summary', final.get('summaryPath'))}`",
        "",
        "## Troubleshooting references",
        "",
        *evidence_rows(initial, "Initial"),
        *evidence_rows(final, "Final"),
        "",
        "## Device quirks and issues",
        "",
        *[f"- {quirk}" for quirk in quirks],
        "",
        "## Startup timing",
        "",
        *json_block("Initial startupTiming", initial.get("startupTiming") or {}),
        *json_block("Initial timings", initial.get("timings") or {}),
        *json_block("Final startupTiming", final.get("startupTiming") or {}),
        *json_block("Final timings", final.get("timings") or {}),
        *json_block("Captured evidence map", {"initial": initial.get("captured") or {}, "final": final.get("captured") or {}}),
        "## Mermaid sequence diagram",
        "",
        *diagram_lines,
    ]
    return "\n".join(lines)


def load_progress(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    return json.loads(path.read_text(encoding="utf-8"))


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    attached_devices = adb_devices()
    device_set = set(attached_devices)
    available_pairs = [pair for pair in PAIRS if pair["sender"] in device_set and pair["passive"] in device_set]
    if args.sender_passive_limit is not None:
        available_pairs = available_pairs[: args.sender_passive_limit]

    run_root = Path(args.run_root or str(DEFAULT_MATRIX_RUN_ROOT / timestamp()))
    run_root.mkdir(parents=True, exist_ok=True)

    fleet_inventory = build_fleet_inventory(
        attached_devices=attached_devices,
        available_pairs=available_pairs,
        fail_fast=args.fail_fast,
        max_failures=args.max_failures,
    )
    fleet_json_path = run_root / "fleet.json"
    fleet_markdown_path = run_root / "fleet.md"
    fleet_json_path.write_text(json.dumps(fleet_inventory, indent=2), encoding="utf-8")
    fleet_markdown_path.write_text(render_fleet_inventory_markdown(fleet_inventory), encoding="utf-8")

    if not available_pairs:
        raise SystemExit("No directed Android pairs are available")

    progress_path = run_root / "progress.json"
    results_path = run_root / "matrix-results.json"
    state_path = run_root / "state.json"

    results = load_progress(progress_path) if args.resume else []
    completed = {(row["sender"], row["passive"]) for row in results}
    stopped_early = False
    stop_reason: str | None = None
    failure_count = sum(1 for row in results if row.get("final", {}).get("status") != "passed" or row.get("initial", {}).get("status") != "passed")

    for index, pair in enumerate(available_pairs, 1):
        if (pair["sender"], pair["passive"]) in completed:
            print(f"==> Skipping completed pair {pair['label']}", flush=True)
            continue
        app_id = f"demo.meshlink.reference.android-direct.{pair['label']}"
        initial_dir = run_root / f"{index:02d}_{pair['label']}_initial"
        final_dir = run_root / f"{index:02d}_{pair['label']}_final"
        initial_dir.mkdir(parents=True, exist_ok=True)
        final_dir.mkdir(parents=True, exist_ok=True)

        sender_api_level = adb_device_api_level(pair["sender"])
        passive_api_level = adb_device_api_level(pair["passive"])
        if sender_api_level is not None and passive_api_level is not None:
            print(
                "==> Pair "
                f"{pair['label']} library transport selection will be reported from device logs "
                f"(senderApiLevel={sender_api_level} passiveApiLevel={passive_api_level})",
                flush=True,
            )

        print(f"==> Pair {index}/{len(available_pairs)} {pair['label']}", flush=True)
        initial = run_pair(
            sender=pair["sender"],
            passive=pair["passive"],
            app_id=app_id,
            run_dir=initial_dir,
            target_peer_id=None,
            capture_timeout=args.capture_timeout_seconds,
            android_ready_seconds=args.android_ready_seconds,
            pair_timeout_seconds=args.pair_timeout_seconds,
            skip_install=False,
        )
        target_peer_id = None
        peer_lookup_seconds = None
        if initial["status"] == "passed":
            peer_lookup_started = time.monotonic()
            target_peer_id = read_passive_peer_id(pair["passive"], app_id)
            peer_lookup_seconds = round(time.monotonic() - peer_lookup_started, 1)
            final = run_pair(
                sender=pair["sender"],
                passive=pair["passive"],
                app_id=app_id,
                run_dir=final_dir,
                target_peer_id=target_peer_id,
                capture_timeout=args.capture_timeout_seconds,
                android_ready_seconds=args.android_ready_seconds,
                pair_timeout_seconds=args.pair_timeout_seconds,
                skip_install=True,
            )
        else:
            final = {
                "status": "skipped",
                "failureStage": initial.get("failureStage"),
                "failureReason": initial.get("failureReason"),
                "senderCompletion": initial.get("senderCompletion"),
                "passiveCompletion": initial.get("passiveCompletion"),
                "timings": initial.get("timings"),
                "htmlReportPath": initial.get("htmlReportPath"),
                "stdoutTail": initial.get("stdoutTail"),
                "stderrTail": initial.get("stderrTail"),
                "elapsedSeconds": initial.get("elapsedSeconds"),
                "exitCode": initial.get("exitCode"),
            }

        row = {
            "label": pair["label"],
            "sender": pair["sender"],
            "passive": pair["passive"],
            "senderModel": ANDROID_MODELS.get(pair["sender"], pair["sender"]),
            "passiveModel": ANDROID_MODELS.get(pair["passive"], pair["passive"]),
            "appId": app_id,
            "targetPeerId": target_peer_id,
            "initial": compact_status(initial),
            "final": compact_status(final),
            "initialRunDir": str(initial_dir),
            "finalRunDir": str(final_dir),
            "pairReportPath": str(run_root / f"{index:02d}_{pair['label']}_report.md"),
            "fleetJsonPath": str(fleet_json_path),
            "fleetInventoryPath": str(fleet_markdown_path),
            "transportMode": initial.get("transportMode") or final.get("transportMode") or "unknown",
            "transportEvidence": initial.get("transportEvidence") or final.get("transportEvidence"),
            "peerLookupSeconds": peer_lookup_seconds,
            "failFast": args.fail_fast,
            "maxFailures": args.max_failures,
        }
        results.append(row)
        completed.add((pair["sender"], pair["passive"]))
        progress_path.write_text(json.dumps(results, indent=2), encoding="utf-8")
        results_path.write_text(json.dumps(results, indent=2), encoding="utf-8")
        state_path.write_text(
            json.dumps(
                {
                    "runRoot": str(run_root),
                    "fleetInventoryPath": str(fleet_markdown_path),
                    "totalPairs": len(available_pairs),
                    "completedPairs": len(results),
                    "pendingPairs": len(available_pairs) - len(results),
                    "lastPair": pair,
                    "failFast": args.fail_fast,
                    "maxFailures": args.max_failures,
                    "stoppedEarly": stopped_early,
                    "stopReason": stop_reason,
                },
                indent=2,
            ),
            encoding="utf-8",
        )
        pair_report_path = run_root / f"{index:02d}_{pair['label']}_report.md"
        pair_report_path.write_text(
            render_pair_report(
                index=index,
                pair=pair,
                sender_model=row["senderModel"],
                passive_model=row["passiveModel"],
                sender_api_level=sender_api_level,
                passive_api_level=passive_api_level,
                target_peer_id=target_peer_id,
                peer_lookup_seconds=peer_lookup_seconds,
                initial=initial,
                final=final,
                fleet_inventory_path=fleet_markdown_path,
                pair_report_path=pair_report_path,
            ),
            encoding="utf-8",
        )
        print(
            f"    initial {initial['status']} ({initial.get('failureStage')}) -> final {final['status']} ({final.get('failureStage')})",
            flush=True,
        )

        pair_failed = initial["status"] != "passed" or final["status"] != "passed"
        if pair_failed:
            failure_count += 1
        if args.fail_fast and pair_failed:
            stopped_early = True
            stop_reason = (
                f"pair {pair['label']} failed during {initial['failureStage'] or final['failureStage'] or 'unknown stage'}"
            )
            break
        if not args.fail_fast and failure_count > args.max_failures:
            stopped_early = True
            stop_reason = (
                f"failure count {failure_count} exceeded max-failures={args.max_failures}; investigate the recorded failures before continuing"
            )
            break

    state_path.write_text(
        json.dumps(
            {
                "runRoot": str(run_root),
                "fleetInventoryPath": str(fleet_markdown_path),
                "totalPairs": len(available_pairs),
                "completedPairs": len(results),
                "pendingPairs": len(available_pairs) - len(results),
                "lastPair": results[-1] if results else None,
                "failFast": args.fail_fast,
                "stoppedEarly": stopped_early,
                "stopReason": stop_reason,
            },
            indent=2,
        ),
        encoding="utf-8",
    )

    compact_report = render_compact_report(results, state=json.loads(state_path.read_text(encoding="utf-8")))
    compact_report += "\n\n## Run setup\n\n"
    compact_report += f"- Fleet inventory: `{fleet_markdown_path}`\n"
    compact_report += f"- Fleet JSON: `{fleet_json_path}`\n"
    compact_report += f"- Fail-fast: {'enabled' if args.fail_fast else 'disabled'}\n"
    compact_report += f"- Stopped early: {'yes' if stopped_early else 'no'}\n"
    if stop_reason is not None:
        compact_report += f"- Stop reason: {stop_reason}\n"
    compact_report_path = run_root / "matrix-report.md"
    compact_report_path.write_text(compact_report, encoding="utf-8")
    print(f"==> Wrote {results_path}", flush=True)
    print(f"==> Wrote {compact_report_path}", flush=True)
    print(f"==> Wrote {fleet_json_path}", flush=True)
    print(f"==> Wrote {fleet_markdown_path}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
