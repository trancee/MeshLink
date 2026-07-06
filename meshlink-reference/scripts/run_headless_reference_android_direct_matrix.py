#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from datetime import datetime
from functools import lru_cache
from pathlib import Path
from typing import Any

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from run_headless_reference_live_proof import force_stop_reference_app, shell_join, timestamp


def mermaid_text(value: Any, *, max_len: int = 120) -> str:
    text = "—" if value is None else str(value)
    text = re.sub(r"\s+", " ", text.replace("\r", " "))
    text = text.strip()
    text = text.replace("`", "'")
    text = text.replace("{", "(").replace("}", ")")
    text = text.replace("[", "(").replace("]", ")")
    text = text.replace("<", "&lt;").replace(">", "&gt;")
    if len(text) > max_len:
        text = text[: max_len - 1].rstrip() + "…"
    return text or "—"

PROOF_SCRIPT = Path("meshlink-reference/scripts/run_headless_reference_android_direct_proof.py")
DEFAULT_ANDROID_READY_SECONDS = 30.0
DEFAULT_CAPTURE_TIMEOUT_SECONDS = 180.0
DEFAULT_PAIR_TIMEOUT_SECONDS = 240.0
DEFAULT_MIN_ANDROID_API_LEVEL = 33
DEFAULT_FALLBACK_TRANSPORT = "gatt"
DEFAULT_PRIMARY_TRANSPORT = "meshlink"
REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MATRIX_RUN_ROOT = REPO_ROOT / "reports" / "android-direct-proof-fleet" / "runs"

ANDROID_MODELS: dict[str, str] = {}


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
        help="Optional cap on the number of directed pairs to run for a partial run",
    )
    parser.add_argument(
        "--android-ready-seconds",
        type=float,
        default=DEFAULT_ANDROID_READY_SECONDS,
        help="Passive startup wait per pair (kept short so transport-start failures surface quickly)",
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
        help="Outer timeout per pair so a hung proof script cannot stall the whole run",
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
        help="Stop the run once failures exceed this threshold when fail-fast is disabled",
    )
    return parser.parse_args(argv)


KNOWN_ADB_STATES = ("device", "offline", "unauthorized", "no permissions", "recovery", "sideload", "bootloader")
# adb can assign a "name (N)" de-dup suffix (with a literal space) to wireless
# device IDs when it sees a duplicate mDNS advertisement, so the id itself may
# contain whitespace. Split on the first known state keyword instead of the
# first whitespace run to avoid truncating those IDs.
DEVICE_LINE_RE = re.compile(
    r"^(?P<id>.+?)\s+(?P<state>" + "|".join(re.escape(state) for state in KNOWN_ADB_STATES) + r")\b(?:\s+.*)?$"
)


def adb_devices() -> list[str]:
    result = subprocess.run(["adb", "devices"], check=True, capture_output=True, text=True)
    devices: list[str] = []
    for line in result.stdout.splitlines()[1:]:
        stripped = line.strip()
        if not stripped:
            continue
        match = DEVICE_LINE_RE.match(stripped)
        if match is not None and match.group("state") == "device":
            devices.append(match.group("id"))
    return dedupe_wireless_mdns_duplicates(devices)


MDNS_DUPLICATE_SUFFIX_RE = re.compile(r"\s\(\d+\)")


def dedupe_wireless_mdns_duplicates(device_ids: list[str]) -> list[str]:
    """Collapse duplicate mDNS advertisements of the same wireless device.

    adb can list the same wireless device more than once when it observes a
    duplicate mDNS advertisement, appending a "<host> (N)" de-dup suffix to
    one of the entries (e.g. "adb-XYZ (2)._adb-tls-connect._tcp" alongside
    the canonical "adb-XYZ._adb-tls-connect._tcp"). Both ids resolve to the
    same physical hardware, so treating them as separate devices would
    generate bogus self-pairs. Dedupe by the device's real hardware serial
    number, preferring the id without the "(N)" suffix when both are seen.
    """
    canonical_id_by_hardware_serial: dict[str, str] = {}
    ordered_ids: list[str] = []
    for device_id in device_ids:
        hardware_serial = adb_device_serialno(device_id) or device_id
        existing_id = canonical_id_by_hardware_serial.get(hardware_serial)
        if existing_id is None:
            canonical_id_by_hardware_serial[hardware_serial] = device_id
            ordered_ids.append(device_id)
        elif MDNS_DUPLICATE_SUFFIX_RE.search(existing_id) and not MDNS_DUPLICATE_SUFFIX_RE.search(device_id):
            canonical_id_by_hardware_serial[hardware_serial] = device_id
            ordered_ids[ordered_ids.index(existing_id)] = device_id
    return ordered_ids


@lru_cache(maxsize=None)
def adb_device_serialno(device_id: str) -> str | None:
    try:
        result = subprocess.run(
            ["adb", "-s", device_id, "shell", "getprop", "ro.serialno"],
            check=True,
            capture_output=True,
            text=True,
        )
    except subprocess.CalledProcessError:
        return None
    serial = result.stdout.strip()
    return serial or None


@lru_cache(maxsize=None)
def adb_device_model(serial: str) -> str:
    try:
        result = subprocess.run(
            ["adb", "-s", serial, "shell", "getprop", "ro.product.model"],
            check=True,
            capture_output=True,
            text=True,
        )
    except subprocess.CalledProcessError:
        return serial
    model = result.stdout.strip()
    return model or serial


def slugify_device_label(value: str) -> str:
    slug = re.sub(r"[^a-zA-Z0-9]+", "_", value).strip("_").lower()
    return slug or "device"


def build_device_labels(attached_devices: list[str], android_models: dict[str, str]) -> dict[str, str]:
    slug_counts: dict[str, int] = {}
    for serial in attached_devices:
        slug = slugify_device_label(android_models.get(serial, serial))
        slug_counts[slug] = slug_counts.get(slug, 0) + 1

    labels: dict[str, str] = {}
    seen: dict[str, int] = {}
    for serial in attached_devices:
        slug = slugify_device_label(android_models.get(serial, serial))
        if slug_counts[slug] > 1:
            seen[slug] = seen.get(slug, 0) + 1
            labels[serial] = f"{slug}_{seen[slug]}"
        else:
            labels[serial] = slug
    return labels


def build_pairs(attached_devices: list[str], android_models: dict[str, str]) -> list[dict[str, str]]:
    device_labels = build_device_labels(attached_devices, android_models)
    pairs: list[dict[str, str]] = []
    for sender in attached_devices:
        for passive in attached_devices:
            if sender == passive:
                continue
            pairs.append(
                {
                    "label": f"{device_labels[sender]}_{device_labels[passive]}",
                    "sender": sender,
                    "passive": passive,
                }
            )
    return pairs


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


def clear_reference_app_data(android_serial: str) -> None:
    command = ["adb", "-s", android_serial, "shell", "pm", "clear", "ch.trancee.meshlink.reference"]
    completed = subprocess.run(command, capture_output=True, text=True, check=False)
    stdout = (completed.stdout or "").strip()
    stderr = (completed.stderr or "").strip()
    message = stdout or stderr or "(no output)"
    print(f"==> Android clear app data result ({shell_join(command)}): {message}", flush=True)


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
        # subprocess.run() kills the child on timeout (SIGKILL), so the proof script's own
        # `finally`-guarded cleanup (which force-stops the reference app on both roles) never
        # gets to run. Without this, a single timed-out pair leaves the app running on one or
        # both phones, contaminating the next pair's run with a stale MeshLink session. Force-stop
        # failures are swallowed here so they never mask the real timeout result below.
        for serial in (sender, passive):
            try:
                force_stop_reference_app(serial)
            except Exception as cleanup_error:
                print(
                    f"==> Force-stop after timeout failed for {serial}: {cleanup_error}",
                    flush=True,
                )
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
    sender_focus = summary.get("senderDiscoveryFocus") or {}
    passive_focus = summary.get("passiveDiscoveryFocus") or {}
    return {
        "status": summary.get("status"),
        "failureStage": summary.get("failureStage"),
        "failureReason": summary.get("failureReason"),
        "routeStage": summary.get("routeStage"),
        "routeEvidence": summary.get("routeEvidence"),
        "senderRouteStage": summary.get("senderRouteStage"),
        "passiveRouteStage": summary.get("passiveRouteStage"),
        "senderForeignScanIgnoredCount": sender_focus.get("foreignScanIgnoredCount"),
        "passiveForeignScanIgnoredCount": passive_focus.get("foreignScanIgnoredCount"),
        "senderScanResultCount": sender_focus.get("scanResultCount"),
        "passiveScanResultCount": passive_focus.get("scanResultCount"),
        "senderScanAcceptedCount": sender_focus.get("scanAcceptedCount"),
        "passiveScanAcceptedCount": passive_focus.get("scanAcceptedCount"),
        "senderScanParseSkippedCount": sender_focus.get("scanParseSkippedCount"),
        "passiveScanParseSkippedCount": passive_focus.get("scanParseSkippedCount"),
        "senderScanTargetMismatchCount": sender_focus.get("scanTargetMismatchCount"),
        "passiveScanTargetMismatchCount": passive_focus.get("scanTargetMismatchCount"),
        "senderDiscoveryFocus": sender_focus,
        "passiveDiscoveryFocus": passive_focus,
        "senderSelectedPeerId": sender_focus.get("selectedPeerId"),
        "passiveSelectedPeerId": passive_focus.get("selectedPeerId"),
        "senderFirstSnapshotTransition": sender_focus.get("firstSnapshotTransition"),
        "passiveFirstSnapshotTransition": passive_focus.get("firstSnapshotTransition"),
        "senderTopAcceptedPeers": sender_focus.get("topAcceptedPeers"),
        "passiveTopAcceptedPeers": passive_focus.get("topAcceptedPeers"),
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


PEER_ID_PATTERN = re.compile(r"\bpeer=([A-Za-z0-9._:-]+)\b")


def extract_peer_id_from_evidence(evidence: str | None) -> str | None:
    if not evidence:
        return None
    match = PEER_ID_PATTERN.search(evidence)
    if match is None:
        return None
    return match.group(1)


def read_passive_peer_id(serial: str, app_id: str, retries: int = 5, delay_s: float = 1.0) -> str | None:
    discovery_seed_path = "files/automation-discovery-seed.txt"
    xml_path = f"shared_prefs/meshlink-{app_id}.xml"
    identity_key = f"identity:{app_id}:x25519-public"
    for _ in range(retries):
        seed_result = subprocess.run(
            ["adb", "-s", serial, "shell", "run-as", "ch.trancee.meshlink.reference", "cat", discovery_seed_path],
            check=False,
            capture_output=True,
            text=True,
        )
        if seed_result.returncode == 0:
            seed_text = seed_result.stdout.strip()
            if seed_text:
                return seed_text.splitlines()[0].strip()
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
                if item.get("name") == identity_key and item.text:
                    return item.text.strip()
            for item in root.findall(".//string"):
                if item.get("name", "").endswith(":x25519-public") and item.text:
                    return item.text.strip()
        time.sleep(delay_s)
    print(f"==> Passive peer id unavailable for {serial}; continuing without a seeded target peer")
    return None


def compact_status(summary: dict[str, Any]) -> dict[str, Any]:
    sender_focus = summary.get("senderDiscoveryFocus") or {}
    passive_focus = summary.get("passiveDiscoveryFocus") or {}
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
        "senderForeignScanIgnoredCount": sender_focus.get("foreignScanIgnoredCount"),
        "passiveForeignScanIgnoredCount": passive_focus.get("foreignScanIgnoredCount"),
        "senderScanResultCount": sender_focus.get("scanResultCount"),
        "passiveScanResultCount": passive_focus.get("scanResultCount"),
        "senderScanAcceptedCount": sender_focus.get("scanAcceptedCount"),
        "passiveScanAcceptedCount": passive_focus.get("scanAcceptedCount"),
        "senderScanParseSkippedCount": sender_focus.get("scanParseSkippedCount"),
        "passiveScanParseSkippedCount": passive_focus.get("scanParseSkippedCount"),
        "senderScanTargetMismatchCount": sender_focus.get("scanTargetMismatchCount"),
        "passiveScanTargetMismatchCount": passive_focus.get("scanTargetMismatchCount"),
        "senderDiscoveryFocus": sender_focus,
        "passiveDiscoveryFocus": passive_focus,
        "senderSelectedPeerId": sender_focus.get("selectedPeerId"),
        "passiveSelectedPeerId": passive_focus.get("selectedPeerId"),
        "senderFirstSnapshotTransition": sender_focus.get("firstSnapshotTransition"),
        "passiveFirstSnapshotTransition": passive_focus.get("firstSnapshotTransition"),
        "senderTopAcceptedPeers": sender_focus.get("topAcceptedPeers"),
        "passiveTopAcceptedPeers": passive_focus.get("topAcceptedPeers"),
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
    top_failure_explanation = {
        "preflight/install": "preflight or install problems prevented the directed pair from entering the proof path",
        "launch timeout": "launch timeouts blocked the pair before route establishment",
        "capture/route stall": "capture and route progression stalled before the proof flow completed",
        "passed": "all processed pairs passed",
    }.get(top_failure_bucket, top_failure_bucket)

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

    sender_ignored_total = sum(
        int(row.get("initial", {}).get("senderForeignScanIgnoredCount") or 0)
        + int(row.get("final", {}).get("senderForeignScanIgnoredCount") or 0)
        for row in results
    )
    passive_ignored_total = sum(
        int(row.get("initial", {}).get("passiveForeignScanIgnoredCount") or 0)
        + int(row.get("final", {}).get("passiveForeignScanIgnoredCount") or 0)
        for row in results
    )

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
        f"Foreign scan summary: sender ignored {sender_ignored_total} · passive ignored {passive_ignored_total}",
        f"Scan-path summary: sender results {sum(int(row.get('initial', {}).get('senderScanResultCount') or 0) + int(row.get('final', {}).get('senderScanResultCount') or 0) for row in results)} accepted {sum(int(row.get('initial', {}).get('senderScanAcceptedCount') or 0) + int(row.get('final', {}).get('senderScanAcceptedCount') or 0) for row in results)} · passive results {sum(int(row.get('initial', {}).get('passiveScanResultCount') or 0) + int(row.get('final', {}).get('passiveScanResultCount') or 0) for row in results)} accepted {sum(int(row.get('initial', {}).get('passiveScanAcceptedCount') or 0) + int(row.get('final', {}).get('passiveScanAcceptedCount') or 0) for row in results)}",
        "- How to read this report: sender/passive foreign-scan counts are summed across initial + final passes for the fleet overview, while pair reports show the same counts per run.",
        "",
        "## Mermaid overview",
        "",
        "```mermaid",
        "sequenceDiagram",
        "    autonumber",
        "    participant Matrix",
        "    participant Fleet",
        "    participant Run",
        "    participant Stop",
        f"    note over Matrix: runRoot={mermaid_text(run_root or '—', max_len=40)} · fleet={mermaid_text(Path(fleet_inventory_path).name if fleet_inventory_path else '—', max_len=40)}",
        "    rect rgba(30, 64, 175, 0.40)",
        f"        Matrix->>Fleet: capture inventory ({total_pairs if total_pairs is not None else 'unknown'} pairs)",
        f"        Matrix->>Run: prepare directed run ({completed_pairs} completed)",
        f"        Fleet-->>Matrix: inventory ready ({passing_count} passing · {failing_count} failing)",
        f"        note over Fleet,Run: failure bucket so far = {mermaid_text(top_failure_bucket, max_len=40)}",
        "    end",
        "    rect rgba(236, 253, 245, 0.55)",
        f"        Run->>Run: execute pair lane across {completed_pairs} completed pairs",
        "        Run->>Run: classify outcomes by failure stage",
        f"        note over Run: top failure bucket = {mermaid_text(top_failure_bucket, max_len=40)}",
        "        alt at least one passing pair",
        f"            Run-->>Matrix: {passing_count} passing pairs recorded",
        "        else no passing pairs",
        "            Run-->>Matrix: no successful pairs",
        "        end",
        "    end",
        "    rect rgba(254, 242, 242, 0.55)",
        "        alt stopped early",
        "            Run->>Stop: stopped early",
        f"            note over Stop: failure summary recorded in report",
        "        else run completed",
        "            Run->>Stop: all processed pairs recorded",
        f"            note over Stop: failure summary recorded in report",
        "        end",
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


def format_elapsed_from_markers(
    start_line: str | None,
    end_line: str | None,
    *,
    fallback_seconds: Any = None,
) -> str:
    if start_line and end_line and len(start_line) >= 18 and len(end_line) >= 18:
        try:
            start = datetime.strptime(f"2001-{start_line[:18]}", "%Y-%m-%d %H:%M:%S.%f")
            end = datetime.strptime(f"2001-{end_line[:18]}", "%Y-%m-%d %H:%M:%S.%f")
            if end >= start:
                return f"{(end - start).total_seconds():.1f}s"
        except ValueError:
            pass
    if fallback_seconds is None:
        return "—"
    try:
        return f"{float(fallback_seconds):.1f}s"
    except (TypeError, ValueError):
        return str(fallback_seconds)


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

    def startup_transport_mode(
        summary: dict[str, Any], log_key: str, fallback_transport: str | None = None
    ) -> tuple[str | None, str | None, str | None]:
        run_dir = summary.get("runDir")
        evidence = summary.get("evidence") or {}
        log_name = evidence.get(log_key)
        if run_dir and log_name:
            log_path = Path(run_dir) / log_name
            if log_path.exists():
                for line in log_path.read_text(encoding="utf-8", errors="replace").splitlines():
                    if "start() with l2capPsm=" in line:
                        try:
                            psm = int(line.split("start() with l2capPsm=", 1)[1].split()[0])
                        except (IndexError, ValueError):
                            continue
                        return ("L2CAP" if psm > 0 else "GATT", "start()" if psm == 0 else line, line)
                    if "gatt.benchmark.start() -> Started" in line or "gatt.notify.start() -> Started" in line:
                        return ("GATT", line, line)
        return (fallback_transport, None, None)

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

    startup_timing = initial.get("startupTiming") or {}
    sender_startup = startup_timing.get("sender") or {}
    passive_startup = startup_timing.get("passive") or {}
    sender_install = startup_timing.get("install") or {}

    sender_startup_transport, sender_startup_transport_evidence, sender_startup_transport_raw = startup_transport_mode(
        initial, "senderLogcat", fallback_transport=transport_label
    )
    passive_startup_transport, passive_startup_transport_evidence, passive_startup_transport_raw = startup_transport_mode(
        initial, "passiveLogcat", fallback_transport=transport_label
    )
    sender_startup_elapsed = format_elapsed_from_markers(
        sender_startup.get("line"),
        sender_startup_transport_raw,
        fallback_seconds=sender_install.get("senderSeconds"),
    )
    passive_startup_elapsed = format_elapsed_from_markers(
        passive_startup.get("line"),
        passive_startup_transport_raw,
        fallback_seconds=sender_install.get("passiveSeconds"),
    )

    sender_connection = "🔌 USB"
    passive_connection = "🔌 USB"

    intro = (
        f"Pair {index:02d} ({pair['label']}) is a {initial.get('status')} initial run over {sender_model} → {passive_model}. "
        f"The sender started {sender_startup_transport or 'unknown'} transport, the passive side started {passive_startup_transport or 'unknown'} transport, and the pair stalled at {final.get('failureStage') or initial.get('failureStage') or 'unknown'} before route establishment. "
        f"Foreign scan summary: initial sender ignored {initial.get('senderForeignScanIgnoredCount', '—')} · initial passive ignored {initial.get('passiveForeignScanIgnoredCount', '—')} · final sender ignored {final.get('senderForeignScanIgnoredCount', '—')} · final passive ignored {final.get('passiveForeignScanIgnoredCount', '—')}"
    )

    lines = [
        f"# Pair {index:02d} — {pair['label']}",
        "",
        "## Introduction",
        "",
        intro,
        "",
        "### How to read this report",
        "- The foreign-scan summary in the intro aggregates initial + final runs for this pair.",
        "- The detailed initial/final counts below let you see whether scan noise was one-sided or symmetric.",
        "",
        "## Setup",
        "",
        f"- Sender: {sender_model} ({pair['sender']})",
        f"- Passive: {passive_model} ({pair['passive']})",
        f"- Sender API level: {sender_api_level if sender_api_level is not None else 'unknown'}",
        f"- Passive API level: {passive_api_level if passive_api_level is not None else 'unknown'}",
        f"- Sender connection: {sender_connection}",
        f"- Passive connection: {passive_connection}",
        f"- Matrix transport summary: `{transport_label}`",
        f"- Pair report path: `{pair_report_path}`",
        f"- Fleet inventory: `{fleet_inventory_path}`",
        f"- Peer lookup time: {peer_lookup_elapsed}",
        f"- Initial run dir: `{initial.get('runDir') or initial.get('summaryPath') or '—'}`",
        f"- Final run dir: `{final.get('runDir') or '—'}`",
        f"- Target peer id: {target_peer_id or 'not resolved'}",
        "- How to read this report: the foreign-scan summary above aggregates the initial + final runs; the per-pair counts below are broken out per run.",
        "- How to read this report: the sender and passive counts are treated separately so you can spot whether the mesh hash noise is localized or symmetric.",
        "",
        "## Result",
        "",
        f"- Initial status: {initial.get('status')} ({initial.get('failureStage') or '—'}) in {(initial.get('timings') or {}).get('totalSeconds') or initial.get('elapsedSeconds') or '—'}s",
        f"- Final status: {final.get('status')} ({final.get('failureStage') or '—'}) in {(final.get('timings') or {}).get('totalSeconds') or final.get('elapsedSeconds') or '—'}s",
        f"- Initial failure reason: {initial.get('failureReason') or 'None.'}",
        f"- Final failure reason: {final.get('failureReason') or 'None.'}",
        f"- Route stage: {final.get('routeStage') or initial.get('routeStage') or 'unknown'}",
        f"- Route evidence: {final.get('routeEvidence') or initial.get('routeEvidence') or '—'}",
        "",
        "## Transport evidence",
        "",
        f"- Sender transport mode: `{sender_startup_transport or transport_label}`",
        f"  - `{sender_startup_transport_evidence or sender_startup_transport_raw or 'start()'}`",
        f"  - Startup marker: `{sender_startup.get('line') or '—'}`",
        f"  - Elapsed: {sender_startup_elapsed}",
        f"- Passive transport mode: `{passive_startup_transport or transport_label}`",
        f"  - `{passive_startup_transport_evidence or passive_startup_transport_raw or 'start()'}`",
        f"  - Startup marker: `{passive_startup.get('line') or '—'}`",
        f"  - Elapsed: {passive_startup_elapsed}",
        "- `scan found ...` lines remain peer-discovery evidence only and are not used as transport source.",
        "",
        "## Mermaid sequence diagram",
        "",
        "```mermaid",
        "sequenceDiagram",
        "    autonumber",
        "    participant Matrix",
        f"    participant Sender as {sender_model}",
        f"    participant Passive as {passive_model}",
        f"    Matrix->>Sender: sender transport start ({sender_startup_elapsed})",
        f"    Sender-->>Matrix: transport start recorded ({sender_startup_elapsed})",
        f"    Matrix->>Passive: passive transport start ({passive_startup_elapsed})",
        f"    Passive-->>Matrix: transport start recorded ({passive_startup_elapsed})",
        f"    Matrix->>Matrix: wait for passive peer id ({peer_lookup_elapsed})",
        f"    Sender->>Passive: discovery and route establishment ({final_elapsed if final.get('status') != 'skipped' else '—'})",
        f"    Sender->>Passive: send guided payload ({final_elapsed if final.get('status') != 'skipped' else '—'})",
        f"    Matrix-->>Matrix: failure summary recorded in report ({final_elapsed})",
        "```",
        "",
        "## Mermaid timeline",
        "",
        "```mermaid",
        "flowchart LR",
        f"    A[Sender transport start<br/>{sender_startup_elapsed}] --> B[Passive transport start<br/>{passive_startup_elapsed}]",
        f"    B --> C[Wait for passive peer id<br/>{peer_lookup_elapsed}]",
        f"    C --> D[Discovery and route establishment<br/>{final_elapsed if final.get('status') != 'skipped' else '—'}]",
        f"    D --> E[Send guided payload<br/>{final_elapsed if final.get('status') != 'skipped' else '—'}]",
        "    E --> F[Failure explanation<br/>see Result section]",
        "```",
        "",
        "## Connections",
        "",
        f"- Sender: {sender_connection}",
        f"- Passive: {passive_connection}",
        "",
        "## Evidence summary",
        "",
        f"- Sender startup marker: `{sender_startup.get('line') or '—'}`",
        f"- Passive startup marker: `{passive_startup.get('line') or '—'}`",
        f"- Route evidence: {final.get('routeEvidence') or initial.get('routeEvidence') or '—'}",
        f"- Passive route evidence: {final.get('passiveRouteEvidence') or initial.get('passiveRouteEvidence') or '—'}",
        "",
    ]
    lines.extend(evidence_rows(initial, 'Initial'))
    lines.extend([
        "",
        "## Startup timing",
        "",
        "```json",
        json.dumps(initial.get('startupTiming') or {}, indent=2, sort_keys=True),
        "```",
        "",
        "## Captured evidence map",
        "",
        "```json",
        json.dumps({'initial': initial.get('captured') or {}, 'final': final.get('captured') or {}}, indent=2, sort_keys=True),
        "```",
        "",
        "## Evidence files",
        "",
        "- sender_logcat.log",
        "- passive_logcat.log",
        "- summary.json",
        "",
    ])
    return "\n".join(lines)
def load_progress(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    payload = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(payload, list):
        return payload
    if isinstance(payload, dict):
        return payload.get("results") or []
    raise TypeError("progress.json must contain a list of results or an object with results")


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    attached_devices = adb_devices()
    ANDROID_MODELS.clear()
    ANDROID_MODELS.update({serial: adb_device_model(serial) for serial in attached_devices})
    available_pairs = build_pairs(attached_devices, ANDROID_MODELS)
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
        clear_reference_app_data(pair["sender"])
        clear_reference_app_data(pair["passive"])
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
        peer_lookup_started = time.monotonic()
        target_peer_id = read_passive_peer_id(pair["passive"], app_id)
        if target_peer_id is None:
            target_peer_id = (
                extract_peer_id_from_evidence(initial.get("passiveRouteEvidence"))
                or extract_peer_id_from_evidence(initial.get("routeEvidence"))
            )
        peer_lookup_seconds = round(time.monotonic() - peer_lookup_started, 1)
        if target_peer_id is None:
            print(
                "==> Passive peer id unavailable; continuing without a seeded target peer",
                flush=True,
            )
        else:
            print(
                f"==> Seeded final pass from discovered peer {target_peer_id}",
                flush=True,
            )
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
        progress_path.write_text(
            json.dumps(
                {
                    "results": results,
                    "foreignScanSummary": {
                        "senderIgnoredTotal": sum(
                            int(row.get("initial", {}).get("senderForeignScanIgnoredCount") or 0)
                            + int(row.get("final", {}).get("senderForeignScanIgnoredCount") or 0)
                            for row in results
                        ),
                        "passiveIgnoredTotal": sum(
                            int(row.get("initial", {}).get("passiveForeignScanIgnoredCount") or 0)
                            + int(row.get("final", {}).get("passiveForeignScanIgnoredCount") or 0)
                            for row in results
                        ),
                        "legend": "Fleet summary aggregates initial + final ignored counts across all processed pairs; pair reports break those counts down per run.",
                    },
                    "legend": "Fleet summary aggregates initial + final ignored counts across all processed pairs; pair reports break those counts down per run.",
                },
                indent=2,
            ),
            encoding="utf-8",
        )
        results_path.write_text(json.dumps(results, indent=2), encoding="utf-8")
        fleet_inventory["foreignScanSummary"] = {
            "senderIgnoredTotal": sum(
                int(row.get("initial", {}).get("senderForeignScanIgnoredCount") or 0)
                + int(row.get("final", {}).get("senderForeignScanIgnoredCount") or 0)
                for row in results
            ),
            "passiveIgnoredTotal": sum(
                int(row.get("initial", {}).get("passiveForeignScanIgnoredCount") or 0)
                + int(row.get("final", {}).get("passiveForeignScanIgnoredCount") or 0)
                for row in results
            ),
            "legend": "Fleet summary aggregates initial + final ignored counts across all processed pairs; pair reports break those counts down per run.",
        }
        fleet_json_path.write_text(json.dumps(fleet_inventory, indent=2), encoding="utf-8")
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
        print(
            "    foreign scan summary "
            + f"sender ignored={initial.get('senderForeignScanIgnoredCount', '—')} "
            + f"passive ignored={initial.get('passiveForeignScanIgnoredCount', '—')}",
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

    foreign_scan_summary = {
        "senderIgnoredTotal": sum(
            int(row.get("initial", {}).get("senderForeignScanIgnoredCount") or 0)
            + int(row.get("final", {}).get("senderForeignScanIgnoredCount") or 0)
            for row in results
        ),
        "passiveIgnoredTotal": sum(
            int(row.get("initial", {}).get("passiveForeignScanIgnoredCount") or 0)
            + int(row.get("final", {}).get("passiveForeignScanIgnoredCount") or 0)
            for row in results
        ),
        "legend": "Fleet summary aggregates initial + final ignored counts across all processed pairs; pair reports break those counts down per run.",
    }
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
                "foreignScanSummary": foreign_scan_summary,
            },
            indent=2,
        ),
        encoding="utf-8",
    )

    progress_summary = {
        "foreignScanSummary": foreign_scan_summary,
        "legend": "Fleet summary aggregates initial + final ignored counts across all processed pairs; pair reports break those counts down per run.",
    }
    progress_path.write_text(
        json.dumps({"results": results, **progress_summary}, indent=2),
        encoding="utf-8",
    )
    compact_report = render_compact_report(results, state=json.loads(state_path.read_text(encoding="utf-8")))
    compact_report += "\n\n## Run setup\n\n"
    compact_report += f"- Fleet inventory: `{fleet_markdown_path}`\n"
    compact_report += f"- Fleet JSON: `{fleet_json_path}`\n"
    compact_report += "- `foreignScanSummary` is written to both `fleet.json` and `progress.json` for downstream tooling.\n"
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
