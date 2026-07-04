#!/usr/bin/env python3

from __future__ import annotations

import argparse
import html
import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from run_headless_reference_live_proof import (
    ANDROID_ACTIVITY,
    ANDROID_EXTRA_APP_ID,
    ANDROID_EXTRA_MODE,
    ANDROID_EXTRA_ROLE,
    ANDROID_EXTRA_SCENARIO,
    ANDROID_EXTRA_STORAGE,
    ANDROID_EXTRA_UI_AUTOMATION,
    ANDROID_PACKAGE,
    ANDROID_PROOF_COMPLETE_PATTERN,
    AUTOMATION_MODE_LIVE_PROOF,
    BackgroundProcess,
    DEFAULT_ANDROID_READY_SECONDS,
    DEFAULT_CAPTURE_TIMEOUT_SECONDS,
    android_logcat_tags,
    android_runtime_permissions,
    android_sdk_int,
    ensure_android_device_ready,
    force_stop_reference_app,
    grant_android_runtime_permissions,
    install_android_app,
    uninstall_android_package,
    read_android_app_file,
    run,
    shell_join,
    timestamp,
    verify_android_runtime_permissions,
)

DEFAULT_APP_ID_PREFIX = "demo.meshlink.reference.android-direct"
DIRECT_GUIDED_SCENARIO = "direct-guided"
ANDROID_PROOF_PACKAGE = "ch.trancee.meshlink.proof.android"
ANDROID_PROOF_ACTIVITY = f"{ANDROID_PROOF_PACKAGE}/.MainActivity"
TARGET_PEER = "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_TARGET_PEER_ID"
ANDROID_PROOF_INSTALL_TASK = ":meshlink-proof:android:installDebug"
ANDROID_START_TIMEOUT_SECONDS = 12.0
ANDROID_USB_INSTALL_TIMEOUT_SECONDS = 120.0
ANDROID_WIRELESS_INSTALL_TIMEOUT_SECONDS = 240.0


def android_proof_install_timeout_seconds(android_serial: str) -> float:
    return (
        ANDROID_WIRELESS_INSTALL_TIMEOUT_SECONDS
        if is_wireless_android_serial(android_serial)
        else ANDROID_USB_INSTALL_TIMEOUT_SECONDS
    )


def is_wireless_android_serial(android_serial: str) -> bool:
    return "_adb-tls-connect._tcp" in android_serial


POST_RESULT_IDLE_SECONDS = 2.0
POST_RESULT_PASSIVE_EXPORT_TIMEOUT_SECONDS = 12.0
POST_PASSIVE_START_SETTLE_SECONDS = 5.0
LOGCAT_TIMESTAMP_PATTERN = re.compile(
    r"^(?P<month>\d{2})-(?P<day>\d{2}) (?P<hour>\d{2}):(?P<minute>\d{2}):(?P<second>\d{2})(?:\.(?P<millis>\d{3}))?"
)
SENDER_LOGCAT_NAME = "sender_logcat.log"
PASSIVE_LOGCAT_NAME = "passive_logcat.log"
SENDER_START_NAME = "sender_start.txt"
PASSIVE_START_NAME = "passive_start.txt"
ANDROID_HISTORY_NAME = "android_history.json"
ANDROID_EXPORT_NAME = "android_export.json"
ANDROID_EXTRA_DISABLE_AUTO_SEND = "meshlink.disableAutoSend"
SENDER_PROOF_COMPLETE_NEEDLE = "REFERENCE_AUTOMATION proof.complete role=sender"
SENDER_PROOF_FAILED_NEEDLE = "REFERENCE_AUTOMATION proof.failed role=sender"
PASSIVE_PROOF_COMPLETE_NEEDLE = "REFERENCE_AUTOMATION proof.complete role=passive"
# NOTE: The Android reference app (MainActivity.kt's logAutomationStartupStage) never emits a
# literal "REFERENCE_AUTOMATION started mode=..." line -- that marker only exists in the iOS-only
# verify_ios_sender_log() required-marker list in run_headless_reference_live_proof.py. It was
# copied into these Android-only lists but never matched real app output, so verify_sender_log()/
# verify_passive_log() would always raise SystemExit here regardless of whether the proof actually
# succeeded. The equivalent Android evidence -- mode + role recorded at startup -- is already
# covered by the "REFERENCE_AUTOMATION startup stage=activity.onCreate mode=... role=..." marker
# below, so the stale duplicate is removed rather than reimplemented.
SENDER_REQUIRED_LOG_MARKERS = [
    f"scenario={DIRECT_GUIDED_SCENARIO}",
    "REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER",
    "REFERENCE_AUTOMATION startup-state=activity.onCreate role=SENDER",
    "autoStartMesh=true",
    "autoSendHello=true",
    "REFERENCE_AUTOMATION peer.discovered role=SENDER",
    "REFERENCE_AUTOMATION send.requested role=sender",
]
PASSIVE_REQUIRED_LOG_MARKERS = [
    f"scenario={DIRECT_GUIDED_SCENARIO}",
    "REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE",
    "REFERENCE_AUTOMATION startup-state=activity.onCreate role=PASSIVE",
    "autoStartMesh=true",
]
PASSIVE_PROOF_REQUIRED_LOG_MARKERS = [
    "MeshLink proof app ready on",
    "gatt.benchmark.start() -> Started",
]
TRANSPORT_REQUIRED_MARKERS = [
    "start() with l2capPsm=",
    "refreshDiscoveryState started=true suspended=false scanner=true advertiser=true",
    "scan started",
    "advertising started mode=2 tx=3 connectable=true",
]
@dataclass
class AndroidDirectCompletions:
    sender_completion: str | None = None
    passive_completion: str | None = None
    export_relative_path: str | None = None
    sender_failed: str | None = None
    startup_state: str | None = None
    startup_evidence: str | None = None


@dataclass(frozen=True)
class RoleArtifacts:
    logcat_name: str
    start_name: str


ROLE_ARTIFACTS = {
    "sender": RoleArtifacts(logcat_name=SENDER_LOGCAT_NAME, start_name=SENDER_START_NAME),
    "passive": RoleArtifacts(logcat_name=PASSIVE_LOGCAT_NAME, start_name=PASSIVE_START_NAME),
}


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Run a retained Android-only MeshLink reference-app direct proof with an Android sender "
            "and Android passive peer. The live-proof path starts a foreground service plus wake lock "
            "during automation to reduce doze risk on aggressive OEM builds."
        )
    )
    parser.add_argument(
        "--sender-android-serial",
        required=True,
        help="ADB serial of the Android sender device",
    )
    parser.add_argument(
        "--passive-android-serial",
        required=True,
        help="ADB serial of the Android passive device",
    )
    parser.add_argument(
        "--app-id",
        help=f"Shared MeshLink app ID. Defaults to {DEFAULT_APP_ID_PREFIX}.<timestamp>",
    )
    parser.add_argument(
        "--run-dir",
        help="Directory for retained logs and artifacts. Defaults to /tmp/reference_android_direct_proof_<timestamp>",
    )
    parser.add_argument(
        "--android-ready-seconds",
        type=float,
        default=30.0,
        help=(
            "How long to wait after launching the passive Android peer before starting the sender. "
            "Keep this short so passive transport startup fails fast when the app never reaches the transport-start marker. "
            "Treat the selected sender/passive pair as part of the environment contract; some attached devices advertise and scan but never discover each other. "
            "On this host, the stable pair that completed direct proof was Spacewar (sender) + DN2103 (passive), while Nokia X20 + DN2103 repeatedly failed to discover until the screen stayed awake. "
            "The Nokia X20 runs Android 14 and was observed in Dozing with quick-doze enabled, so keep the screen awake, disable battery optimization if discovery stalls, and rely on the live-proof foreground wake-lock mitigation when the reference app starts it."
        ),
    )
    parser.add_argument(
        "--capture-timeout-seconds",
        type=float,
        default=180.0,
        help="Hard timeout for both Android roles to reach proof completion",
    )
    parser.add_argument(
        "--extra-force-stop-serial",
        action="append",
        default=[],
        help="Extra Android serials whose reference app should be force-stopped before and after the run",
    )
    parser.add_argument(
        "--android-transport-logcat",
        action="store_true",
        help="Also capture MeshLinkTransport Android logcat lines for transport diagnosis",
    )
    parser.add_argument(
        "--target-peer-id",
        help="Optional peer id to seed into the Android sender as a bootstrap hint when orchestration knows it",
    )
    parser.add_argument(
        "--advertisement-carrier",
        choices=("uuid-pair", "uuid-pair-plus-service-data"),
        default="uuid-pair",
        help=(
            "Android advertisement carrier to use for direct proof. Keep the default UUID pair "
            "unless you are isolating discovery-carrier behavior on a specific device."
        ),
    )
    parser.add_argument("--skip-android-install", action="store_true")
    return parser.parse_args(argv)


def evidence_paths(run_dir: Path) -> dict[str, str]:
    del run_dir
    return {
        "senderLogcat": SENDER_LOGCAT_NAME,
        "passiveLogcat": PASSIVE_LOGCAT_NAME,
        "senderStart": SENDER_START_NAME,
        "passiveStart": PASSIVE_START_NAME,
        "androidHistory": ANDROID_HISTORY_NAME,
        "androidExport": ANDROID_EXPORT_NAME,
    }


def captured_evidence(run_dir: Path) -> dict[str, bool]:
    manifest = evidence_paths(run_dir)
    return {
        key: (run_dir / relative_path).exists()
        for key, relative_path in manifest.items()
    }


def render_summary_html(payload: dict[str, Any]) -> str:
    def esc(value: Any) -> str:
        return html.escape("—" if value is None else str(value))

    def fmt_seconds(value: Any) -> str:
        if value is None:
            return "—"
        if isinstance(value, (int, float)):
            return f"{value:.3f}s"
        return esc(value)

    def kv_table(title: str, rows: list[tuple[str, Any]]) -> str:
        body = "".join(f"<tr><th>{esc(label)}</th><td>{esc(value)}</td></tr>" for label, value in rows)
        return f"<section><h2>{esc(title)}</h2><table>{body}</table></section>"

    def foreign_peer_rows(peers: list[dict[str, Any]]) -> str:
        if not peers:
            return "<tr><th>None</th><td>—</td></tr>"
        return "".join(
            f"<tr><th>{esc(peer.get('addr'))}</th><td>remoteMeshHash={esc(peer.get('remoteMeshHash'))} · count={esc(peer.get('count'))}</td></tr>"
            for peer in peers
        )

    def accepted_peer_rows(peers: list[dict[str, Any]]) -> str:
        if not peers:
            return "<tr><th>None</th><td>—</td></tr>"
        return "".join(
            f"<tr><th>{esc(peer.get('peerId'))}</th><td>addr={esc(peer.get('addr'))} · mode={esc(peer.get('mode'))} · count={esc(peer.get('count'))}</td></tr>"
            for peer in peers
        )

    def timing_rows(stage: str, data: dict[str, Any]) -> str:
        message_latency = data.get("sendLatencySeconds") if stage == "Sender" else data.get("receiptSeconds")
        return "".join(
            [
                f"<tr><th>{esc(stage)} startup wait</th><td>{fmt_seconds(data.get('startupWaitSeconds'))}</td></tr>",
                f"<tr><th>{esc(stage)} peer discovery</th><td>{fmt_seconds(data.get('peerDiscoverySeconds'))}</td></tr>",
                f"<tr><th>{esc(stage)} trust connection</th><td>{fmt_seconds(data.get('trustConnectionSeconds'))}</td></tr>",
                f"<tr><th>{esc(stage)} message latency</th><td>{fmt_seconds(message_latency)}</td></tr>",
                f"<tr><th>{esc(stage)} completion</th><td>{fmt_seconds(data.get('sendCompletionSeconds') or data.get('receiptSeconds'))}</td></tr>",
                f"<tr><th>{esc(stage)} transport</th><td>{esc(data.get('transportMode'))}</td></tr>",
            ]
        )

    startup_timing = payload.get("startupTiming", {}) or {}
    timings = payload.get("timings", {}) or {}
    sender_timings = timings.get("sender", {}) or {}
    passive_timings = timings.get("passive", {}) or {}
    status = payload.get("status")
    total_seconds = payload.get("totalSeconds") if payload.get("totalSeconds") is not None else timings.get("totalSeconds")
    capture_timeout_seconds = (
        payload.get("captureTimeoutSeconds")
        if payload.get("captureTimeoutSeconds") is not None
        else timings.get("captureTimeoutSeconds")
    )
    android_ready_seconds = (
        payload.get("androidReadySeconds")
        if payload.get("androidReadySeconds") is not None
        else timings.get("androidReadySeconds")
    )
    transport_mode = payload.get("transportMode") or timings.get("transportMode") or sender_timings.get("transportMode") or passive_timings.get("transportMode")
    badge_class = "pass" if status == "passed" else "fail"
    raw_timings_json = esc(json.dumps(timings, indent=2, sort_keys=True))
    raw_startup_json = esc(json.dumps(startup_timing, indent=2, sort_keys=True))

    sender_completion = payload.get("senderCompletion")
    passive_completion = payload.get("passiveCompletion")
    route_evidence = payload.get("routeEvidence")
    transport_evidence = payload.get("transportEvidence")
    sender_discovery_focus = payload.get("senderDiscoveryFocus", {}) or {}
    passive_discovery_focus = payload.get("passiveDiscoveryFocus", {}) or {}
    sender_selected_peer_id = payload.get("senderSelectedPeerId")
    passive_selected_peer_id = payload.get("passiveSelectedPeerId")
    sender_discovery_focus_lines = sender_discovery_focus.get("lines") or []
    passive_discovery_focus_lines = passive_discovery_focus.get("lines") or []
    passive_foreign_scan_top_peers = passive_discovery_focus.get("topPeers") or []
    sender_accepted_top_peers = sender_discovery_focus.get("topAcceptedPeers") or []
    passive_accepted_top_peers = passive_discovery_focus.get("topAcceptedPeers") or []
    sender_top_accepted_peer = sender_accepted_top_peers[0] if sender_accepted_top_peers else None
    passive_top_accepted_peer = passive_accepted_top_peers[0] if passive_accepted_top_peers else None
    sender_selection_note = (
        "matches target" if sender_selected_peer_id and sender_selected_peer_id == sender_discovery_focus.get("peerId") else "mismatch or none"
    )
    passive_selection_note = (
        "matches target" if passive_selected_peer_id and passive_selected_peer_id == passive_discovery_focus.get("peerId") else "mismatch or none"
    )
    scan_summary = (
        f"sender scans {esc(sender_discovery_focus.get('scanResultCount'))} "
        f"accepted {esc(sender_discovery_focus.get('scanAcceptedCount'))} "
        f"parse-skipped {esc(sender_discovery_focus.get('scanParseSkippedCount'))} "
        f"target-mismatch {esc(sender_discovery_focus.get('scanTargetMismatchCount'))} · "
        f"passive scans {esc(passive_discovery_focus.get('scanResultCount'))} "
        f"accepted {esc(passive_discovery_focus.get('scanAcceptedCount'))} "
        f"parse-skipped {esc(passive_discovery_focus.get('scanParseSkippedCount'))} "
        f"target-mismatch {esc(passive_discovery_focus.get('scanTargetMismatchCount'))}"
    )

    execution_timeline_html = ""
    if any(
        value is not None
        for value in (
            (startup_timing.get("sender") or {}).get("elapsedSeconds"),
            (startup_timing.get("passive") or {}).get("elapsedSeconds"),
            (startup_timing.get("passiveTransport") or {}).get("elapsedSeconds"),
            sender_timings.get("peerDiscoverySeconds"),
            passive_timings.get("peerDiscoverySeconds"),
            sender_timings.get("trustConnectionSeconds"),
            passive_timings.get("trustConnectionSeconds"),
            sender_timings.get("sendLatencySeconds"),
            passive_timings.get("sendLatencySeconds"),
            passive_timings.get("receiptSeconds"),
            sender_timings.get("sendCompletionSeconds"),
        )
    ):
        execution_timeline_html = "\n".join(
            [
                "<section><h2>Execution timeline</h2><table>",
                "<tr><th>Step</th><th>Sender</th><th>Passive</th><th>Evidence / note</th></tr>",
                f"<tr><th>App launch</th><td>{fmt_seconds((startup_timing.get('sender') or {}).get('elapsedSeconds'))}</td><td>{fmt_seconds((startup_timing.get('passive') or {}).get('elapsedSeconds'))}</td><td>{esc((startup_timing.get('sender') or {}).get('line') or (startup_timing.get('passive') or {}).get('line') or 'launch markers')}</td></tr>",
                f"<tr><th>Transport start</th><td>{fmt_seconds((startup_timing.get('passiveTransport') or {}).get('elapsedSeconds'))}</td><td>{fmt_seconds((startup_timing.get('passiveTransport') or {}).get('elapsedSeconds'))}</td><td>{esc((startup_timing.get('passiveTransport') or {}).get('line') or transport_evidence or 'transport marker')}</td></tr>",
                f"<tr><th>Peer discovery</th><td>{fmt_seconds(sender_timings.get('peerDiscoverySeconds'))}</td><td>{fmt_seconds(passive_timings.get('peerDiscoverySeconds'))}</td><td>{esc(sender_timings.get('peerDiscoveryMarker') or passive_timings.get('peerDiscoveryMarker') or 'peer discovered')}</td></tr>",
                f"<tr><th>Trust connection</th><td>{fmt_seconds(sender_timings.get('trustConnectionSeconds'))}</td><td>{fmt_seconds(passive_timings.get('trustConnectionSeconds'))}</td><td>{esc(sender_timings.get('trustConnectionMarker') or passive_timings.get('trustConnectionMarker') or route_evidence or 'trust established')}</td></tr>",
                f"<tr><th>Message exchange</th><td>{fmt_seconds(sender_timings.get('sendLatencySeconds'))}</td><td>{fmt_seconds(passive_timings.get('sendLatencySeconds') or passive_timings.get('receiptSeconds'))}</td><td>{esc(sender_timings.get('sendRequestMarker') or passive_timings.get('sendRequestMarker') or 'message sent and acknowledged')}</td></tr>",
                f"<tr><th>Completion / close</th><td>{fmt_seconds(sender_timings.get('sendCompletionSeconds'))}</td><td>{fmt_seconds(passive_timings.get('sendCompletionSeconds') or passive_timings.get('receiptSeconds') or passive_timings.get('sendLatencySeconds'))}</td><td>{esc(sender_completion or passive_completion or 'connection closed')}</td></tr>",
                "</table></section>",
            ]
        )

    failure_diagnostics_html = ""
    capture_stall_note_html = ""
    foreign_scan_note = None
    passive_foreign_scan_ignored_count = passive_discovery_focus.get("foreignScanIgnoredCount")
    if passive_foreign_scan_ignored_count is not None and int(passive_foreign_scan_ignored_count or 0) > 0:
        top_peer_note = (
            "Top foreign peers: "
            + ", ".join(
                f"{peer.get('addr')}→{peer.get('remoteMeshHash')}×{peer.get('count')}"
                for peer in passive_foreign_scan_top_peers[:3]
            )
            if passive_foreign_scan_top_peers
            else "Top foreign peers: none parsed"
        )
        top_accepted_peer_note = (
            "Top accepted peers: sender "
            + (
                ", ".join(
                    f"{peer.get('peerId')}@{peer.get('addr')}×{peer.get('count')}"
                    for peer in sender_accepted_top_peers[:3]
                )
                if sender_accepted_top_peers
                else "none parsed"
            )
            + " · passive "
            + (
                ", ".join(
                    f"{peer.get('peerId')}@{peer.get('addr')}×{peer.get('count')}"
                    for peer in passive_accepted_top_peers[:3]
                )
                if passive_accepted_top_peers
                else "none parsed"
            )
        )
        foreign_scan_note = (
            "Passive discovery is dropping foreign payloads; inspect the Passive discovery focus section "
            f"and foreign scan summary (passive ignored {passive_foreign_scan_ignored_count}). {top_peer_note}. {top_accepted_peer_note}."
        )
    if status != "passed" or payload.get("failureReason") or payload.get("failureStage"):
        failure_rows = [
            ("Failure stage", payload.get("failureStage")),
            ("Failure reason", payload.get("failureReason")),
            ("Startup state", payload.get("startupState")),
            ("Startup evidence", payload.get("startupStateEvidence")),
            ("Route stage", payload.get("routeStage")),
            ("Route evidence", route_evidence),
            ("Sender route stage", payload.get("senderRouteStage")),
            ("Sender route evidence", payload.get("senderRouteEvidence")),
            ("Passive route stage", payload.get("passiveRouteStage")),
            ("Passive route evidence", payload.get("passiveRouteEvidence")),
            ("Sender completion", sender_completion),
            ("Passive completion", passive_completion),
        ]
        if foreign_scan_note is not None:
            failure_rows.append(("Foreign scan note", foreign_scan_note))
        if payload.get("failureStage") == "capture" or payload.get("routeStage") == "discovery-stalled":
            capture_stall_note = (
                "Capture stalled before route establishment; the passive side is still absorbing foreign payloads "
                "so check the Top foreign peers table for the dominant noisy addresses."
            )
            failure_rows.append(("Capture stall note", capture_stall_note))
            capture_stall_note_html = (
                "<section><h2>Capture stall note</h2>"
                f"<p>{esc(capture_stall_note)}</p></section>"
            )
        failure_diagnostics_html = kv_table("Failure diagnostics", failure_rows)

    html_parts = [
        "<!doctype html>",
        "<html lang=\"en\">",
        "<head>",
        "<meta charset=\"utf-8\" />",
        f"<title>Android direct-proof summary — {esc(payload.get('appId'))}</title>",
        "<style>",
        "body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;margin:24px;line-height:1.45;color:#111}",
        "h1,h2{margin:0 0 12px}",
        ".badge{display:inline-block;padding:4px 10px;border-radius:999px;font-weight:700;text-transform:uppercase;letter-spacing:.04em}",
        ".pass{background:#d1fae5;color:#065f46}.fail{background:#fee2e2;color:#991b1b}",
        "table{border-collapse:collapse;width:100%;margin:12px 0 24px}",
        "th,td{border:1px solid #d1d5db;padding:8px 10px;vertical-align:top;text-align:left}",
        "th{background:#f9fafb;width:280px}",
        "code,pre{background:#f3f4f6;border-radius:8px;padding:12px;overflow:auto}",
        "pre{white-space:pre-wrap;word-break:break-word}",
        ".grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}",
        ".muted{color:#6b7280}",
        "</style>",
        "</head>",
        "<body>",
        f"<h1>Android direct-proof summary <span class=\"badge {badge_class}\">{esc(status)}</span></h1>",
        f"<p class=\"muted\">App ID: {esc(payload.get('appId'))} · Scenario: {esc(payload.get('scenario'))} · Report: {esc(payload.get('htmlReportPath'))}</p>",
        f"<p class=\"muted\">Foreign scan summary: sender ignored {esc((payload.get('senderDiscoveryFocus') or {}).get('foreignScanIgnoredCount'))} · passive ignored {esc((payload.get('passiveDiscoveryFocus') or {}).get('foreignScanIgnoredCount'))}</p>",
        f"<p class=\"muted\">Scan-path summary: {scan_summary}</p>",
        kv_table(
            "Overview",
            [
                ("Status", status),
                ("Failure stage", payload.get("failureStage")),
                ("Failure reason", payload.get("failureReason")),
                ("Startup state", payload.get("startupState")),
                ("Startup evidence", payload.get("startupStateEvidence")),
                ("Sender", payload.get("senderSerial")),
                ("Passive", payload.get("passiveSerial")),
                ("Route stage", payload.get("routeStage")),
                ("Transport mode", transport_mode),
                ("Total time", fmt_seconds(total_seconds)),
                ("Capture timeout", fmt_seconds(capture_timeout_seconds)),
            ],
        ),
        "<section><h2>Startup timing</h2><table>",
        f"<tr><th>Sender startup wait</th><td>{fmt_seconds((startup_timing.get('sender') or {}).get('elapsedSeconds'))}</td></tr>",
        f"<tr><th>Passive startup wait</th><td>{fmt_seconds((startup_timing.get('passive') or {}).get('elapsedSeconds'))}</td></tr>",
        f"<tr><th>Passive transport wait</th><td>{fmt_seconds((startup_timing.get('passiveTransport') or {}).get('elapsedSeconds'))}</td></tr>",
        f"<tr><th>Configured passive wait</th><td>{fmt_seconds((startup_timing.get('launch') or {}).get('passiveStartupWaitSeconds'))}</td></tr>",
        f"<tr><th>Configured transport wait</th><td>{fmt_seconds((startup_timing.get('launch') or {}).get('passiveTransportWaitSeconds'))}</td></tr>",
        f"<tr><th>Configured idle wait</th><td>{fmt_seconds((startup_timing.get('launch') or {}).get('postResultIdleSeconds'))}</td></tr>",
        "</table></section>",
        "<section><h2>Interaction timing</h2><table>",
        timing_rows("Sender", sender_timings),
        timing_rows("Passive", passive_timings),
        "</table></section>",
        kv_table(
            "Transport",
            [
                ("Transport mode", transport_mode),
                ("Transport evidence", transport_evidence),
                ("Sender transport", sender_timings.get("transportMode")),
                ("Passive transport", passive_timings.get("transportMode")),
                ("Sender transport evidence", sender_timings.get("transportEvidence")),
                ("Passive transport evidence", passive_timings.get("transportEvidence")),
            ],
        ),
        execution_timeline_html,
        failure_diagnostics_html,
        capture_stall_note_html,
        kv_table(
            "Evidence",
            [
                ("Sender logcat", payload.get("evidence", {}).get("senderLogcat")),
                ("Passive logcat", payload.get("evidence", {}).get("passiveLogcat")),
                ("Sender start", payload.get("evidence", {}).get("senderStart")),
                ("Passive start", payload.get("evidence", {}).get("passiveStart")),
                ("Android history", payload.get("evidence", {}).get("androidHistory")),
                ("Android export", payload.get("evidence", {}).get("androidExport")),
            ],
        ),
        kv_table(
            "Sender discovery focus",
            [
                ("Peer id", sender_discovery_focus.get("peerId")),
                ("Foreign scan ignored count", sender_discovery_focus.get("foreignScanIgnoredCount")),
                ("Scan result count", sender_discovery_focus.get("scanResultCount")),
                ("Parse-skipped count", sender_discovery_focus.get("scanParseSkippedCount")),
                ("Target-mismatch count", sender_discovery_focus.get("scanTargetMismatchCount")),
                ("Accepted count", sender_discovery_focus.get("scanAcceptedCount")),
                ("Selected peer id", sender_selected_peer_id),
                ("Target match", sender_discovery_focus.get("peerMatch")),
                ("Selection note", sender_selection_note),
                ("First snapshot transition", sender_discovery_focus.get("firstSnapshotTransition")),
                ("Top accepted peer id", (sender_top_accepted_peer or {}).get("peerId")),
                ("Matching line count", len(sender_discovery_focus_lines)),
            ],
        ),
        f"<section><h2>Sender discovery lines</h2><pre>{esc('\n'.join(sender_discovery_focus_lines) if sender_discovery_focus_lines else '—')}</pre></section>",
        kv_table(
            "Passive discovery focus",
            [
                ("Peer id", passive_discovery_focus.get("peerId")),
                ("Foreign scan ignored count", passive_discovery_focus.get("foreignScanIgnoredCount")),
                ("Scan result count", passive_discovery_focus.get("scanResultCount")),
                ("Parse-skipped count", passive_discovery_focus.get("scanParseSkippedCount")),
                ("Target-mismatch count", passive_discovery_focus.get("scanTargetMismatchCount")),
                ("Accepted count", passive_discovery_focus.get("scanAcceptedCount")),
                ("Selected peer id", passive_selected_peer_id),
                ("Target match", passive_discovery_focus.get("peerMatch")),
                ("Selection note", passive_selection_note),
                ("First snapshot transition", passive_discovery_focus.get("firstSnapshotTransition")),
                ("Top accepted peer id", (passive_top_accepted_peer or {}).get("peerId")),
                ("Matching line count", len(passive_discovery_focus_lines)),
            ],
        ),
        f"<section><h2>Passive discovery lines</h2><pre>{esc('\n'.join(passive_discovery_focus_lines) if passive_discovery_focus_lines else '—')}</pre></section>",
        f"<section><h2>Top foreign peers</h2><table>{foreign_peer_rows(passive_foreign_scan_top_peers)}</table></section>",
        f"<section><h2>Sender top accepted peers</h2><table>{accepted_peer_rows(sender_accepted_top_peers)}</table></section>",
        f"<section><h2>Passive top accepted peers</h2><table>{accepted_peer_rows(passive_accepted_top_peers)}</table></section>",
        "<section><h2>Completion lines</h2>",
        f"<h3>Sender</h3><pre>{esc(sender_completion)}</pre>",
        f"<h3>Passive</h3><pre>{esc(passive_completion)}</pre>",
        f"<h3>Route evidence</h3><pre>{esc(route_evidence)}</pre>",
        "</section>",
        f"<details><summary>Raw startup timing JSON</summary><pre>{raw_startup_json}</pre></details>",
        f"<details><summary>Raw timing JSON</summary><pre>{raw_timings_json}</pre></details>",
        "</body></html>",
    ]
    return "\n".join(html_parts)


def write_summary(run_dir: Path, payload: dict[str, Any]) -> None:
    payload = dict(payload)
    payload.setdefault("htmlReportPath", "summary.html")
    (run_dir / "summary.json").write_text(json.dumps(payload, indent=2), encoding="utf-8")
    (run_dir / payload["htmlReportPath"]).write_text(render_summary_html(payload), encoding="utf-8")


def read_text(path: Path) -> str:
    if not path.exists():
        return ""
    return path.read_text(encoding="utf-8", errors="replace")


def sender_log_path(run_dir: Path) -> Path:
    return run_dir / SENDER_LOGCAT_NAME


def passive_log_path(run_dir: Path) -> Path:
    return run_dir / PASSIVE_LOGCAT_NAME


def extract_discovered_peer_id(log_text: str) -> str | None:
    for line in log_text.splitlines():
        if "REFERENCE_AUTOMATION peer.discovered" not in line:
            continue
        if "role=passive" not in line.lower():
            continue
        match = re.search(r"peer=([^\s]+)", line)
        if match is not None:
            return match.group(1)
    return None


def extract_target_peer_id(log_text: str) -> str | None:
    for line in log_text.splitlines():
        if "targetPeerId=" not in line:
            continue
        match = re.search(r"targetPeerId=([^\s]+)", line)
        if match is not None:
            return match.group(1)
    return None


def extract_selected_peer_id(log_text: str) -> str | None:
    fallback: str | None = None
    for line in log_text.splitlines():
        if "peer.snapshot reason=" not in line or "selectedPeerId=" not in line:
            continue
        match = re.search(r"selectedPeerId=([^\s]+)", line)
        if match is None:
            continue
        selected_peer_id = match.group(1)
        if selected_peer_id != "none":
            return selected_peer_id
        fallback = fallback or selected_peer_id
    return fallback


def extract_first_peer_snapshot_transition(log_text: str) -> str | None:
    for line in log_text.splitlines():
        if "peer.snapshot reason=" not in line or "count=" not in line:
            continue
        match = re.search(r"count=(\d+)", line)
        if match is None:
            continue
        if int(match.group(1)) > 0:
            return line.strip()
    return None


def count_foreign_scan_ignored_lines(log_text: str) -> int:
    return sum(
        1
        for line in log_text.splitlines()
        if "ignoring discovery payload with mismatched meshHash" in line
    )


def count_scan_result_lines(log_text: str) -> int:
    return sum(1 for line in log_text.splitlines() if "scan result#" in line)


def count_scan_parse_skipped_lines(log_text: str) -> int:
    return sum(1 for line in log_text.splitlines() if "scan discovery skipped" in line)


def count_scan_target_mismatch_lines(log_text: str) -> int:
    return sum(1 for line in log_text.splitlines() if "scan discovery target mismatch" in line)


def count_scan_accepted_lines(log_text: str) -> int:
    return sum(1 for line in log_text.splitlines() if "scan accepted " in line)


def summarize_foreign_scan_peers(log_text: str, limit: int = 3) -> list[dict[str, Any]]:
    counts: dict[tuple[str, str], int] = {}
    for line in log_text.splitlines():
        if "ignoring discovery payload with mismatched meshHash" not in line:
            continue
        addr_match = re.search(r"addr=([^\s]+)", line)
        remote_match = re.search(r"remoteMeshHash=([^\s]+)", line)
        if addr_match is None or remote_match is None:
            continue
        key = (addr_match.group(1), remote_match.group(1))
        counts[key] = counts.get(key, 0) + 1
    peers = [
        {"addr": addr, "remoteMeshHash": remote_mesh_hash, "count": count}
        for (addr, remote_mesh_hash), count in counts.items()
    ]
    peers.sort(key=lambda item: (-item["count"], item["addr"], item["remoteMeshHash"]))
    return peers[:limit]


def summarize_accepted_scan_peers(log_text: str, limit: int = 3) -> list[dict[str, Any]]:
    counts: dict[str, int] = {}
    first_seen: dict[str, dict[str, str]] = {}
    for line in log_text.splitlines():
        if "scan accepted " not in line:
            continue
        peer_match = re.search(r"peerId=([^\s]+)", line)
        if peer_match is None:
            continue
        peer_id = peer_match.group(1)
        counts[peer_id] = counts.get(peer_id, 0) + 1
        first_seen.setdefault(
            peer_id,
            {
                "peerId": peer_id,
                "addr": re.search(r"addr=([^\s]+)", line).group(1) if re.search(r"addr=([^\s]+)", line) else "—",
                "mode": re.search(r"mode=([^\s]+)", line).group(1) if re.search(r"mode=([^\s]+)", line) else "—",
            },
        )
    peers = [
        {"peerId": peer_id, "addr": first_seen[peer_id]["addr"], "mode": first_seen[peer_id]["mode"], "count": count}
        for peer_id, count in counts.items()
    ]
    peers.sort(key=lambda item: (-item["count"], item["peerId"]))
    return peers[:limit]


def extract_discovery_focus_lines(
    log_text: str,
    peer_id: str | None,
    *,
    include_summary_lines: bool = True,
    include_peer_lines: bool = True,
) -> list[str]:
    focus_lines: list[str] = []
    peer_id_tokens = tuple(
        token
        for token in [
            peer_id,
            f"peerId={peer_id}" if peer_id else None,
            f"hintPeerId={peer_id}" if peer_id else None,
            f"targetPeerId={peer_id}" if peer_id else None,
        ]
        if token is not None
    )
    for line in log_text.splitlines():
        normalized = line.strip()
        if include_summary_lines and (
            "REFERENCE_AUTOMATION startup.meshHashSummary" in normalized
            or "discovery.summary" in normalized
        ):
            focus_lines.append(normalized)
            continue
        if not include_peer_lines or peer_id is None:
            continue
        if any(token in normalized for token in peer_id_tokens):
            focus_lines.append(normalized)
    return focus_lines


def extract_sender_completion(log_text: str) -> str | None:
    return next(
        (line.strip() for line in log_text.splitlines() if SENDER_PROOF_COMPLETE_NEEDLE in line),
        None,
    )


def extract_sender_failure(log_text: str) -> str | None:
    return next(
        (line.strip() for line in log_text.splitlines() if SENDER_PROOF_FAILED_NEEDLE in line),
        None,
    )


def extract_startup_state(log_text: str) -> tuple[str | None, str | None]:
    latest_state: str | None = None
    latest_line: str | None = None
    for line in log_text.splitlines():
        if "startup-state=" not in line:
            continue
        match = re.search(r"startup-state=([a-z0-9_.-]+)", line)
        if match is None:
            continue
        latest_state = match.group(1)
        latest_line = line.strip()
    return latest_state, latest_line


def extract_route_observation(log_text: str) -> tuple[str | None, str | None]:
    stage: str | None = None
    evidence: str | None = None
    for line in log_text.splitlines():
        normalized = line.strip()
        if "sender.discovery.stalled role=" in line:
            stage = "sender-discovery-stalled"
            evidence = normalized
        elif "sender.discovery.pending role=" in line:
            stage = "sender-discovery-pending"
            evidence = normalized
        elif "discovery.stalled role=" in line:
            stage = "discovery-stalled"
            evidence = normalized
        elif "discovery.pending role=" in line:
            stage = "discovery-pending"
            evidence = normalized
        elif "peer.discovered role=" in line or "GATT notify benchmark discovered service" in line:
            stage = "peer-discovered"
            evidence = normalized
        if "transport.handshake.message1.send" in line:
            stage = "handshake-message1-send"
            evidence = normalized
        elif "HOP_SESSION_FAILED" in line:
            stage = "hop-failed"
            evidence = normalized
        elif "NO_ROUTE_AVAILABLE" in line or "route-unavailable" in line or "routeAvailable=false" in line:
            stage = "route-unavailable"
            evidence = normalized
        elif "route.pending role=" in line:
            stage = "route-pending"
            evidence = normalized
        elif "route.ready role=" in line or "GATT notify benchmark notifications enabled" in line:
            stage = "route-discovered"
            evidence = normalized
        elif "ROUTE_DISCOVERED" in line or "routeAvailable=true" in line:
            stage = "route-discovered"
            evidence = normalized
        elif "GATT notify benchmark start token=" in line:
            stage = "hop-established"
            evidence = normalized
        elif "HOP_SESSION_ESTABLISHED" in line:
            stage = "hop-established"
            evidence = normalized
        elif "DELIVERY_SUCCEEDED" in line:
            # A successful delivery is a recovery signal: it means any prior
            # transient route-unavailable/hop-failed blip (e.g. the routine
            # delivery.send.routeRefreshed -> DELIVERY_RETRY_SCHEDULED ->
            # DELIVERY_SUCCEEDED retry sequence) was resolved, so it must
            # supersede that earlier stage rather than leaving a stale
            # "route-unavailable"/"hop-failed" reading that would make
            # route_stall_failure_reason() bail out on an already-succeeded
            # exchange.
            stage = "delivery-succeeded"
            evidence = normalized
    return stage, evidence


def should_prefer_service_data(sender_android_serial: str, passive_android_serial: str) -> bool:
    flaky_pairs = {
        frozenset({"1f1dad34", "GX6CTR500184"}),
        frozenset({"1f1dad34", "EQUGS85LJNEIO7Z5"}),
        frozenset({"42004386e43c8589", "GX6CTR500184"}),
        frozenset({"42004386e43c8589", "EQUGS85LJNEIO7Z5"}),
    }
    return frozenset({sender_android_serial, passive_android_serial}) in flaky_pairs




def extract_passive_completion(log_text: str) -> tuple[str | None, str | None]:
    for line in log_text.splitlines():
        if PASSIVE_PROOF_COMPLETE_NEEDLE not in line:
            continue
        match = ANDROID_PROOF_COMPLETE_PATTERN.search(line)
        export_relative_path = match.group("export") if match is not None else None
        return line.strip(), export_relative_path
    return None, None


def infer_passive_export_path(log_path: Path) -> str | None:
    log_text = read_text(log_path)
    _completion_line, export_relative_path = extract_passive_completion(log_text)
    return export_relative_path


def collect_android_completions(run_dir: Path) -> AndroidDirectCompletions:
    sender_log = read_text(sender_log_path(run_dir))
    passive_log = read_text(passive_log_path(run_dir))
    passive_completion, export_relative_path = extract_passive_completion(passive_log)
    startup_state, startup_evidence = extract_startup_state(passive_log)
    if startup_state is None:
        startup_state, startup_evidence = extract_startup_state(sender_log)
    return AndroidDirectCompletions(
        sender_completion=extract_sender_completion(sender_log),
        passive_completion=passive_completion,
        export_relative_path=export_relative_path,
        sender_failed=extract_sender_failure(sender_log),
        startup_state=startup_state,
        startup_evidence=startup_evidence,
    )


def collect_android_completions_best_effort(run_dir: Path) -> AndroidDirectCompletions:
    sender_log = read_text(sender_log_path(run_dir))
    passive_log = read_text(passive_log_path(run_dir))
    passive_completion: str | None = None
    export_relative_path: str | None = None
    for line in passive_log.splitlines():
        if PASSIVE_PROOF_COMPLETE_NEEDLE not in line:
            continue
        passive_completion = line.strip()
        match = ANDROID_PROOF_COMPLETE_PATTERN.search(line)
        export_relative_path = match.group("export") if match is not None else None
        break
    startup_state, startup_evidence = extract_startup_state(passive_log)
    if startup_state is None:
        startup_state, startup_evidence = extract_startup_state(sender_log)
    return AndroidDirectCompletions(
        sender_completion=extract_sender_completion(sender_log),
        passive_completion=passive_completion,
        export_relative_path=export_relative_path,
        sender_failed=extract_sender_failure(sender_log),
        startup_state=startup_state,
        startup_evidence=startup_evidence,
    )


def parse_logcat_timestamp_seconds(line: str) -> float | None:
    match = LOGCAT_TIMESTAMP_PATTERN.match(line)
    if match is None:
        return None
    hour = int(match.group("hour"))
    minute = int(match.group("minute"))
    second = int(match.group("second"))
    millis = int(match.group("millis") or 0)
    return float(hour * 3600 + minute * 60 + second) + (millis / 1000.0)


def extract_marker_timing(
    log_text: str,
    markers: tuple[str, ...],
    *,
    after_seconds: float | None = None,
) -> tuple[str | None, float | None]:
    for line in log_text.splitlines():
        if not any(marker in line for marker in markers):
            continue
        timestamp_seconds = parse_logcat_timestamp_seconds(line)
        if after_seconds is not None and timestamp_seconds is not None and timestamp_seconds < after_seconds:
            continue
        return line.strip(), timestamp_seconds
    return None, None


def extract_transport_mode(log_text: str) -> tuple[str | None, str | None]:
    def transport_mode_from_label(raw_label: str) -> str | None:
        label = raw_label.strip().lower()
        if label in {"meshlink", "l2cap"}:
            return "L2CAP"
        if label in {
            "gatt",
            "gattprototype",
            "gatt-prototype",
            "gattnotify",
            "gattnotifyprototype",
            "gatt-notify",
            "gatt-notify-prototype",
        }:
            return "GATT"
        return None

    for line in log_text.splitlines():
        match = re.search(r"\bprimaryTransport=([A-Za-z0-9\-]+)\b", line)
        if match is not None:
            transport_mode = transport_mode_from_label(match.group(1))
            if transport_mode is not None:
                return transport_mode, line.strip()
    for line in log_text.splitlines():
        if (
            "transport=gattPrototype" in line
            or "gatt.benchmark.start() -> Started" in line
            or "GATT benchmark completed" in line
            or "GATT benchmark server opening" in line
            or "gatt.start() -> Started" in line
            or "transport=gattNotifyPrototype" in line
            or "gatt.notify.start() -> Started" in line
        ):
            return "GATT", line.strip()
        if "mode=L2CAP" in line:
            return "L2CAP", line.strip()
        if "mode=GATT" in line:
            return "GATT", line.strip()
    for line in log_text.splitlines():
        match = re.search(r"\bl2capPsm=(\d+)\b", line)
        if match is None:
            continue
        transport_mode = "GATT" if int(match.group(1)) == 0 else "L2CAP"
        return transport_mode, line.strip()
    return None, None


def build_timing_snapshot(
    *,
    run_dir: Path,
    startup_timing: dict[str, Any],
    total_seconds: float,
    android_ready_seconds: float,
    capture_timeout_seconds: float,
) -> dict[str, Any]:
    sender_log = read_text(sender_log_path(run_dir))
    passive_log = read_text(passive_log_path(run_dir))

    sender_startup_line, sender_startup_seconds = extract_marker_timing(
        sender_log,
        (
            "startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER",
            "started mode=LIVE_PROOF role=SENDER",
        ),
    )
    sender_peer_line, sender_peer_seconds = extract_marker_timing(
        sender_log,
        ("peer.discovered role=SENDER",),
        after_seconds=sender_startup_seconds,
    )
    sender_trust_line, sender_trust_seconds = extract_marker_timing(
        sender_log,
        ("route.ready role=SENDER", "ROUTE_DISCOVERED", "HOP_SESSION_ESTABLISHED"),
        after_seconds=sender_peer_seconds or sender_startup_seconds,
    )
    sender_send_line, sender_send_seconds = extract_marker_timing(
        sender_log,
        ("send.requested role=sender",),
        after_seconds=sender_trust_seconds or sender_peer_seconds or sender_startup_seconds,
    )
    sender_complete_line, sender_complete_seconds = extract_marker_timing(
        sender_log,
        (SENDER_PROOF_COMPLETE_NEEDLE,),
        after_seconds=sender_send_seconds or sender_startup_seconds,
    )
    passive_startup_line, passive_startup_seconds = extract_marker_timing(
        passive_log,
        (
            "startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE",
            "started mode=LIVE_PROOF role=PASSIVE",
        ),
    )
    passive_peer_line, passive_peer_seconds = extract_marker_timing(
        passive_log,
        ("peer.discovered role=PASSIVE",),
        after_seconds=passive_startup_seconds,
    )
    passive_trust_line, passive_trust_seconds = extract_marker_timing(
        passive_log,
        ("route.ready role=PASSIVE", "ROUTE_DISCOVERED", "HOP_SESSION_ESTABLISHED"),
        after_seconds=passive_peer_seconds or passive_startup_seconds,
    )
    passive_complete_line, passive_complete_seconds = extract_marker_timing(
        passive_log,
        (PASSIVE_PROOF_COMPLETE_NEEDLE,),
        after_seconds=passive_trust_seconds or passive_startup_seconds,
    )
    passive_complete_line, passive_complete_seconds = extract_marker_timing(
        passive_log,
        (PASSIVE_PROOF_COMPLETE_NEEDLE,),
        after_seconds=passive_trust_seconds or passive_startup_seconds,
    )
    sender_transport_mode, sender_transport_evidence = extract_transport_mode(sender_log)
    passive_transport_mode, passive_transport_evidence = extract_transport_mode(passive_log)
    transport_mode = passive_transport_mode or sender_transport_mode
    transport_evidence = passive_transport_evidence or sender_transport_evidence

    def delta_seconds(start_seconds: float | None, end_seconds: float | None) -> float | None:
        if start_seconds is None or end_seconds is None:
            return None
        if end_seconds < start_seconds:
            return None
        return round(end_seconds - start_seconds, 3)

    def role_timing(
        *,
        startup_seconds: float | None,
        startup_line: str | None,
        peer_seconds: float | None,
        peer_line: str | None,
        trust_seconds: float | None,
        trust_line: str | None,
        send_seconds: float | None,
        send_line: str | None,
        complete_seconds: float | None,
        complete_line: str | None,
        transport_mode: str | None,
        transport_evidence: str | None,
        startup_observation: dict[str, Any],
        receipt_label: str,
    ) -> dict[str, Any]:
        return {
            "startupWaitSeconds": startup_observation.get("elapsedSeconds"),
            "startupObserved": startup_observation.get("observed"),
            "startupMarker": startup_line,
            "peerDiscoverySeconds": delta_seconds(startup_seconds, peer_seconds),
            "peerDiscoveryMarker": peer_line,
            "trustConnectionSeconds": delta_seconds(peer_seconds or startup_seconds, trust_seconds),
            "trustConnectionMarker": trust_line,
            "sendLatencySeconds": delta_seconds(send_seconds, complete_seconds),
            receipt_label: delta_seconds(startup_seconds, complete_seconds),
            "sendRequestMarker": send_line,
            "completionMarker": complete_line,
            "transportMode": transport_mode,
            "transportEvidence": transport_evidence,
        }

    return {
        "totalSeconds": round(total_seconds, 1),
        "androidReadySeconds": android_ready_seconds,
        "captureTimeoutSeconds": capture_timeout_seconds,
        "transportMode": transport_mode,
        "transportEvidence": transport_evidence,
        "sender": role_timing(
            startup_seconds=sender_startup_seconds,
            startup_line=sender_startup_line,
            peer_seconds=sender_peer_seconds,
            peer_line=sender_peer_line,
            trust_seconds=sender_trust_seconds,
            trust_line=sender_trust_line,
            send_seconds=sender_send_seconds,
            send_line=sender_send_line,
            complete_seconds=sender_complete_seconds,
            complete_line=sender_complete_line,
            transport_mode=sender_transport_mode,
            transport_evidence=sender_transport_evidence,
            startup_observation=startup_timing.get("sender", {}),
            receipt_label="sendCompletionSeconds",
        ),
        "passive": role_timing(
            startup_seconds=passive_startup_seconds,
            startup_line=passive_startup_line,
            peer_seconds=passive_peer_seconds,
            peer_line=passive_peer_line,
            trust_seconds=passive_trust_seconds,
            trust_line=passive_trust_line,
            send_seconds=passive_peer_seconds,
            send_line=passive_peer_line,
            complete_seconds=passive_complete_seconds,
            complete_line=passive_complete_line,
            transport_mode=passive_transport_mode,
            transport_evidence=passive_transport_evidence,
            startup_observation=startup_timing.get("passive", {}),
            receipt_label="receiptSeconds",
        ),
    }


# NO_ROUTE_AVAILABLE/routeAvailable=false is routine, expected telemetry during
# the delivery layer's own delivery.send.routeRefreshed -> DELIVERY_RETRY_SCHEDULED
# -> DELIVERY_SUCCEEDED retry sequence (see the DELIVERY_SUCCEEDED handling in
# extract_route_observation()); hardware runs have been observed to report
# retryDeadlineMs=15000 for this retry window. Give a bare "route-unavailable"
# reading this much grace before treating it as a terminal stall so the retry
# gets a fair chance to resolve on its own.
ROUTE_UNAVAILABLE_GRACE_SECONDS = 16.0

# discovery-stalled/discovery-pending (and the sender-prefixed variants) are
# emitted by GuidedFirstExchangeViewModel.maybeLogDiscoveryStalled() as a
# one-shot diagnostic fired at a *fixed* ~3-second elapsed-time timer,
# regardless of whether the underlying BLE connection is still actively
# progressing. Hardware runs have shown a GATT connection reach
# CONNECTED -> MTU negotiated -> CCCD-enabled within a couple hundred
# milliseconds of this marker firing, with the LinkIdentity data frame (and
# the resulting peer.discovered/route.ready transition) still in flight.
# Give this marker the same kind of grace window as route-unavailable before
# treating it as a terminal stall.
DISCOVERY_STALL_GRACE_SECONDS = 12.0


def route_stall_failure_reason(
    run_dir: Path,
    *,
    route_unavailable_grace_deadline: float | None = None,
    discovery_stall_grace_deadline: float | None = None,
) -> str | None:
    """Detect genuinely terminal route-level failures that are safe to raise
    immediately, even before the full capture timeout elapses.

    This only fires once a peer has actually been discovered by either role
    and its most recent route-stage marker indicates a real regression (e.g.
    a hop/handshake failure). It deliberately does NOT fire on the one-time
    "discovery.stalled" diagnostic checkpoint that GuidedFirstExchangeViewModel
    emits ~3 seconds after startup (see GuidedFirstExchangeViewModel.kt,
    maybeLogDiscoveryStalled()): that marker is routine telemetry, not a
    terminal failure, and BLE discovery on real hardware can easily take
    longer than 3 seconds while still succeeding well within the configured
    android-ready/capture-timeout budget.

    A bare "route-unavailable" stage (with no accompanying harder failure
    signal) is similarly not immediately terminal: it is routine mid-retry
    telemetry (see ROUTE_UNAVAILABLE_GRACE_SECONDS above). When
    route_unavailable_grace_deadline is an absolute time.monotonic() deadline,
    a bare route-unavailable reading only becomes terminal once that deadline
    has passed. When route_unavailable_grace_deadline is None (e.g. used for
    post-hoc classification after the full capture timeout has already
    elapsed), a bare route-unavailable reading is treated as terminal
    immediately, since there is no more time left to wait for the retry to
    resolve.

    A bare "discovery-stalled"/"discovery-pending" stage (with no accompanying
    harder failure signal) gets the same grace treatment via
    discovery_stall_grace_deadline (see DISCOVERY_STALL_GRACE_SECONDS above):
    it is a one-shot early diagnostic, not proof the connection is dead, and
    real hardware has been observed to complete GATT connect/MTU/CCCD/
    LinkIdentity binding within a couple hundred milliseconds of this marker
    firing."""
    sender_log = read_text(sender_log_path(run_dir))
    passive_log = read_text(passive_log_path(run_dir))
    combined_log_lower = (sender_log + "\n" + passive_log).lower()
    sender_route_stage, sender_route_evidence = extract_route_observation(sender_log)
    passive_route_stage, passive_route_evidence = extract_route_observation(passive_log)
    peer_discovered = (
        "peer.discovered role=passive" in combined_log_lower
        or "peer.discovered role=sender" in combined_log_lower
    )
    if not peer_discovered:
        return None
    route_stage = sender_route_stage or passive_route_stage
    hard_route_failure_stages = {
        "handshake-message1-send",
        "hop-failed",
    }
    if (
        sender_route_stage in hard_route_failure_stages
        or passive_route_stage in hard_route_failure_stages
    ):
        return (
            "Android direct proof stalled at route stage "
            f"sender={sender_route_stage or 'none'} passive={passive_route_stage or 'none'}; "
            f"senderEvidence={sender_route_evidence or 'n/a'} passiveEvidence={passive_route_evidence or 'n/a'}"
        )
    discovery_stall_stages = {
        "discovery-stalled",
        "discovery-pending",
        "sender-discovery-stalled",
        "sender-discovery-pending",
    }
    discovery_stalled = (
        sender_route_stage in discovery_stall_stages
        or passive_route_stage in discovery_stall_stages
    )
    if discovery_stalled:
        if (
            discovery_stall_grace_deadline is not None
            and time.monotonic() < discovery_stall_grace_deadline
        ):
            return None
        return (
            "Android direct proof stalled at route stage "
            f"sender={sender_route_stage or 'none'} passive={passive_route_stage or 'none'}; "
            f"senderEvidence={sender_route_evidence or 'n/a'} passiveEvidence={passive_route_evidence or 'n/a'}"
        )
    route_unavailable = (
        sender_route_stage == "route-unavailable" or passive_route_stage == "route-unavailable"
    )
    if route_unavailable:
        if (
            route_unavailable_grace_deadline is not None
            and time.monotonic() < route_unavailable_grace_deadline
        ):
            return None
        return (
            "Android direct proof stalled at route stage "
            f"sender={sender_route_stage or 'none'} passive={passive_route_stage or 'none'}; "
            f"senderEvidence={sender_route_evidence or 'n/a'} passiveEvidence={passive_route_evidence or 'n/a'}"
        )
    if route_stage is None:
        return (
            "Android direct proof discovered a peer but never emitted a route-stage marker; "
            "sender stalled before route stabilization"
        )
    return None


def transport_failure_reason(run_dir: Path) -> str | None:
    """Full failure classification, including non-terminal diagnostic
    checkpoints such as the one-time "discovery.stalled" marker.

    This should only be used to build a descriptive failure message AFTER
    the caller has already decided to give up (e.g. after the full capture
    timeout has elapsed) — see route_stall_failure_reason() for the subset
    of checks that are safe to use as an early-exit signal while polling."""
    reason = route_stall_failure_reason(run_dir)
    if reason is not None:
        return reason
    sender_log = read_text(sender_log_path(run_dir))
    passive_log = read_text(passive_log_path(run_dir))
    combined_log = sender_log + "\n" + passive_log
    combined_log_lower = combined_log.lower()
    if "sender.discovery.stalled role=" in combined_log_lower:
        return (
            "Android direct proof sender stalled before peer discovery; "
            "classified as a capture stall"
        )
    if "discovery.stalled role=" in combined_log_lower:
        return (
            "Android direct proof reached startup but discovery stalled before peer discovery or route readiness; "
            "classified as a capture stall"
        )
    if all(marker in combined_log for marker in TRANSPORT_REQUIRED_MARKERS):
        return (
            "Android transport reached scan/advertise startup but never emitted peer.discovered or route.ready; "
            "classified as a capture stall"
        )
    return None


def extract_power_state_snapshot(log_text: str) -> str | None:
    for line in reversed(log_text.splitlines()):
        if "REFERENCE_AUTOMATION power.state" in line:
            return line.strip()
    return None


def launch_passive_transport_wait_seconds(
    android_ready_seconds: float,
    _capture_timeout_seconds: float,
) -> float:
    return max(android_ready_seconds, 20.0)


def wait_for_android_completions(
    run_dir: Path,
    timeout_seconds: float,
    sender_android_serial: str,
    passive_android_serial: str,
) -> AndroidDirectCompletions:
    deadline = time.monotonic() + timeout_seconds
    no_peer_deadline = time.monotonic() + min(timeout_seconds, 10.0)
    route_unavailable_grace_deadline: float | None = None
    discovery_stall_grace_deadline: float | None = None
    while time.monotonic() < deadline:
        completions = collect_android_completions(run_dir)
        if completions.sender_failed is not None:
            raise SystemExit(
                "Android sender reported proof.failed before retained completion: "
                f"{completions.sender_failed}"
            )
        if completions.sender_completion:
            return completions
        if route_unavailable_grace_deadline is None or discovery_stall_grace_deadline is None:
            sender_stage, _ = extract_route_observation(read_text(sender_log_path(run_dir)))
            passive_stage, _ = extract_route_observation(read_text(passive_log_path(run_dir)))
            if route_unavailable_grace_deadline is None and "route-unavailable" in (
                sender_stage,
                passive_stage,
            ):
                route_unavailable_grace_deadline = (
                    time.monotonic() + ROUTE_UNAVAILABLE_GRACE_SECONDS
                )
            discovery_stall_stages = {
                "discovery-stalled",
                "discovery-pending",
                "sender-discovery-stalled",
                "sender-discovery-pending",
            }
            if discovery_stall_grace_deadline is None and (
                sender_stage in discovery_stall_stages or passive_stage in discovery_stall_stages
            ):
                discovery_stall_grace_deadline = time.monotonic() + DISCOVERY_STALL_GRACE_SECONDS
        route_stall_reason = route_stall_failure_reason(
            run_dir,
            route_unavailable_grace_deadline=route_unavailable_grace_deadline,
            discovery_stall_grace_deadline=discovery_stall_grace_deadline,
        )
        if route_stall_reason is not None:
            raise SystemExit(route_stall_reason)
        if time.monotonic() >= no_peer_deadline:
            # Keep the explicit no-peer guard for runs that never emit route diagnostics,
            # but fall through to the standard timeout message when no transport reason exists.
            pass
        time.sleep(0.5)

    completions = collect_android_completions(run_dir)
    if completions.sender_failed is not None:
        raise SystemExit(
            "Android sender reported proof.failed before retained completion: "
            f"{completions.sender_failed}"
        )
    missing_roles: list[str] = []
    if completions.passive_completion is None:
        missing_roles.append("passive")
    if completions.sender_completion is None:
        missing_roles.append("sender")
    transport_reason = transport_failure_reason(run_dir)
    if transport_reason is not None:
        raise SystemExit(transport_reason)
    raise SystemExit(
        "Timed out waiting for proof.complete on Android roles: "
        + ", ".join(missing_roles or ["passive"])
    )


def ensure_android_preflight(
    android_serial: str,
    *,
    skip_android_install: bool,
    run_dir: Path,
    install_profile: str = "reference",
) -> dict[str, float | bool]:
    timings: dict[str, float | bool] = {}
    ensure_android_device_ready(android_serial)
    if not skip_android_install:
        install_started_at = time.monotonic()
        try:
            if install_profile == "proof":
                install_android_proof_app(
                    android_serial,
                    run_dir,
                    install_timeout_seconds=android_proof_install_timeout_seconds(android_serial),
                )
            else:
                install_android_app(android_serial, run_dir)
        except TimeoutError as error:
            raise SystemExit(str(error)) from error
        except subprocess.CalledProcessError as error:
            details = (error.stderr or error.output or "").strip()
            detail_suffix = f": {details}" if details else ""
            raise SystemExit(
                f"Android app install failed for '{android_serial}' with exit code {error.returncode}{detail_suffix}"
            ) from error
        timings["installSeconds"] = round(time.monotonic() - install_started_at, 1)
        timings["installReused"] = False
    else:
        timings["installReused"] = True
    permissions_started_at = time.monotonic()
    permission_package = ANDROID_PROOF_PACKAGE if install_profile == "proof" else ANDROID_PACKAGE
    try:
        try:
            if install_profile == "proof":
                verify_android_runtime_permissions_for_package(android_serial, ANDROID_PROOF_PACKAGE)
            else:
                verify_android_runtime_permissions(android_serial)
        except SystemExit:
            print(
                f"==> Android runtime permissions missing after install; retrying grant once for {android_serial}",
                flush=True,
            )
            grant_android_runtime_permissions(android_serial, permission_package)
            if install_profile == "proof":
                verify_android_runtime_permissions_for_package(android_serial, ANDROID_PROOF_PACKAGE)
            else:
                verify_android_runtime_permissions(android_serial)
    except SystemExit as error:
        raise SystemExit(
            f"Android runtime-permission verification failed for '{android_serial}': {error}"
        ) from error
    except subprocess.CalledProcessError as error:
        raise SystemExit(
            "Android runtime-permission verification command failed for "
            f"'{android_serial}' with exit code {error.returncode}"
        ) from error
    timings["permissionsSeconds"] = round(time.monotonic() - permissions_started_at, 1)
    return timings


def validate_serial_configuration(
    *,
    sender_android_serial: str,
    passive_android_serial: str,
    extra_force_stop_serials: list[str],
) -> None:
    if sender_android_serial == passive_android_serial:
        raise SystemExit(
            "Sender and passive Android serials must be different physical devices: "
            f"{sender_android_serial}"
        )

    reserved_serials = {
        sender_android_serial: "sender",
        passive_android_serial: "passive",
    }
    seen_extra_serials: set[str] = set()
    for extra_serial in extra_force_stop_serials:
        if extra_serial in reserved_serials:
            raise SystemExit(
                "Extra force-stop Android serial cannot reuse the "
                f"{reserved_serials[extra_serial]} device: {extra_serial}"
            )
        if extra_serial in seen_extra_serials:
            raise SystemExit(
                "Extra force-stop Android serials must be unique physical devices: "
                f"{extra_serial}"
            )
        seen_extra_serials.add(extra_serial)


def ensure_extra_force_stop_devices_ready(extra_force_stop_serials: list[str]) -> None:
    for extra_serial in extra_force_stop_serials:
        ensure_android_device_ready(extra_serial)


def force_stop_android_package(android_serial: str, android_package: str) -> None:
    run(["adb", "-s", android_serial, "shell", "am", "force-stop", android_package], check=False)


ANDROID_QUIT_VERIFY_ATTEMPTS: int = 5
ANDROID_QUIT_VERIFY_DELAY_SECONDS: float = 1.0


def android_package_running_pids(android_serial: str, android_package: str) -> str:
    result = run(
        ["adb", "-s", android_serial, "shell", "pidof", android_package],
        check=False,
        capture_output=True,
    )
    return (result.stdout or "").strip()


def force_stop_android_package_and_verify_quit(
    android_serial: str, android_package: str
) -> None:
    """Force-stops `android_package` on `android_serial` and polls `pidof` until the
    process is confirmed gone, retrying `am force-stop` on every attempt. Some OEM
    builds have been observed to resurrect a just-force-stopped process for a brief
    window (e.g. a pending BLE GATT callback re-entering the app), so a single
    fire-and-forget force-stop is not always sufficient to guarantee the app has
    actually quit by the time the next test run starts on the same device."""
    for attempt in range(ANDROID_QUIT_VERIFY_ATTEMPTS):
        force_stop_android_package(android_serial, android_package)
        pids = android_package_running_pids(android_serial, android_package)
        if not pids:
            return
        if attempt < ANDROID_QUIT_VERIFY_ATTEMPTS - 1:
            time.sleep(ANDROID_QUIT_VERIFY_DELAY_SECONDS)
    remaining_pids = android_package_running_pids(android_serial, android_package)
    if remaining_pids:
        raise SystemExit(
            f"Android package '{android_package}' on '{android_serial}' did not quit "
            f"after {ANDROID_QUIT_VERIFY_ATTEMPTS} force-stop attempts "
            f"(pids still running: {remaining_pids})"
        )


def verify_android_runtime_permissions_for_package(android_serial: str, android_package: str) -> None:
    result = run(["adb", "-s", android_serial, "shell", "dumpsys", "package", android_package], capture_output=True)
    package_dump = result.stdout
    required_permissions = android_runtime_permissions(android_serial)
    missing_permissions = []
    for permission in required_permissions:
        if f"{permission}: granted=true" not in package_dump:
            missing_permissions.append(permission)
    if missing_permissions:
        raise SystemExit(
            "Android runtime permissions are not granted after installDebug: "
            + ", ".join(missing_permissions)
        )
    print(
        "==> Verified Android runtime permissions: "
        + ", ".join(permission.split('.')[-1] for permission in android_runtime_permissions(android_serial))
    )


def install_android_proof_app(
    android_serial: str,
    run_dir: Path | None = None,
    *,
    install_timeout_seconds: float = 120.0,
) -> None:
    env = os.environ.copy()
    env["ANDROID_SERIAL"] = android_serial
    cache_run_dir = run_dir or Path("/tmp")
    del cache_run_dir
    command = ["./gradlew", ANDROID_PROOF_INSTALL_TASK, "--no-build-cache", "--console=plain"]

    def run_install_once() -> None:
        print(f"==> Installing Android proof app: {shell_join(command)}")
        result = subprocess.run(
            command,
            env=env,
            check=True,
            capture_output=True,
            text=True,
            timeout=install_timeout_seconds,
        )
        print("==> Android install stdout:")
        print(result.stdout.rstrip() or "(empty)")
        print("==> Android install stderr:")
        print(result.stderr.rstrip() or "(empty)")

    uninstall_android_package(android_serial, ANDROID_PROOF_PACKAGE)
    try:
        run_install_once()
    except (TimeoutError, subprocess.TimeoutExpired) as error:
        raise TimeoutError(
            f"Android install timed out after {install_timeout_seconds} seconds while installing proof app"
        ) from error
    except subprocess.CalledProcessError as error:
        details = (error.stderr or error.stdout or "").strip()
        detail_suffix = f": {details}" if details else ""
        print("==> Android install failed; retrying once after uninstalling the existing package")
        uninstall_android_package(android_serial, ANDROID_PROOF_PACKAGE)
        try:
            run_install_once()
        except (TimeoutError, subprocess.TimeoutExpired) as retry_error:
            raise TimeoutError(
                f"Android install timed out after {install_timeout_seconds} seconds while installing proof app"
            ) from retry_error
        except subprocess.CalledProcessError as retry_error:
            retry_details = (retry_error.stderr or retry_error.stdout or "").strip()
            retry_suffix = f": {retry_details}" if retry_details else ""
            raise SystemExit(
                f"Android proof app install failed for '{android_serial}' with exit code {retry_error.returncode}{retry_suffix}"
            ) from retry_error

    grant_android_runtime_permissions(android_serial, ANDROID_PROOF_PACKAGE)
    verify_android_runtime_permissions_for_package(android_serial, ANDROID_PROOF_PACKAGE)


def force_stop_extra_peers(extra_force_stop_serials: list[str]) -> None:
    failures: list[str] = []
    for extra_serial in extra_force_stop_serials:
        try:
            force_stop_android_package_and_verify_quit(extra_serial, ANDROID_PACKAGE)
        except Exception as error:
            failures.append(f"{extra_serial}: {error}")
    if failures:
        raise SystemExit(
            "Failed to isolate extra Android peers before launch: " + "; ".join(failures)
        )


def stop_background_process_before_relaunch(process: BackgroundProcess | None) -> None:
    """Stop a role's previous logcat-capturing BackgroundProcess before replacing the variable
    with a freshly launched one.

    Without this, relaunching a role (e.g. to seed a target-peer-id filter) orphans the old
    `adb logcat` subprocess: it keeps its file descriptor open on the same log file path, and
    since the new process reopens that path in truncating "w" mode (not a new file), the two
    processes end up writing to the same inode concurrently -- corrupting/interleaving the
    captured log content and producing hard-to-diagnose false negatives (e.g. a genuine
    `proof.complete` line silently lost amid interleaved writes from a zombie logcat process).
    """
    if process is not None:
        process.stop()


def cleanup_android_direct_run(
    *,
    sender_process: BackgroundProcess | None,
    passive_process: BackgroundProcess | None,
    sender_android_serial: str,
    passive_android_serial: str,
    extra_force_stop_serials: list[str],
) -> None:
    failures: list[str] = []
    for label, process in (("sender", sender_process), ("passive", passive_process)):
        if process is None:
            continue
        try:
            process.stop()
        except Exception as error:
            failures.append(f"{label} logcat cleanup failed: {error}")

    for android_serial in [
        sender_android_serial,
        passive_android_serial,
        *extra_force_stop_serials,
    ]:
        for android_package in (ANDROID_PACKAGE, ANDROID_PROOF_PACKAGE):
            try:
                force_stop_android_package_and_verify_quit(android_serial, android_package)
            except Exception as error:
                failures.append(f"{android_serial}:{android_package}: {error}")

    if failures:
        raise SystemExit("Android direct cleanup failed: " + "; ".join(failures))


def start_android_role_app(
    *,
    run_dir: Path,
    android_serial: str,
    label: str,
    role: str,
    app_id: str,
    storage_subdirectory: str,
    android_transport_logcat: bool,
    target_peer_id: str | None = None,
    advertisement_carrier: str = "uuid-pair",
    android_activity: str = ANDROID_ACTIVITY,
    android_package: str = ANDROID_PACKAGE,
    disable_auto_send: bool = False,
) -> BackgroundProcess:
    role_artifacts = ROLE_ARTIFACTS[label]
    force_stop_android_package_and_verify_quit(android_serial, android_package)
    run(["adb", "-s", android_serial, "logcat", "-c"])
    logcat_path = run_dir / role_artifacts.logcat_name
    logcat_file = logcat_path.open("w", encoding="utf-8")
    logcat_tags = android_logcat_tags(android_transport_logcat)
    if android_package == ANDROID_PROOF_PACKAGE:
        logcat_tags = ["MeshLinkProof:I", *logcat_tags]
    logcat_process = subprocess.Popen(
        ["adb", "-s", android_serial, "logcat", *logcat_tags],
        stdout=logcat_file,
        stderr=subprocess.STDOUT,
        text=True,
    )
    process = BackgroundProcess(logcat_process, cleanup_actions=[logcat_file.close])
    if android_package == ANDROID_PROOF_PACKAGE:
        command = [
            "adb",
            "-s",
            android_serial,
            "shell",
            "am",
            "start",
            "-n",
            android_activity,
            "--es",
            "meshlink.appId",
            app_id,
        ]
    else:
        command = [
            "adb",
            "-s",
            android_serial,
            "shell",
            "am",
            "start",
            "-n",
            android_activity,
            "--ez",
            ANDROID_EXTRA_UI_AUTOMATION,
            "true",
            "--es",
            ANDROID_EXTRA_MODE,
            AUTOMATION_MODE_LIVE_PROOF,
            "--es",
            ANDROID_EXTRA_STORAGE,
            storage_subdirectory,
            "--es",
            ANDROID_EXTRA_APP_ID,
            app_id,
            "--es",
            ANDROID_EXTRA_ROLE,
            role,
            "--es",
            ANDROID_EXTRA_SCENARIO,
            DIRECT_GUIDED_SCENARIO,
        ]
        if target_peer_id:
            command.extend(["--es", "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_TARGET_PEER_ID", target_peer_id])
        if disable_auto_send:
            command.extend(["--ez", ANDROID_EXTRA_DISABLE_AUTO_SEND, "true"])
        command.extend(
            [
                "--es",
                "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_ADVERTISEMENT_CARRIER",
                advertisement_carrier,
            ]
        )
    app_label = "proof" if android_package == ANDROID_PROOF_PACKAGE else "reference"
    print(f"==> Launching {label} Android {app_label} app ({role}): {shell_join(command)}")
    start_path = run_dir / role_artifacts.start_name
    try:
        start_output = run(
            command,
            capture_output=True,
            timeout=ANDROID_START_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired as error:
        start_path.write_text(
            "Timed out waiting for Android activity launch to return.\n"
            f"Command: {shell_join(command)}\n"
            f"TimeoutSeconds: {ANDROID_START_TIMEOUT_SECONDS}\n",
            encoding="utf-8",
        )
        process.stop()
        raise SystemExit(
            f"{label} Android launch timed out after {ANDROID_START_TIMEOUT_SECONDS} seconds"
        ) from error
    except subprocess.CalledProcessError as error:
        start_path.write_text((error.stdout or "") + (error.stderr or ""), encoding="utf-8")
        process.stop()
        raise
    start_path.write_text(
        start_output.stdout + start_output.stderr,
        encoding="utf-8",
    )
    return process


ANDROID_SHELL_ERROR_MARKERS = (
    "cat:",
    "run-as:",
    "No such file or directory",
    "Permission denied",
    "is not debuggable",
    "not accessible",
)


def looks_like_android_shell_error(text: str) -> bool:
    """adb's exec-out/run-as/cat chain does not always propagate a non-zero
    exit code when the remote file is missing (a known adb limitation), so a
    remote shell error message can otherwise be mistaken for real file
    content (e.g. "cat: files/automation-discovery-seed.txt: No such file or
    directory" being treated as a discovered peer id)."""
    stripped = text.strip()
    if not stripped:
        return False
    return any(stripped.startswith(marker) or marker in stripped for marker in ANDROID_SHELL_ERROR_MARKERS)


def read_passive_peer_id(android_serial: str, app_id: str, retries: int = 60, delay_s: float = 1.0) -> str | None:
    discovery_seed_path = "automation-discovery-seed.txt"
    relative_path = f"../shared_prefs/meshlink-{app_id}.xml"
    for _ in range(retries):
        try:
            discovery_seed_text = read_android_app_file(android_serial, discovery_seed_path).strip()
        except subprocess.CalledProcessError:
            discovery_seed_text = ""
        if (
            discovery_seed_text
            and not discovery_seed_text.lstrip().startswith("<")
            and not looks_like_android_shell_error(discovery_seed_text)
        ):
            return discovery_seed_text.splitlines()[0].strip()
        try:
            xml_text = read_android_app_file(android_serial, relative_path)
        except subprocess.CalledProcessError:
            time.sleep(delay_s)
            continue
        if "x25519-public" in xml_text:
            try:
                root = ET.fromstring(xml_text)
            except ET.ParseError:
                time.sleep(delay_s)
                continue
            for item in root.findall(".//string"):
                name = item.get("name") or ""
                if item.text and (name == TARGET_PEER or name.endswith(":x25519-public")):
                    return item.text.strip()
        time.sleep(delay_s)
    print(f"==> Passive peer id unavailable for {android_serial}; continuing without a seeded target peer")
    return None


def resolve_sender_target_peer_id(
    passive_marker_path: Path,
    discovery_wait_seconds: float,
    discovered_peer_id: str | None,
    target_peer_id: str | None,
) -> str | None:
    sender_target_peer_id = discovered_peer_id or target_peer_id
    if sender_target_peer_id is None:
        sender_target_peer_id = wait_for_discovered_peer_id(passive_marker_path, discovery_wait_seconds)
    return sender_target_peer_id


def launch_android_sender_role_app(
    *,
    run_dir: Path,
    android_serial: str,
    app_id: str,
    storage_subdirectory: str,
    android_transport_logcat: bool,
    target_peer_id: str,
    sender_advertisement_carrier: str,
    disable_auto_send: bool = False,
) -> BackgroundProcess:
    return start_android_role_app(
        run_dir=run_dir,
        android_serial=android_serial,
        label="sender",
        role="sender",
        app_id=app_id,
        storage_subdirectory=storage_subdirectory,
        android_transport_logcat=android_transport_logcat,
        target_peer_id=target_peer_id,
        advertisement_carrier=sender_advertisement_carrier,
        android_activity=ANDROID_ACTIVITY,
        android_package=ANDROID_PACKAGE,
        disable_auto_send=disable_auto_send,
    )



def launch_android_role_apps(
    *,
    run_dir: Path,
    sender_android_serial: str,
    passive_android_serial: str,
    app_id: str,
    storage_subdirectory: str,
    android_transport_logcat: bool,
    target_peer_id: str | None,
    passive_advertisement_carrier: str,
    sender_advertisement_carrier: str,
) -> tuple[BackgroundProcess, BackgroundProcess]:
    with ThreadPoolExecutor(max_workers=2) as executor:
        passive_future = executor.submit(
            start_android_role_app,
            run_dir=run_dir,
            android_serial=passive_android_serial,
            label="passive",
            role="passive",
            app_id=app_id,
            storage_subdirectory=storage_subdirectory,
            android_transport_logcat=android_transport_logcat,
            target_peer_id=None,
            advertisement_carrier=passive_advertisement_carrier,
            android_activity=ANDROID_ACTIVITY,
            android_package=ANDROID_PACKAGE,
        )
        sender_future = executor.submit(
            start_android_role_app,
            run_dir=run_dir,
            android_serial=sender_android_serial,
            label="sender",
            role="sender",
            app_id=app_id,
            storage_subdirectory=storage_subdirectory,
            android_transport_logcat=android_transport_logcat,
            target_peer_id=target_peer_id,
            advertisement_carrier=sender_advertisement_carrier,
                android_activity=ANDROID_ACTIVITY,
            android_package=ANDROID_PACKAGE,
        )
        passive_process = passive_future.result()
        sender_process = sender_future.result()
    return passive_process, sender_process

def wait_for_discovered_peer_id(log_path: Path, timeout_seconds: float) -> str | None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        peer_id = extract_discovered_peer_id(read_text(log_path))
        if peer_id is not None:
            return peer_id
        time.sleep(0.25)
    return extract_discovered_peer_id(read_text(log_path))


def wait_for_log_marker_observation(
    log_path: Path,
    marker: str,
    timeout_seconds: float,
) -> dict[str, Any]:
    started_at = time.monotonic()
    deadline = started_at + timeout_seconds
    alternate_marker = marker.replace("startup stage=activity.onCreate", "started")
    while time.monotonic() < deadline:
        log_text = read_text(log_path)
        for line in log_text.splitlines():
            if marker in line or alternate_marker in line:
                return {
                    "observed": True,
                    "elapsedSeconds": round(time.monotonic() - started_at, 1),
                    "line": line.strip(),
                }
        time.sleep(0.25)
    log_text = read_text(log_path)
    matched_line = next(
        (
            line.strip()
            for line in log_text.splitlines()
            if marker in line or alternate_marker in line
        ),
        None,
    )
    return {
        "observed": matched_line is not None,
        "elapsedSeconds": round(time.monotonic() - started_at, 1),
        "line": matched_line,
    }


def wait_for_log_marker(log_path: Path, marker: str, timeout_seconds: float) -> bool:
    return wait_for_log_marker_observation(log_path, marker, timeout_seconds)["observed"]


def wait_for_android_app_file_marker(
    android_serial: str,
    relative_path: str,
    marker: str,
    timeout_seconds: float,
) -> dict[str, Any]:
    started_at = time.monotonic()
    deadline = started_at + timeout_seconds
    while time.monotonic() < deadline:
        try:
            file_text = read_android_app_file(android_serial, relative_path)
        except subprocess.CalledProcessError:
            file_text = ""
        if marker in file_text:
            return {
                "observed": True,
                "elapsedSeconds": round(time.monotonic() - started_at, 1),
                "line": marker,
            }
        time.sleep(0.25)
    try:
        file_text = read_android_app_file(android_serial, relative_path)
    except subprocess.CalledProcessError:
        file_text = ""
    return {
        "observed": marker in file_text,
        "elapsedSeconds": round(time.monotonic() - started_at, 1),
        "line": marker if marker in file_text else None,
    }


def wait_for_passive_export_completion(run_dir: Path, timeout_seconds: float) -> AndroidDirectCompletions:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        completions = collect_android_completions_best_effort(run_dir)
        if completions.export_relative_path is not None:
            return completions
        time.sleep(0.25)
    return collect_android_completions_best_effort(run_dir)


def verify_sender_log(log_path: Path) -> str:
    log_text = read_text(log_path)
    if not log_text.strip():
        raise SystemExit("Sender log is empty; cannot trust Android-only direct proof completion")
    for marker in SENDER_REQUIRED_LOG_MARKERS:
        if marker not in log_text:
            raise SystemExit(f"Expected sender log to contain '{marker}'")
    completion_line = extract_sender_completion(log_text)
    if completion_line is None:
        raise SystemExit("Missing sender proof.complete line in sender log")
    if SENDER_PROOF_FAILED_NEEDLE in log_text:
        raise SystemExit("Sender log contains proof.failed")
    return completion_line


def verify_passive_log(log_path: Path, *, passive_transport: str = "meshlink") -> str | None:
    log_text = read_text(log_path)
    if not log_text.strip():
        raise SystemExit("Passive log is empty; cannot trust Android-only retained export evidence")
    if passive_transport == "meshlink":
        for marker in PASSIVE_REQUIRED_LOG_MARKERS:
            if marker not in log_text:
                raise SystemExit(f"Expected passive log to contain '{marker}'")
        completion_line, _export_relative_path = extract_passive_completion(log_text)
        if completion_line is not None:
            return completion_line
        receipt_line = next(
            (
                line.strip()
                for line in log_text.splitlines()
                if "REFERENCE_AUTOMATION BENCHMARK receipt sent" in line and "result=Sent" in line
            ),
            None,
        )
        if receipt_line is None:
            raise SystemExit("Missing passive proof.complete line and receipt-sent retained evidence in passive log")
        return receipt_line
    if passive_transport == "gatt":
        proof_app_markers = [
            "MeshLink proof app ready on",
            "gatt.benchmark.start() -> Started",
            "GATT benchmark advertising started",
        ]
        bridge_markers = [
            "REFERENCE_AUTOMATION started mode=LIVE_PROOF role=PASSIVE",
            "GATT benchmark advertising started",
            "gatt.notify.start() -> Started",
        ]
        if all(marker in log_text for marker in proof_app_markers) or all(marker in log_text for marker in bridge_markers):
            return None
        raise SystemExit(
            "Expected passive GATT log to contain either the proof-app or bridge startup markers"
        )
    for marker in PASSIVE_PROOF_REQUIRED_LOG_MARKERS:
        if marker not in log_text:
            raise SystemExit(f"Expected proof-app passive log to contain '{marker}'")
    return None


def summarize_and_verify(
    *,
    sender_android_serial: str,
    passive_android_serial: str,
    run_dir: Path,
    storage_subdirectory: str,
    app_id: str,
    completions: AndroidDirectCompletions,
    startup_timing: dict[str, Any],
    timings: dict[str, Any],
    passive_transport: str = "meshlink",
) -> dict[str, Any]:
    sender_completion_line = verify_sender_log(sender_log_path(run_dir))
    passive_completion_line = verify_passive_log(passive_log_path(run_dir), passive_transport=passive_transport)
    if passive_transport == "meshlink":
        if completions.export_relative_path is None:
            export_relative_path = infer_passive_export_path(passive_log_path(run_dir))
        else:
            export_relative_path = completions.export_relative_path

        history_relative_path = f"live-automation/{storage_subdirectory}/reference/history.json"
        history_json = read_android_app_file(passive_android_serial, history_relative_path)
        (run_dir / ANDROID_HISTORY_NAME).write_text(history_json, encoding="utf-8")
    else:
        export_relative_path = None
        history_json = json.dumps(
            {
                "transport": passive_transport,
                "appId": app_id,
                "note": "proof-app passive retained evidence is log-only",
            },
            indent=2,
        )
        export_json = json.dumps(
            {
                "defaultMode": "redacted-preview",
                "fullPayloadIncluded": False,
                "operatorOptInRecorded": False,
                "transport": passive_transport,
                "note": "proof-app passive export not available",
            },
            indent=2,
        )
        (run_dir / ANDROID_HISTORY_NAME).write_text(history_json, encoding="utf-8")
        (run_dir / ANDROID_EXPORT_NAME).write_text(export_json, encoding="utf-8")

    sender_route_stage, sender_route_evidence = extract_route_observation(read_text(sender_log_path(run_dir)))
    passive_route_stage, passive_route_evidence = extract_route_observation(read_text(passive_log_path(run_dir)))
    route_stage = sender_route_stage or passive_route_stage
    route_evidence = sender_route_evidence or passive_route_evidence

    checks = [('\"historyStatus\": \"RETAINED\"', history_json)]
    export_json = None
    if export_relative_path is not None:
        export_relative_file = f"live-automation/{storage_subdirectory}/{export_relative_path}"
        export_json = read_android_app_file(passive_android_serial, export_relative_file)
        (run_dir / ANDROID_EXPORT_NAME).write_text(export_json, encoding="utf-8")
        checks.extend(
            [
                ('"defaultMode": "redacted-preview"', export_json),
                ('"fullPayloadIncluded": false', export_json),
                ('"operatorOptInRecorded": false', export_json),
            ]
        )
        if '"fullPayload":' in export_json:
            raise SystemExit("Redacted export unexpectedly included fullPayload")

    transport_mode = timings.get("transportMode") or (timings.get("sender") or {}).get("transportMode") or (timings.get("passive") or {}).get("transportMode")
    transport_evidence = timings.get("transportEvidence") or (timings.get("sender") or {}).get("transportEvidence") or (timings.get("passive") or {}).get("transportEvidence")

    summary = {
        "status": "passed",
        "scenario": DIRECT_GUIDED_SCENARIO,
        "appId": app_id,
        "storageSubdirectory": storage_subdirectory,
        "senderPlatform": "android",
        "passivePlatform": "android",
        "senderSerial": sender_android_serial,
        "passiveSerial": passive_android_serial,
        "senderCompletion": sender_completion_line,
        "passiveCompletion": passive_completion_line,
        "senderPowerState": extract_power_state_snapshot(read_text(sender_log_path(run_dir))),
        "passivePowerState": extract_power_state_snapshot(read_text(passive_log_path(run_dir))),
        "startupState": completions.startup_state,
        "startupStateEvidence": completions.startup_evidence,
        "routeStage": route_stage,
        "routeEvidence": route_evidence,
        "senderRouteStage": sender_route_stage,
        "senderRouteEvidence": sender_route_evidence,
        "passiveRouteStage": passive_route_stage,
        "passiveRouteEvidence": passive_route_evidence,
        "routeBoundary": {
            "stage": route_stage,
            "evidence": route_evidence,
            "senderStage": sender_route_stage,
            "senderEvidence": sender_route_evidence,
            "passiveStage": passive_route_stage,
            "passiveEvidence": passive_route_evidence,
        },
        "transportMode": transport_mode,
        "transportEvidence": transport_evidence,
        "startupTiming": startup_timing,
        "timings": timings,
        "htmlReportPath": "summary.html",
        "exportRelativePath": completions.export_relative_path,
        "evidence": evidence_paths(run_dir),
        "captured": captured_evidence(run_dir),
    }
    write_summary(run_dir, summary)
    print("==> Scenario:", DIRECT_GUIDED_SCENARIO)
    print("==> Android sender completion:", sender_completion_line)
    if passive_completion_line is not None:
        print("==> Android passive completion:", passive_completion_line)
    print("==> Android export:", completions.export_relative_path)
    return summary


def failure_summary(
    *,
    run_dir: Path,
    sender_android_serial: str,
    passive_android_serial: str,
    app_id: str,
    storage_subdirectory: str,
    stage: str,
    error_message: str,
    completions: AndroidDirectCompletions,
    startup_timing: dict[str, Any],
    timings: dict[str, Any],
) -> dict[str, Any]:
    sender_log_text = read_text(sender_log_path(run_dir))
    passive_log_text = read_text(passive_log_path(run_dir))
    sender_route_stage, sender_route_evidence = extract_route_observation(sender_log_text)
    passive_route_stage, passive_route_evidence = extract_route_observation(passive_log_text)
    route_stage = sender_route_stage or passive_route_stage
    route_evidence = sender_route_evidence or passive_route_evidence
    transport_mode = timings.get("transportMode") or (timings.get("sender") or {}).get("transportMode") or (timings.get("passive") or {}).get("transportMode")
    transport_evidence = timings.get("transportEvidence") or (timings.get("sender") or {}).get("transportEvidence") or (timings.get("passive") or {}).get("transportEvidence")
    discovered_peer_id = extract_discovered_peer_id(passive_log_text)
    target_peer_id = discovered_peer_id or extract_target_peer_id(sender_log_text)
    sender_selected_peer_id = extract_selected_peer_id(sender_log_text)
    passive_selected_peer_id = extract_selected_peer_id(passive_log_text)
    sender_first_snapshot_transition = extract_first_peer_snapshot_transition(sender_log_text)
    passive_first_snapshot_transition = extract_first_peer_snapshot_transition(passive_log_text)
    sender_discovery_focus_lines = extract_discovery_focus_lines(
        sender_log_text,
        target_peer_id,
        include_summary_lines=False,
    )
    passive_discovery_focus_lines = extract_discovery_focus_lines(
        passive_log_text,
        target_peer_id,
        include_peer_lines=False,
    )
    return {
        "status": "failed",
        "scenario": DIRECT_GUIDED_SCENARIO,
        "appId": app_id,
        "storageSubdirectory": storage_subdirectory,
        "senderPlatform": "android",
        "passivePlatform": "android",
        "senderSerial": sender_android_serial,
        "passiveSerial": passive_android_serial,
        "senderCompletion": completions.sender_completion,
        "passiveCompletion": completions.passive_completion,
        "senderFailure": completions.sender_failed,
        "senderPowerState": extract_power_state_snapshot(sender_log_text),
        "passivePowerState": extract_power_state_snapshot(read_text(passive_log_path(run_dir))),
        "discoveredPeerId": discovered_peer_id,
        "senderDiscoveryFocus": {
            "peerId": target_peer_id,
            "selectedPeerId": sender_selected_peer_id,
            "peerMatch": bool(sender_selected_peer_id and target_peer_id and sender_selected_peer_id == target_peer_id),
            "firstSnapshotTransition": sender_first_snapshot_transition,
            "foreignScanIgnoredCount": count_foreign_scan_ignored_lines(sender_log_text),
            "scanResultCount": count_scan_result_lines(sender_log_text),
            "scanParseSkippedCount": count_scan_parse_skipped_lines(sender_log_text),
            "scanTargetMismatchCount": count_scan_target_mismatch_lines(sender_log_text),
            "scanAcceptedCount": count_scan_accepted_lines(sender_log_text),
            "topAcceptedPeers": summarize_accepted_scan_peers(sender_log_text),
            "lines": sender_discovery_focus_lines,
        },
        "passiveDiscoveryFocus": {
            "peerId": target_peer_id,
            "selectedPeerId": passive_selected_peer_id,
            "peerMatch": bool(passive_selected_peer_id and target_peer_id and passive_selected_peer_id == target_peer_id),
            "firstSnapshotTransition": passive_first_snapshot_transition,
            "foreignScanIgnoredCount": count_foreign_scan_ignored_lines(passive_log_text),
            "scanResultCount": count_scan_result_lines(passive_log_text),
            "scanParseSkippedCount": count_scan_parse_skipped_lines(passive_log_text),
            "scanTargetMismatchCount": count_scan_target_mismatch_lines(passive_log_text),
            "scanAcceptedCount": count_scan_accepted_lines(passive_log_text),
            "topPeers": summarize_foreign_scan_peers(passive_log_text),
            "topAcceptedPeers": summarize_accepted_scan_peers(passive_log_text),
            "lines": passive_discovery_focus_lines,
        },
        "startupState": completions.startup_state,
        "startupStateEvidence": completions.startup_evidence,
        "routeStage": route_stage,
        "routeEvidence": route_evidence,
        "senderRouteStage": sender_route_stage,
        "senderRouteEvidence": sender_route_evidence,
        "passiveRouteStage": passive_route_stage,
        "passiveRouteEvidence": passive_route_evidence,
        "routeBoundary": {
            "stage": route_stage,
            "evidence": route_evidence,
            "senderStage": sender_route_stage,
            "senderEvidence": sender_route_evidence,
            "passiveStage": passive_route_stage,
            "passiveEvidence": passive_route_evidence,
        },
        "transportMode": transport_mode,
        "transportEvidence": transport_evidence,
        "startupTiming": startup_timing,
        "timings": timings,
        "htmlReportPath": "summary.html",
        "exportRelativePath": completions.export_relative_path,
        "failureStage": stage,
        "failureReason": error_message,
        "evidence": evidence_paths(run_dir),
        "captured": captured_evidence(run_dir),
        "partialEvidenceAvailable": any(captured_evidence(run_dir).values()),
    }


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    run_started_at = time.monotonic()
    run_dir = Path(args.run_dir or f"/tmp/reference_android_direct_proof_{timestamp()}")
    run_dir.mkdir(parents=True, exist_ok=True)
    app_id = args.app_id or f"{DEFAULT_APP_ID_PREFIX}.{timestamp()}"
    storage_subdirectory = run_dir.name.replace("/", "_")
    peer_resolution_wait_seconds = min(max(args.android_ready_seconds, 1.0), 5.0)
    discovered_peer_id: str | None = args.target_peer_id
    stage = "input-validation"
    sender_process: BackgroundProcess | None = None
    passive_process: BackgroundProcess | None = None
    completions = AndroidDirectCompletions(
        sender_completion=None,
        passive_completion=None,
        export_relative_path=None,
        sender_failed=None,
    )
    startup_timing: dict[str, Any] = {
        "sender": {"observed": False, "elapsedSeconds": None, "line": None},
        "passive": {"observed": False, "elapsedSeconds": None, "line": None},
        "passiveTransport": {"observed": False, "elapsedSeconds": None, "line": None},
        "launch": {
            "passiveStartupWaitSeconds": args.android_ready_seconds,
            "passiveTransportWaitSeconds": args.android_ready_seconds,
            "postResultIdleSeconds": POST_RESULT_IDLE_SECONDS,
        },
        "totalSeconds": None,
    }

    try:
        validate_serial_configuration(
            sender_android_serial=args.sender_android_serial,
            passive_android_serial=args.passive_android_serial,
            extra_force_stop_serials=args.extra_force_stop_serial,
        )

        stage = "preflight"
        with ThreadPoolExecutor(max_workers=2) as executor:
            passive_future = executor.submit(
                ensure_android_preflight,
                args.passive_android_serial,
                skip_android_install=args.skip_android_install,
                run_dir=run_dir,
                install_profile="reference",
            )
            sender_future = executor.submit(
                ensure_android_preflight,
                args.sender_android_serial,
                skip_android_install=args.skip_android_install,
                run_dir=run_dir,
                install_profile="reference",
            )
            passive_preflight = passive_future.result()
            sender_preflight = sender_future.result()
        ensure_extra_force_stop_devices_ready(args.extra_force_stop_serial)
        print(f"==> Android preflight ready in {time.monotonic() - run_started_at:.1f}s")

        try:
            stage = "launch"
            force_stop_extra_peers(args.extra_force_stop_serial)
            passive_transport_is_meshlink = True
            passive_process = start_android_role_app(
                run_dir=run_dir,
                android_serial=args.passive_android_serial,
                label="passive",
                role="passive",
                app_id=app_id,
                storage_subdirectory=storage_subdirectory,
                android_transport_logcat=args.android_transport_logcat,
                target_peer_id=None,
                advertisement_carrier=args.advertisement_carrier,
                android_activity=ANDROID_ACTIVITY,
                android_package=ANDROID_PACKAGE,
            )
            print(f"==> Android passive launched at +{time.monotonic() - run_started_at:.1f}s")
            passive_marker_path = passive_log_path(run_dir)
            sender_marker_path = sender_log_path(run_dir)
            if passive_transport_is_meshlink:
                print(
                    f"==> Waiting up to {args.android_ready_seconds} seconds for Android passive startup marker"
                )
                passive_startup_observation = wait_for_log_marker_observation(
                    passive_marker_path,
                    "REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE",
                    args.android_ready_seconds,
                )
                startup_timing["passive"] = passive_startup_observation
                if not passive_startup_observation["observed"]:
                    print(
                        f"==> Android passive startup marker not observed after {args.android_ready_seconds} seconds; continuing with discovery wait"
                    )
                if sender_process is None and discovered_peer_id is not None:
                    sender_process = start_android_role_app(
                        run_dir=run_dir,
                        android_serial=args.sender_android_serial,
                        label="sender",
                        role="sender",
                        app_id=app_id,
                        storage_subdirectory=storage_subdirectory,
                        android_transport_logcat=args.android_transport_logcat,
                        target_peer_id=discovered_peer_id,
                        advertisement_carrier=(
                            "uuid-pair-plus-service-data"
                            if should_prefer_service_data(args.sender_android_serial, args.passive_android_serial)
                            else args.advertisement_carrier
                        ),
                    )
                    print(f"==> Android sender launched at +{time.monotonic() - run_started_at:.1f}s")
                passive_transport_timeout_seconds = max(
                    args.android_ready_seconds,
                    min(args.capture_timeout_seconds * 0.3, 20.0),
                )
                startup_timing["launch"]["passiveTransportWaitSeconds"] = passive_transport_timeout_seconds
                passive_transport_observation = wait_for_log_marker_observation(
                    passive_marker_path,
                    "advertising started mode=2 tx=3 connectable=true",
                    passive_transport_timeout_seconds,
                )
                startup_timing["passiveTransport"] = passive_transport_observation
                if passive_transport_observation["observed"]:
                    time.sleep(POST_PASSIVE_START_SETTLE_SECONDS)
                if not passive_transport_observation["observed"]:
                    raise SystemExit(
                        f"Android passive transport did not start within {passive_transport_timeout_seconds} seconds"
                    )
                discovered_peer_id = (
                    discovered_peer_id
                    or read_passive_peer_id(
                        args.passive_android_serial,
                        app_id,
                        retries=max(1, int(peer_resolution_wait_seconds)),
                    )
                    or wait_for_discovered_peer_id(
                        passive_marker_path,
                        peer_resolution_wait_seconds,
                    )
                )
                print(f"==> Passive peer id resolved: {discovered_peer_id}")
            else:
                discovered_peer_id = discovered_peer_id or args.target_peer_id
                print(
                    f"==> Waiting up to {args.android_ready_seconds} seconds for Android proof-app ready marker"
                )
                passive_startup_observation = wait_for_log_marker_observation(
                    passive_marker_path,
                    "MeshLink proof app ready on",
                    args.android_ready_seconds,
                )
                startup_timing["passive"] = passive_startup_observation
                if not passive_startup_observation["observed"]:
                    print(
                        f"==> Android proof-app ready marker not observed after {args.android_ready_seconds} seconds; continuing with GATT transport wait"
                    )
                passive_transport_timeout_seconds = launch_passive_transport_wait_seconds(
                    args.android_ready_seconds,
                    args.capture_timeout_seconds,
                )
                startup_timing["launch"]["passiveTransportWaitSeconds"] = passive_transport_timeout_seconds
                passive_transport_observation = wait_for_log_marker_observation(
                    passive_marker_path,
                    "advertising started mode=2 tx=3 connectable=true",
                    passive_transport_timeout_seconds,
                )
                startup_timing["passiveTransport"] = passive_transport_observation
                if passive_transport_observation["observed"]:
                    time.sleep(POST_PASSIVE_START_SETTLE_SECONDS)
                if not passive_transport_observation["observed"]:
                    raise SystemExit(
                        f"Android passive transport did not start within {passive_transport_timeout_seconds} seconds"
                    )
            if sender_process is None:
                sender_target_peer_id = resolve_sender_target_peer_id(
                    passive_marker_path,
                    peer_resolution_wait_seconds,
                    discovered_peer_id,
                    args.target_peer_id,
                )
                if sender_target_peer_id is None:
                    print(
                        "==> Passive peer id unavailable after short resolution wait; launching sender without a seeded target peer"
                    )
                else:
                    discovered_peer_id = sender_target_peer_id
                    print(f"==> Passive peer id resolved for sender launch: {sender_target_peer_id}")
                sender_advertisement_carrier_choice = (
                    "uuid-pair-plus-service-data"
                    if should_prefer_service_data(args.sender_android_serial, args.passive_android_serial)
                    else args.advertisement_carrier
                )
                # Phase 1: launch the sender with auto-send disabled so we can read its own
                # identity (peer id) via the same discovery-seed mechanism already used for the
                # passive role, without risking a premature send to the wrong peer while many
                # other fleet devices are simultaneously advertising/scanning.
                sender_process = launch_android_sender_role_app(
                    run_dir=run_dir,
                    android_serial=args.sender_android_serial,
                    app_id=app_id,
                    storage_subdirectory=storage_subdirectory,
                    android_transport_logcat=args.android_transport_logcat,
                    target_peer_id=sender_target_peer_id,
                    sender_advertisement_carrier=sender_advertisement_carrier_choice,
                    disable_auto_send=True,
                )
                print(f"==> Android sender launched at +{time.monotonic() - run_started_at:.1f}s")
                sender_own_peer_id = read_passive_peer_id(
                    args.sender_android_serial,
                    app_id,
                    retries=max(1, int(peer_resolution_wait_seconds)),
                )
                if sender_own_peer_id is None:
                    print(
                        "==> Sender peer id unavailable after short resolution wait; passive will remain unfiltered"
                    )
                else:
                    print(f"==> Sender peer id resolved for passive target filter: {sender_own_peer_id}")
                    # Restart both roles together (passive with the sender's peer id as a target
                    # filter, sender with auto-send re-enabled) rather than sequentially. Android
                    # rotates the BLE MAC address and L2CAP PSM whenever a role is force-stopped
                    # and relaunched. If only the sender were restarted here while passive stayed
                    # up from phase 1, passive would already be mid-reconnect to the sender's
                    # stale phase-1 address when it rotates, causing repeated L2CAP connect
                    # failures and a premature ROUTE_EXPIRED (confirmed on hardware). Restarting
                    # both together means neither side has an established connection to break.
                    stop_background_process_before_relaunch(passive_process)
                    passive_process = start_android_role_app(
                        run_dir=run_dir,
                        android_serial=args.passive_android_serial,
                        label="passive",
                        role="passive",
                        app_id=app_id,
                        storage_subdirectory=storage_subdirectory,
                        android_transport_logcat=args.android_transport_logcat,
                        target_peer_id=sender_own_peer_id,
                        advertisement_carrier=args.advertisement_carrier,
                        android_activity=ANDROID_ACTIVITY,
                        android_package=ANDROID_PACKAGE,
                    )
                    print(
                        f"==> Android passive re-launched with target filter at +{time.monotonic() - run_started_at:.1f}s"
                    )
                    # Phase 2: relaunch the sender with the real (still-valid) target peer id and
                    # auto-send re-enabled, launched immediately after the passive restart above
                    # (not after waiting for passive's transport marker) so both roles come up
                    # fresh together instead of one settling in before the other rotates its
                    # BLE identity.
                    stop_background_process_before_relaunch(sender_process)
                    sender_process = launch_android_sender_role_app(
                        run_dir=run_dir,
                        android_serial=args.sender_android_serial,
                        app_id=app_id,
                        storage_subdirectory=storage_subdirectory,
                        android_transport_logcat=args.android_transport_logcat,
                        target_peer_id=sender_target_peer_id,
                        sender_advertisement_carrier=sender_advertisement_carrier_choice,
                        disable_auto_send=False,
                    )
                    print(f"==> Android sender re-launched at +{time.monotonic() - run_started_at:.1f}s")
                    passive_restart_transport_observation = wait_for_log_marker_observation(
                        passive_marker_path,
                        "advertising started mode=2 tx=3 connectable=true",
                        passive_transport_timeout_seconds,
                    )
                    if passive_restart_transport_observation["observed"]:
                        time.sleep(POST_PASSIVE_START_SETTLE_SECONDS)
                    else:
                        raise SystemExit(
                            f"Android passive transport did not restart within {passive_transport_timeout_seconds} seconds"
                        )
            if discovered_peer_id is None:
                print(
                    "==> Passive peer id unavailable before the route phase; proceeding without a seeded target peer"
                )
            else:
                print(f"==> Peer resolution gate resolved passive peer id {discovered_peer_id}")
            sender_startup_observation = wait_for_log_marker_observation(
                sender_log_path(run_dir),
                "REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER",
                args.android_ready_seconds,
            )
            startup_timing["sender"] = sender_startup_observation
            if not sender_startup_observation["observed"]:
                print(
                    f"==> Android sender startup marker not observed after {args.android_ready_seconds} seconds"
                )
            stage = "capture"
            completions = wait_for_android_completions(
                run_dir,
                args.capture_timeout_seconds,
                args.sender_android_serial,
                args.passive_android_serial,
            )
            print(f"==> Android proof completed at +{time.monotonic() - run_started_at:.1f}s")
            completions = wait_for_passive_export_completion(
                run_dir,
                POST_RESULT_PASSIVE_EXPORT_TIMEOUT_SECONDS,
            )
            time.sleep(POST_RESULT_IDLE_SECONDS)
        finally:
            try:
                cleanup_android_direct_run(
                    sender_process=sender_process,
                    passive_process=passive_process,
                    sender_android_serial=args.sender_android_serial,
                    passive_android_serial=args.passive_android_serial,
                    extra_force_stop_serials=args.extra_force_stop_serial,
                )
            except SystemExit:
                stage = "cleanup"
                raise

        stage = "summary"
        total_seconds = round(time.monotonic() - run_started_at, 1)
        startup_timing["totalSeconds"] = total_seconds
        timings = build_timing_snapshot(
            run_dir=run_dir,
            startup_timing=startup_timing,
            total_seconds=total_seconds,
            android_ready_seconds=args.android_ready_seconds,
            capture_timeout_seconds=args.capture_timeout_seconds,
        )
        summary = summarize_and_verify(
            sender_android_serial=args.sender_android_serial,
            passive_android_serial=args.passive_android_serial,
            run_dir=run_dir,
            storage_subdirectory=storage_subdirectory,
            app_id=app_id,
            completions=completions,
            startup_timing=startup_timing,
            timings=timings,
            passive_transport="meshlink",
        )
        summary["discoveredPeerId"] = discovered_peer_id
        sender_log_text = read_text(sender_log_path(run_dir))
        passive_log_text = read_text(passive_log_path(run_dir))
        target_peer_id = discovered_peer_id or extract_target_peer_id(sender_log_text)
        sender_discovery_focus_lines = extract_discovery_focus_lines(
            sender_log_text,
            target_peer_id,
            include_summary_lines=False,
        )
        passive_discovery_focus_lines = extract_discovery_focus_lines(
            passive_log_text,
            target_peer_id,
            include_peer_lines=False,
        )
        summary["senderDiscoveryFocus"] = {
            "peerId": target_peer_id,
            "foreignScanIgnoredCount": count_foreign_scan_ignored_lines(sender_log_text),
            "scanResultCount": count_scan_result_lines(sender_log_text),
            "scanParseSkippedCount": count_scan_parse_skipped_lines(sender_log_text),
            "scanTargetMismatchCount": count_scan_target_mismatch_lines(sender_log_text),
            "scanAcceptedCount": count_scan_accepted_lines(sender_log_text),
            "topAcceptedPeers": summarize_accepted_scan_peers(sender_log_text),
            "lines": sender_discovery_focus_lines,
        }
        summary["passiveDiscoveryFocus"] = {
            "peerId": target_peer_id,
            "foreignScanIgnoredCount": count_foreign_scan_ignored_lines(passive_log_text),
            "scanResultCount": count_scan_result_lines(passive_log_text),
            "scanParseSkippedCount": count_scan_parse_skipped_lines(passive_log_text),
            "scanTargetMismatchCount": count_scan_target_mismatch_lines(passive_log_text),
            "scanAcceptedCount": count_scan_accepted_lines(passive_log_text),
            "topPeers": summarize_foreign_scan_peers(passive_log_text),
            "topAcceptedPeers": summarize_accepted_scan_peers(passive_log_text),
            "lines": passive_discovery_focus_lines,
        }
        summary["timings"].update(
            {
                "totalSeconds": total_seconds,
                "androidReadySeconds": args.android_ready_seconds,
                "captureTimeoutSeconds": args.capture_timeout_seconds,
                "senderInstallReused": sender_preflight.get("installReused", False),
                "passiveInstallReused": passive_preflight.get("installReused", False),
            }
        )
        summary["startupTiming"].update(
            {
                "install": {
                    "senderSeconds": sender_preflight.get("installSeconds"),
                    "passiveSeconds": passive_preflight.get("installSeconds"),
                    "senderReused": sender_preflight.get("installReused", False),
                    "passiveReused": passive_preflight.get("installReused", False),
                },
                "permissions": {
                    "senderSeconds": sender_preflight.get("permissionsSeconds"),
                    "passiveSeconds": passive_preflight.get("permissionsSeconds"),
                    "senderReused": sender_preflight.get("permissionsReused", False),
                    "passiveReused": passive_preflight.get("permissionsReused", False),
                },
                "launch": {
                    "senderSeconds": sender_preflight.get("launchSeconds"),
                    "passiveSeconds": passive_preflight.get("launchSeconds"),
                    "senderReused": sender_preflight.get("launchReused", False),
                    "passiveReused": passive_preflight.get("launchReused", False),
                    "passiveStartupWaitSeconds": args.android_ready_seconds,
                    "passiveTransportWaitSeconds": startup_timing.get("launch", {}).get(
                        "passiveTransportWaitSeconds"
                    ),
                    "postResultIdleSeconds": POST_RESULT_IDLE_SECONDS,
                },
            }
        )
        write_summary(run_dir, summary)
        print("==> Android discovery seed:", discovered_peer_id or "none")
        return 0
    except SystemExit as error:
        total_seconds = round(time.monotonic() - run_started_at, 1)
        startup_timing["totalSeconds"] = total_seconds
        timings = build_timing_snapshot(
            run_dir=run_dir,
            startup_timing=startup_timing,
            total_seconds=total_seconds,
            android_ready_seconds=args.android_ready_seconds,
            capture_timeout_seconds=args.capture_timeout_seconds,
        )
        failure = failure_summary(
            run_dir=run_dir,
            sender_android_serial=args.sender_android_serial,
            passive_android_serial=args.passive_android_serial,
            app_id=app_id,
            storage_subdirectory=storage_subdirectory,
            stage=stage,
            error_message=str(error),
            completions=collect_android_completions_best_effort(run_dir),
            startup_timing=startup_timing,
            timings=timings,
        )
        write_summary(run_dir, failure)
        raise
    except Exception as error:
        total_seconds = round(time.monotonic() - run_started_at, 1)
        startup_timing["totalSeconds"] = total_seconds
        timings = build_timing_snapshot(
            run_dir=run_dir,
            startup_timing=startup_timing,
            total_seconds=total_seconds,
            android_ready_seconds=args.android_ready_seconds,
            capture_timeout_seconds=args.capture_timeout_seconds,
        )
        failure = failure_summary(
            run_dir=run_dir,
            sender_android_serial=args.sender_android_serial,
            passive_android_serial=args.passive_android_serial,
            app_id=app_id,
            storage_subdirectory=storage_subdirectory,
            stage=stage,
            error_message=str(error),
            completions=collect_android_completions_best_effort(run_dir),
            startup_timing=startup_timing,
            timings=timings,
        )
        write_summary(run_dir, failure)
        raise


if __name__ == "__main__":
    raise SystemExit(main())
