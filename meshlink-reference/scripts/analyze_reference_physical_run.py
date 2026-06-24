#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import re
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from typing import Callable

ANDROID_TIMESTAMP = re.compile(
    r"^(?P<month>\d{2})-(?P<day>\d{2}) (?P<hour>\d{2}):(?P<minute>\d{2}):(?P<second>\d{2})(?:\.(?P<millis>\d{3}))?"
)
XCODEBUILD_TIMESTAMP = re.compile(
    r"^(?P<year>\d{4})-(?P<month>\d{2})-(?P<day>\d{2}) (?P<hour>\d{2}):(?P<minute>\d{2}):(?P<second>\d{2})(?:\.(?P<fraction>\d+))?"
)


@dataclass
class RunAnalysis:
    scenario_type: str
    scenario_name: str
    status: str
    run_directory: str
    summary: list[str]
    invariants: dict[str, bool]
    timings_seconds: dict[str, float | None]
    artifacts: dict[str, str | int | bool | None]
    recovery_window: dict[str, object]
    topology_evidence: dict[str, object]
    recommendations: list[str]


@dataclass
class AndroidRoleLog:
    label: str
    role: str
    path: Path
    text: str


@dataclass
class LoadedSummary:
    exists: bool
    valid: bool
    data: dict[str, object]
    error: str | None = None


@dataclass
class DirectRunInputs:
    sender_platform: str
    sender_log_path: Path
    sender_log_text: str
    sender_launch_mode: str | None
    passive_log_path: Path
    passive_log_text: str
    sender_xcodebuild_text: str


ANDROID_ROLE_PATTERNS = {
    "RELAY": re.compile(r"\brole=RELAY\b|\brole=relay\b"),
    "PASSIVE": re.compile(r"\brole=PASSIVE\b|\brole=passive\b"),
    "SENDER": re.compile(r"\brole=SENDER\b|\brole=sender\b"),
}


DIRECT_SENDER_REQUIRED_MARKERS = {
    "sender_started": "REFERENCE_AUTOMATION started mode=LIVE_PROOF role=SENDER",
    "sender_peer_discovered": "REFERENCE_AUTOMATION peer.discovered role=SENDER",
    "sender_send_requested": "REFERENCE_AUTOMATION send.requested role=sender",
}

RESILIENCE_SCENARIOS = {
    "direct-pause-resume",
    "direct-trust-reset-recovery",
    "direct-restart-recovery",
    "direct-isolation-recovery",
    "direct-route-break-recovery",
}

RECOVERY_WINDOW_CONTRACTS: dict[str, dict[str, object]] = {
    "direct-pause-resume": {
        "label": "pause-resume",
        "injection_requested": "REFERENCE_AUTOMATION pause.requested role=sender",
        "injection_observed": "REFERENCE_AUTOMATION pause.observed role=sender window=open",
        "recovery_marker": "REFERENCE_AUTOMATION pause.recovered role=sender window=closed",
        "topology_needles": [
            "REFERENCE_AUTOMATION pause.observed role=sender window=open",
            "REFERENCE_AUTOMATION resume.requested role=sender",
            "REFERENCE_AUTOMATION resume.observed role=sender window=closed",
        ],
    },
    "direct-trust-reset-recovery": {
        "label": "trust-reset",
        "injection_requested": "REFERENCE_AUTOMATION trust.reset.requested role=sender",
        "injection_observed": "REFERENCE_AUTOMATION trust.reset.observed role=sender window=open",
        "recovery_marker": "REFERENCE_AUTOMATION trust.reset.recovered role=sender window=closed",
        "topology_needles": [
            "REFERENCE_AUTOMATION trust.reset.observed role=sender window=open",
            "REFERENCE_AUTOMATION trust.reset.recovered role=sender window=closed",
            "ROUTE_RETRACTED",
            "ROUTE_EXPIRED",
        ],
    },
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Analyze a retained MeshLink reference-app physical proof run directory."
    )
    parser.add_argument("--run-dir", required=True, help="Retained run directory to analyze")
    parser.add_argument(
        "--scenario-type",
        choices=["auto", "direct", "relay"],
        default="auto",
        help="Scenario type. Defaults to auto-detection from retained files.",
    )
    parser.add_argument(
        "--output-json",
        help="Optional output path for the machine-readable analysis JSON. Defaults to <run-dir>/analysis.json",
    )
    parser.add_argument(
        "--output-markdown",
        help="Optional output path for the human-readable analysis Markdown. Defaults to <run-dir>/analysis.md",
    )
    parser.add_argument(
        "--print-json",
        action="store_true",
        help="Print the JSON analysis to stdout after writing it.",
    )
    return parser.parse_args()


def load_text(path: Path) -> str:
    if not path.exists():
        return ""
    return path.read_text(encoding="utf-8", errors="replace")


def load_summary_json(run_dir: Path) -> LoadedSummary:
    summary_path = run_dir / "summary.json"
    if not summary_path.exists():
        return LoadedSummary(exists=False, valid=False, data={})
    try:
        payload = json.loads(summary_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        return LoadedSummary(
            exists=True,
            valid=False,
            data={},
            error=(
                f"{summary_path.name} could not be parsed: {error.msg} "
                f"(line {error.lineno}, column {error.colno})"
            ),
        )
    if not isinstance(payload, dict):
        return LoadedSummary(
            exists=True,
            valid=False,
            data={},
            error=f"{summary_path.name} must contain a JSON object",
        )
    return LoadedSummary(exists=True, valid=True, data=payload)


def text_value(value: object | None) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def summary_value(summary: LoadedSummary, *keys: str) -> object | None:
    if not summary.valid:
        return None
    for key in keys:
        if key in summary.data:
            return summary.data.get(key)
    return None


def summary_text_value(summary: LoadedSummary, *keys: str) -> str | None:
    return text_value(summary_value(summary, *keys))


def summary_evidence_text(summary: LoadedSummary, key: str) -> str | None:
    if not summary.valid:
        return None
    evidence = summary.data.get("evidence")
    if not isinstance(evidence, dict):
        return None
    return text_value(evidence.get(key))


def summary_scenario_name(summary: LoadedSummary) -> str | None:
    return summary_text_value(summary, "scenario")


def summary_status_value(summary: LoadedSummary) -> str | None:
    return summary_text_value(summary, "status")


def summary_sender_platform(summary: LoadedSummary) -> str | None:
    return summary_text_value(summary, "senderPlatform")


def summary_passive_platform(summary: LoadedSummary) -> str | None:
    return summary_text_value(summary, "passivePlatform")


def summary_sender_completion(summary: LoadedSummary) -> str | None:
    return summary_text_value(summary, "senderCompletion", "ios_completion")


def summary_passive_completion(summary: LoadedSummary) -> str | None:
    return summary_text_value(summary, "passiveCompletion", "android_completion")


def summary_export_relative_path(summary: LoadedSummary) -> str | None:
    return summary_text_value(summary, "exportRelativePath", "export_relative_path")


def summary_full_export_relative_path(summary: LoadedSummary) -> str | None:
    return summary_text_value(summary, "fullExportRelativePath", "full_export_relative_path")


def detect_scenario_type(run_dir: Path) -> str:
    if (run_dir / "android_logcat.log").exists():
        return "direct"

    role_logs = classify_android_role_logs(run_dir)
    if "PASSIVE" in role_logs and "RELAY" in role_logs:
        return "relay"
    if "PASSIVE" in role_logs and "SENDER" in role_logs:
        return "direct"

    summary = load_summary_json(run_dir)
    if summary_scenario_name(summary) == "relay-constrained":
        return "relay"
    if summary_sender_platform(summary) == "android" and summary_passive_platform(summary) == "android":
        return "direct"

    raise SystemExit(
        "Could not detect the retained run type. Pass --scenario-type direct|relay explicitly."
    )


def detect_android_role(text: str) -> str | None:
    for role, pattern in ANDROID_ROLE_PATTERNS.items():
        if pattern.search(text):
            return role
    return None


def classify_android_role_logs(run_dir: Path) -> dict[str, AndroidRoleLog]:
    role_logs: dict[str, AndroidRoleLog] = {}
    for candidate in sorted(run_dir.glob("*_logcat.log")):
        text = load_text(candidate)
        role = detect_android_role(text)
        if role is None or role in role_logs:
            continue
        role_logs[role] = AndroidRoleLog(
            label=candidate.stem.removesuffix("_logcat"),
            role=role,
            path=candidate,
            text=text,
        )
    return role_logs


def parse_android_timestamp(line: str) -> float | None:
    match = ANDROID_TIMESTAMP.match(line)
    if match is None:
        return None
    values = {key: int(value or 0) for key, value in match.groupdict().items()}
    moment = datetime(
        year=2026,
        month=values["month"],
        day=values["day"],
        hour=values["hour"],
        minute=values["minute"],
        second=values["second"],
        microsecond=values["millis"] * 1000,
    )
    return moment.timestamp()


def parse_xcodebuild_timestamp(line: str) -> float | None:
    match = XCODEBUILD_TIMESTAMP.match(line)
    if match is None:
        return None
    values = match.groupdict()
    fraction = (values.get("fraction") or "0").ljust(6, "0")[:6]
    moment = datetime(
        year=int(values["year"]),
        month=int(values["month"]),
        day=int(values["day"]),
        hour=int(values["hour"]),
        minute=int(values["minute"]),
        second=int(values["second"]),
        microsecond=int(fraction),
    )
    return moment.timestamp()


TimestampParser = Callable[[str], float | None]


def collect_relative_timings(
    text: str,
    parser: TimestampParser,
    markers: dict[str, str],
) -> dict[str, float | None]:
    found: dict[str, float] = {}
    for line in text.splitlines():
        timestamp = parser(line)
        if timestamp is None:
            continue
        for key, needle in markers.items():
            if key not in found and needle in line:
                found[key] = timestamp

    if not found:
        return {key: None for key in markers}

    baseline = min(found.values())
    return {
        key: round(found[key] - baseline, 3) if key in found else None for key in markers
    }


def extract_first_line(text: str, needle: str) -> str | None:
    return next((line.strip() for line in text.splitlines() if needle in line), None)


def route_is_direct_value(line: str | None) -> str | None:
    if line is None:
        return None
    if "routeIsDirect=true" in line:
        return "true"
    if "routeIsDirect=false" in line:
        return "false"
    return None


def extract_integer_value(line: str | None, key: str) -> int | None:
    if line is None:
        return None
    match = re.search(rf"{re.escape(key)}=(?P<value>\d+)", line)
    return int(match.group("value")) if match is not None else None


def extract_first_matching_line(text: str, pattern: str) -> str | None:
    regex = re.compile(pattern)
    return next((line.strip() for line in text.splitlines() if regex.search(line)), None)


def parse_retained_timestamp(line: str) -> float | None:
    return parse_xcodebuild_timestamp(line) or parse_android_timestamp(line)


def extract_first_line_and_timestamp(text: str, needle: str) -> tuple[str | None, float | None]:
    for line in text.splitlines():
        if needle in line:
            return line.strip(), parse_retained_timestamp(line)
    return None, None


def collect_first_matching_lines(text: str, needles: list[str]) -> list[str]:
    matches: list[str] = []
    for needle in needles:
        line = extract_first_line(text, needle)
        if line is not None and line not in matches:
            matches.append(line)
    return matches


def resolve_direct_run_inputs(run_dir: Path, summary: LoadedSummary) -> DirectRunInputs:
    mixed_passive_log_path = run_dir / "android_logcat.log"
    sender_console_path = run_dir / "iphone_console.log"
    sender_xcodebuild_path = run_dir / "ios_xcodebuild.log"
    if (
        mixed_passive_log_path.exists()
        or sender_console_path.exists()
        or sender_xcodebuild_path.exists()
    ):
        sender_log_text = load_text(sender_console_path)
        sender_xcodebuild_text = load_text(sender_xcodebuild_path)
        sender_launch_mode = (
            "xcuitest"
            if "** TEST SUCCEEDED **" in sender_xcodebuild_text
            else ("devicectl" if sender_log_text else None)
        )
        return DirectRunInputs(
            sender_platform="ios",
            sender_log_path=sender_console_path,
            sender_log_text=sender_log_text,
            sender_launch_mode=sender_launch_mode,
            passive_log_path=mixed_passive_log_path,
            passive_log_text=load_text(mixed_passive_log_path),
            sender_xcodebuild_text=sender_xcodebuild_text,
        )

    role_logs = classify_android_role_logs(run_dir)
    sender_log_name = summary_evidence_text(summary, "senderLogcat") or "sender_logcat.log"
    passive_log_name = summary_evidence_text(summary, "passiveLogcat") or "passive_logcat.log"
    sender_log_path = role_logs.get("SENDER", AndroidRoleLog("sender", "SENDER", run_dir / sender_log_name, "")).path
    passive_log_path = role_logs.get("PASSIVE", AndroidRoleLog("passive", "PASSIVE", run_dir / passive_log_name, "")).path
    return DirectRunInputs(
        sender_platform="android",
        sender_log_path=sender_log_path,
        sender_log_text=load_text(sender_log_path),
        sender_launch_mode="android",
        passive_log_path=passive_log_path,
        passive_log_text=load_text(passive_log_path),
        sender_xcodebuild_text="",
    )


def validate_direct_summary_metadata(
    *,
    summary: LoadedSummary,
    direct_inputs: DirectRunInputs,
    sender_completion_from_log: str | None,
    passive_completion_from_log: str | None,
) -> tuple[bool, list[str]]:
    if not summary.valid:
        return True, []

    issues: list[str] = []
    explicit_sender_platform = summary_sender_platform(summary)
    if explicit_sender_platform is not None and explicit_sender_platform != direct_inputs.sender_platform:
        issues.append(
            "summary.json declares "
            f"senderPlatform={explicit_sender_platform} but retained direct evidence resolves the sender platform as {direct_inputs.sender_platform}."
        )
    explicit_passive_platform = summary_passive_platform(summary)
    if explicit_passive_platform is not None and explicit_passive_platform != "android":
        issues.append(
            "summary.json declares "
            f"passivePlatform={explicit_passive_platform} but direct retained passive analysis expects android."
        )

    explicit_sender_completion = summary_sender_completion(summary)
    if explicit_sender_completion is not None and "role=passive" in explicit_sender_completion:
        issues.append(
            "summary.json recorded a sender completion line that names role=passive, which contradicts the retained direct sender evidence."
        )
    if (
        explicit_sender_completion is not None
        and sender_completion_from_log is not None
        and explicit_sender_completion != sender_completion_from_log
        and explicit_sender_completion != "xcodebuild test passed"
    ):
        issues.append(
            "summary.json sender completion does not match the retained sender log line."
        )

    explicit_passive_completion = summary_passive_completion(summary)
    if explicit_passive_completion is not None and "role=sender" in explicit_passive_completion:
        issues.append(
            "summary.json recorded a passive completion line that names role=sender, which contradicts the retained direct passive evidence."
        )
    if (
        explicit_passive_completion is not None
        and passive_completion_from_log is not None
        and explicit_passive_completion != passive_completion_from_log
    ):
        issues.append(
            "summary.json passive completion does not match the retained passive log line."
        )

    explicit_export_relative_path = summary_export_relative_path(summary)
    derived_export_relative_path = extract_export_path(passive_completion_from_log)
    if (
        explicit_export_relative_path is not None
        and derived_export_relative_path is not None
        and explicit_export_relative_path != derived_export_relative_path
    ):
        issues.append(
            "summary.json export path does not match the export path recorded by the retained passive proof.complete line."
        )

    return not issues, issues


def evaluate_recovery_window(
    *,
    scenario_name: str,
    sender_log: str,
    sender_completion_line: str | None,
) -> tuple[dict[str, object], dict[str, object], list[str]]:
    recovery_window: dict[str, object] = {
        "scenario_name": scenario_name,
        "contract": None,
        "verdict": "not-applicable" if scenario_name not in RESILIENCE_SCENARIOS else "missing-evidence",
        "reason": None,
        "injection_requested_marker": None,
        "injection_observed_marker": None,
        "recovery_marker": None,
        "completion_marker": "REFERENCE_AUTOMATION proof.complete role=sender",
        "injection_requested_line": None,
        "injection_observed_line": None,
        "recovery_marker_line": None,
        "completion_line": sender_completion_line,
        "injection_requested_seconds": None,
        "injection_observed_seconds": None,
        "recovery_marker_seconds": None,
        "completion_seconds": None,
        "completed_after_recovery_marker": None,
    }
    topology_evidence: dict[str, object] = {
        "scenario_marker_line": extract_first_line(sender_log, f"scenario={scenario_name}"),
        "pointers": collect_first_matching_lines(
            sender_log,
            [f"scenario={scenario_name}", "ROUTE_RETRACTED", "ROUTE_EXPIRED", "routeIsDirect=false"],
        ),
    }
    recommendations: list[str] = []

    if scenario_name not in RESILIENCE_SCENARIOS:
        recovery_window["reason"] = "This scenario is not a resilience recovery scenario."
        return recovery_window, topology_evidence, recommendations

    contract = RECOVERY_WINDOW_CONTRACTS.get(scenario_name)
    if contract is None:
        recovery_window["reason"] = (
            "No dedicated recovery-marker contract is recorded yet for this scenario, so retained analysis cannot honestly prove recovery-window timing from the current logs."
        )
        recommendations.append(
            "This resilience scenario is still missing dedicated recovery-window markers in the retained logs. Keep the run fail until the scenario emits requested, observed, and recovered markers that bracket proof.complete."
        )
        return recovery_window, topology_evidence, recommendations

    recovery_window["contract"] = contract["label"]
    injection_requested_marker = str(contract["injection_requested"])
    injection_observed_marker = str(contract["injection_observed"])
    recovery_marker = str(contract["recovery_marker"])
    recovery_window["injection_requested_marker"] = injection_requested_marker
    recovery_window["injection_observed_marker"] = injection_observed_marker
    recovery_window["recovery_marker"] = recovery_marker

    injection_requested_line, injection_requested_ts = extract_first_line_and_timestamp(
        sender_log, injection_requested_marker
    )
    injection_observed_line, injection_observed_ts = extract_first_line_and_timestamp(
        sender_log, injection_observed_marker
    )
    recovery_marker_line, recovery_marker_ts = extract_first_line_and_timestamp(
        sender_log, recovery_marker
    )
    completion_line = sender_completion_line
    completion_ts = parse_retained_timestamp(completion_line) if completion_line is not None else None

    recovery_window["injection_requested_line"] = injection_requested_line
    recovery_window["injection_observed_line"] = injection_observed_line
    recovery_window["recovery_marker_line"] = recovery_marker_line
    recovery_window["completion_line"] = completion_line
    recovery_window["injection_requested_seconds"] = injection_requested_ts
    recovery_window["injection_observed_seconds"] = injection_observed_ts
    recovery_window["recovery_marker_seconds"] = recovery_marker_ts
    recovery_window["completion_seconds"] = completion_ts

    topology_evidence["pointers"] = collect_first_matching_lines(
        sender_log,
        list(contract["topology_needles"]),
    )

    missing_markers: list[str] = []
    if injection_requested_line is None:
        missing_markers.append("injection_requested")
    if injection_observed_line is None:
        missing_markers.append("injection_observed")
    if recovery_marker_line is None:
        missing_markers.append("recovery_marker")
    if completion_line is None:
        missing_markers.append("completion")

    if missing_markers:
        recovery_window["verdict"] = "missing-evidence"
        recovery_window["reason"] = (
            "Missing recovery-window evidence: " + ", ".join(missing_markers) + "."
        )
        recommendations.append(
            f"{contract['label']} recovery analysis is incomplete because the retained logs never captured: "
            + ", ".join(missing_markers)
            + ". Keep the run fail until the scenario emits the requested, observed, and recovered markers around proof.complete."
        )
        return recovery_window, topology_evidence, recommendations

    if recovery_marker_ts is not None and completion_ts is not None and completion_ts < recovery_marker_ts:
        recovery_window["verdict"] = "late-recovery"
        recovery_window["reason"] = (
            "The retained proof completed before the recovery marker appeared, so the run does not prove recovery within the declared window."
        )
        recovery_window["completed_after_recovery_marker"] = False
        recommendations.append(
            f"The {contract['label']} scenario completed before the recovery marker landed. Keep the run fail until the recovery marker appears before proof.complete."
        )
        return recovery_window, topology_evidence, recommendations

    recovery_window["verdict"] = "within-window"
    recovery_window["reason"] = (
        "The recovery marker appeared before proof.complete, so the retained run proves recovery within the declared window."
    )
    recovery_window["completed_after_recovery_marker"] = True
    return recovery_window, topology_evidence, recommendations


def analyze_direct_run(run_dir: Path) -> RunAnalysis:
    summary = load_summary_json(run_dir)
    scenario_name = summary_scenario_name(summary) or "direct-guided"
    direct_inputs = resolve_direct_run_inputs(run_dir, summary)
    sender_log = direct_inputs.sender_log_text
    passive_log = direct_inputs.passive_log_text
    sender_xcodebuild = direct_inputs.sender_xcodebuild_text
    export_text = load_text(run_dir / "android_export.json")
    full_export_text = load_text(run_dir / "android_export_full.json")
    history_text = load_text(run_dir / "android_history.json")

    sender_completion_from_log = extract_first_line(
        sender_log, "REFERENCE_AUTOMATION proof.complete role=sender"
    )
    sender_completion_line = sender_completion_from_log or summary_sender_completion(summary)
    passive_completion_from_log = extract_first_line(
        passive_log, "REFERENCE_AUTOMATION proof.complete role=passive"
    )
    passive_completion_line = passive_completion_from_log or summary_passive_completion(summary)
    sender_via_xcuitest = direct_inputs.sender_launch_mode == "xcuitest"
    passive_inbound_count = extract_integer_value(passive_completion_line, "inboundCount")
    passive_largest_inbound_bytes = extract_integer_value(
        passive_completion_line, "largestInboundBytes"
    )
    recovery_delivery_count = extract_integer_value(sender_completion_line, "deliveries")
    large_transfer_request_line = extract_first_matching_line(
        sender_log, r"payload=large-transfer"
    )
    large_transfer_request_bytes = extract_integer_value(large_transfer_request_line, "bytes")
    recovery_window, topology_evidence, recovery_window_recommendations = evaluate_recovery_window(
        scenario_name=scenario_name,
        sender_log=sender_log,
        sender_completion_line=sender_completion_line,
    )
    summary_role_metadata_consistent, summary_metadata_issues = validate_direct_summary_metadata(
        summary=summary,
        direct_inputs=direct_inputs,
        sender_completion_from_log=sender_completion_from_log,
        passive_completion_from_log=passive_completion_from_log,
    )
    summary_status = (summary_status_value(summary) or "").lower()

    invariants = {
        "summary_json_valid_or_absent": (not summary.exists) or summary.valid,
        "summary_role_metadata_consistent": summary_role_metadata_consistent,
        "summary_does_not_report_failure": summary_status not in {"failed", "fail"},
        "sender_log_captured": bool(sender_log.strip()),
        "sender_started": DIRECT_SENDER_REQUIRED_MARKERS["sender_started"] in sender_log,
        "sender_peer_discovered": DIRECT_SENDER_REQUIRED_MARKERS["sender_peer_discovered"] in sender_log,
        "sender_send_requested": DIRECT_SENDER_REQUIRED_MARKERS["sender_send_requested"] in sender_log,
        "sender_proof_complete": sender_completion_from_log is not None or sender_via_xcuitest,
        "sender_proof_failed": "REFERENCE_AUTOMATION proof.failed role=sender" in sender_log,
        "passive_log_captured": bool(passive_log.strip()),
        "passive_proof_complete": passive_completion_from_log is not None,
        "peer_discovered_on_passive": "REFERENCE_AUTOMATION peer.discovered role=PASSIVE"
        in passive_log,
        "passive_requested_redacted_export": "REFERENCE_AUTOMATION export.requested role=passive policy=redacted-preview"
        in passive_log,
        "redacted_export_file_captured": bool(export_text),
        "retained_history_file_captured": bool(history_text),
        "full_payload_absent_from_redacted_export": '"fullPayload":' not in export_text,
        "redacted_export_flags_present": '"defaultMode": "redacted-preview"' in export_text
        and '"fullPayloadIncluded": false' in export_text
        and '"operatorOptInRecorded": false' in export_text,
        "retained_history_marked_retained": '"historyStatus": "RETAINED"' in history_text,
        "ios_gatt_side_link_retained_after_l2cap_close": "retaining peer" in sender_log
        and "GATT side link is still active" in sender_log,
        "unexpected_routed_delivery": "routeIsDirect=false" in sender_log,
        "pause_requested": "REFERENCE_AUTOMATION pause.requested role=sender" in sender_log,
        "pause_observed": "REFERENCE_AUTOMATION pause.observed role=sender" in sender_log,
        "resume_requested": "REFERENCE_AUTOMATION resume.requested role=sender" in sender_log,
        "resume_observed": "REFERENCE_AUTOMATION resume.observed role=sender" in sender_log,
        "full_export_requested": "REFERENCE_AUTOMATION export.requested role=passive policy=full-payload"
        in passive_log,
        "full_export_file_captured": bool(full_export_text),
        "full_export_flags_present": '"fullPayloadIncluded": true' in full_export_text
        and '"operatorOptInRecorded": true' in full_export_text
        and '"fullPayload":' in full_export_text,
        "trust_reset_requested": "REFERENCE_AUTOMATION trust.reset.requested role=sender"
        in sender_log,
        "trust_reset_observed": "REFERENCE_AUTOMATION trust.reset.observed role=sender"
        in sender_log,
        "recovery_send_requested": "phase=recovery" in sender_log,
        "recovery_delivery_count_met": (recovery_delivery_count or 0) >= 2,
        "passive_recovery_inbound_count_met": (passive_inbound_count or 0) >= 2,
        "large_transfer_requested": large_transfer_request_line is not None,
        "large_transfer_bytes_recorded": (large_transfer_request_bytes or 0) >= 4096,
        "passive_large_inbound_recorded": (passive_largest_inbound_bytes or 0) >= 4096,
    }

    timings = collect_relative_timings(
        passive_log,
        parse_android_timestamp,
        markers={
            "passive_started": "REFERENCE_AUTOMATION started mode=LIVE_PROOF role=PASSIVE",
            "passive_peer_discovered": "REFERENCE_AUTOMATION peer.discovered role=PASSIVE",
            "passive_session_end_requested": "REFERENCE_AUTOMATION session.end.requested role=passive",
            "passive_export_requested": "REFERENCE_AUTOMATION export.requested role=passive",
            "passive_proof_complete": "REFERENCE_AUTOMATION proof.complete role=passive",
        },
    )
    if scenario_name == "direct-full-export":
        timings.update(
            collect_relative_timings(
                passive_log,
                parse_android_timestamp,
                markers={
                    "passive_full_export_requested": "REFERENCE_AUTOMATION export.requested role=passive policy=full-payload",
                    "passive_full_export_completed": "REFERENCE_AUTOMATION export.completed role=passive policy=full-payload",
                    "passive_redacted_export_completed": "REFERENCE_AUTOMATION export.completed role=passive policy=redacted-preview",
                },
            )
        )

    recommendations: list[str] = []
    recommendations.extend(recovery_window_recommendations)
    if recovery_window["verdict"] != "not-applicable":
        recovery_summary = recovery_window.get("reason") or "Recovery-window verdict computed."
        summary_lines_recovery = f"Recovery window verdict: `{recovery_window['verdict']}` — {recovery_summary}"
    else:
        summary_lines_recovery = None
    if summary.error is not None:
        recommendations.append(
            f"{summary.error}. The analyzer fell back to retained sender/passive logs and left summary-derived fields unset where needed."
        )
    recommendations.extend(summary_metadata_issues)
    if not invariants["summary_does_not_report_failure"]:
        recommendations.append(
            "summary.json already reports a failed direct run. Keep the retained analysis fail until a new run writes a passing summary and matching retained logs."
        )
    if not invariants["sender_log_captured"] and not sender_via_xcuitest:
        recommendations.append(
            "Required direct sender evidence file "
            f"'{direct_inputs.sender_log_path.name}' is missing or empty. Keep the run fail until sender-side retained proof logs are captured."
        )
    if not invariants["passive_log_captured"]:
        recommendations.append(
            "Required direct passive evidence file "
            f"'{direct_inputs.passive_log_path.name}' is missing or empty. Keep the run fail until passive-side retained proof logs are captured."
        )
    if direct_inputs.sender_launch_mode != "xcuitest" and invariants["sender_log_captured"]:
        if not invariants["sender_started"]:
            recommendations.append(
                "The retained sender log is missing the LIVE_PROOF start marker. Re-run the direct proof instead of trusting sender summary data alone."
            )
        if not invariants["sender_peer_discovered"]:
            recommendations.append(
                "The retained sender log never recorded peer discovery. Re-check discovery readiness before trusting the sender-side direct proof."
            )
        if not invariants["sender_send_requested"]:
            recommendations.append(
                "The retained sender log never recorded send.requested. Re-check sender automation sequencing before trusting the direct proof."
            )
    if invariants["sender_proof_failed"]:
        recommendations.append(
            "The retained sender log contains REFERENCE_AUTOMATION proof.failed role=sender. Keep the run fail and inspect sender-side trust, routing, or session recovery before retrying."
        )
    if invariants["unexpected_routed_delivery"]:
        recommendations.append(
            "The direct scenario observed routeIsDirect=false on the sender. Mark this run fail and move routed coverage to the relay scenario or re-position devices for a one-hop direct proof."
        )
    if not invariants["sender_proof_complete"]:
        sender_label = "Android sender" if direct_inputs.sender_platform == "android" else "iPhone sender"
        if sender_via_xcuitest:
            recommendations.append(
                "The iPhone sender XCTest path never completed successfully. Re-run after clearing first-launch prompts or fixing the XCTest sender automation."
            )
        else:
            recommendations.append(
                f"The {sender_label} never reached proof.complete. Re-run after clearing Bluetooth prompts or inspecting the retained sender log before trusting the run."
            )
    if not invariants["passive_proof_complete"]:
        recommendations.append(
            "The Android passive peer never retained/exported the session. Treat sender-only success as incomplete and inspect discovery, trust, or export automation before trusting the run."
        )
    if invariants["passive_log_captured"] and not invariants["peer_discovered_on_passive"]:
        recommendations.append(
            "The passive log never recorded peer discovery. Re-check passive readiness before trusting retained export evidence."
        )
    if invariants["passive_log_captured"] and not invariants["passive_requested_redacted_export"]:
        recommendations.append(
            "The passive log never recorded a redacted export request. Re-check retained-export automation before trusting android_export.json."
        )
    if not invariants["redacted_export_file_captured"]:
        recommendations.append(
            "android_export.json is missing or empty. Keep the run fail until the passive-side redacted export is captured locally."
        )
    if not invariants["retained_history_file_captured"]:
        recommendations.append(
            "android_history.json is missing or empty. Keep the run fail until retained-history evidence is captured locally."
        )
    if export_text and not invariants["redacted_export_flags_present"]:
        recommendations.append(
            "The captured export file does not advertise the expected redacted-preview flags. Re-check export policy wiring before using the artifact as retained evidence."
        )
    if export_text and not invariants["full_payload_absent_from_redacted_export"]:
        recommendations.append(
            "A redacted export unexpectedly included full payload bytes. Keep full-payload export gated behind explicit live-session opt-in only."
        )

    critical_keys = [
        "sender_proof_complete",
        "passive_log_captured",
        "passive_proof_complete",
        "peer_discovered_on_passive",
        "passive_requested_redacted_export",
        "redacted_export_file_captured",
        "retained_history_file_captured",
        "full_payload_absent_from_redacted_export",
        "redacted_export_flags_present",
        "retained_history_marked_retained",
        "summary_role_metadata_consistent",
        "summary_does_not_report_failure",
    ]
    if direct_inputs.sender_launch_mode != "xcuitest":
        critical_keys.extend(
            [
                "sender_log_captured",
                "sender_started",
                "sender_peer_discovered",
                "sender_send_requested",
            ]
        )

    scenario_critical_keys: list[str] = []
    if scenario_name == "direct-pause-resume":
        scenario_critical_keys = [
            "pause_requested",
            "pause_observed",
            "resume_requested",
            "resume_observed",
        ]
        if not invariants["resume_observed"]:
            recommendations.append(
                "The pause/resume scenario never observed a clean resume on the sender. Keep lifecycle recovery in the physical matrix until pause/resume stops being a transport-risky operation."
            )
    elif scenario_name == "direct-full-export":
        scenario_critical_keys = [
            "full_export_requested",
            "full_export_file_captured",
            "full_export_flags_present",
        ]
        if not invariants["full_export_file_captured"]:
            recommendations.append(
                "The passive peer never captured the live full export. Re-check the live-session full-export path before relying on redacted retained artifacts alone."
            )
    elif scenario_name == "direct-trust-reset-recovery":
        scenario_critical_keys = [
            "trust_reset_requested",
            "trust_reset_observed",
            "recovery_send_requested",
            "recovery_delivery_count_met",
            "passive_recovery_inbound_count_met",
        ]
        if not invariants["passive_recovery_inbound_count_met"]:
            recommendations.append(
                "The trust-reset recovery scenario did not produce two inbound deliveries on the passive peer. Re-check trust reset, re-discovery, and re-send sequencing before trusting that recovery path."
            )
    elif scenario_name == "direct-large-transfer":
        scenario_critical_keys = [
            "large_transfer_requested",
            "large_transfer_bytes_recorded",
            "passive_large_inbound_recorded",
        ]
        if not invariants["passive_large_inbound_recorded"]:
            recommendations.append(
                "The large-transfer scenario never observed a large inbound payload on the passive peer. Re-check the physical large-transfer threshold and delivery path before trusting the transfer evidence."
            )

    if recovery_window["verdict"] != "not-applicable":
        invariants["recovery_window_within_window"] = recovery_window["verdict"] == "within-window"
        scenario_critical_keys.append("recovery_window_within_window")
        if recovery_window["verdict"] == "late-recovery":
            recommendations.append(
                "The retained proof completed after the recovery marker, so this resilience run is late even though the sender eventually recovered. Keep the scenario fail until the recovery marker lands before proof.complete."
            )
        elif recovery_window["verdict"] == "missing-evidence" and not recovery_window_recommendations:
            recommendations.append(
                "The retained logs never captured enough recovery-window evidence to prove this resilience scenario honestly. Keep the scenario fail until the requested, observed, and recovered markers are retained alongside proof.complete."
            )

    status = (
        "pass"
        if all(invariants[key] for key in critical_keys + scenario_critical_keys)
        and not invariants["sender_proof_failed"]
        and not invariants["unexpected_routed_delivery"]
        else "fail"
    )

    summary_lines: list[str] = []
    scenario_summary_text = {
        "direct-guided": "The direct guided proof completed end-to-end, retained the session on Android, and captured a redacted export without full payload bytes.",
        "direct-pause-resume": "The direct pause/resume proof completed after an explicit lifecycle pause and resume on the sender, then retained a redacted export on the passive peer.",
        "direct-full-export": "The direct full-export proof captured a live full-payload export before session end, then retained a separate redacted export after the session closed.",
        "direct-trust-reset-recovery": "The direct trust-reset recovery proof completed an initial send, reset trust on the sender, then re-established delivery and retained evidence on the passive peer.",
        "direct-restart-recovery": "The direct restart recovery proof completed after the sender restarted and the retained route state converged again within the declared recovery window.",
        "direct-isolation-recovery": "The direct isolation recovery proof completed after the sender or passive peer was intentionally isolated and then rejoined the retained direct route.",
        "direct-route-break-recovery": "The direct route-break recovery proof completed after the route was intentionally broken and then re-established with retained evidence on the sender and passive peer.",
        "direct-large-transfer": "The direct large-transfer proof delivered a large physical payload and retained a redacted export after the passive peer received the oversized message.",
    }
    summary_lines.append(
        scenario_summary_text.get(
            scenario_name,
            "The direct physical proof completed with retained history and a redacted export.",
        )
        if status == "pass"
        else "The direct physical scenario is incomplete; at least one sender, passive, or scenario-specific invariant is missing."
    )
    if summary_lines_recovery is not None:
        summary_lines.append(summary_lines_recovery)
    if direct_inputs.sender_platform == "android":
        summary_lines.append(
            "Sender-side proof evidence came from retained Android sender logcat output, while preserving the same analysis.json and analysis.md contract used by mixed direct runs."
        )
    elif invariants["ios_gatt_side_link_retained_after_l2cap_close"]:
        summary_lines.append(
            "The sender log shows the iPhone keeping a GATT notify side link alive after the L2CAP channel closes, which matches the mixed-bearer resilience posture."
        )
    if summary.error is not None:
        summary_lines.append(
            "summary.json was malformed, so summary-derived fields were omitted and the analyzer fell back to retained sender/passive logs."
        )

    artifacts = {
        "sender_platform": direct_inputs.sender_platform,
        "sender_log_artifact": direct_inputs.sender_log_path.name,
        "passive_log_artifact": direct_inputs.passive_log_path.name,
        "sender_completion_line": sender_completion_line or None,
        "passive_completion_line": passive_completion_line,
        "sender_launch_mode": direct_inputs.sender_launch_mode,
        "export_relative_path": summary_export_relative_path(summary)
        or extract_export_path(passive_completion_from_log),
        "full_export_relative_path": summary_full_export_relative_path(summary),
        "passive_inbound_count": passive_inbound_count,
        "passive_largest_inbound_bytes": passive_largest_inbound_bytes,
        "large_transfer_request_bytes": large_transfer_request_bytes,
        "analysis_based_on_summary_json": summary.valid,
    }

    return RunAnalysis(
        scenario_type="direct",
        scenario_name=scenario_name,
        status=status,
        run_directory=str(run_dir),
        summary=summary_lines,
        invariants=invariants,
        timings_seconds=timings,
        artifacts=artifacts,
        recovery_window=recovery_window,
        topology_evidence=topology_evidence,
        recommendations=recommendations,
    )


def analyze_relay_run(run_dir: Path) -> RunAnalysis:
    summary = load_summary_json(run_dir)
    scenario_name = summary_scenario_name(summary) or "relay-constrained"
    sender_log = load_text(run_dir / "iphone_console.log")
    role_logs = classify_android_role_logs(run_dir)
    passive_log = role_logs.get("PASSIVE")
    relay_log = role_logs.get("RELAY")
    if passive_log is None or relay_log is None:
        raise SystemExit(
            "Relay analysis requires one Android passive log and one Android relay log in the run directory."
        )

    sender_completion_line = extract_first_line(
        sender_log, "REFERENCE_AUTOMATION proof.complete role=sender"
    )
    passive_completion_line = extract_first_line(
        passive_log.text, "REFERENCE_AUTOMATION proof.complete role=passive"
    )
    sender_delivery_line = extract_first_line(
        sender_log, "DELIVERY_SUCCEEDED @ delivery.send"
    )

    invariants = {
        "sender_proof_complete": sender_completion_line is not None,
        "sender_proof_failed": "REFERENCE_AUTOMATION proof.failed role=sender" in sender_log,
        "sender_bootstrap_requested": "REFERENCE_AUTOMATION bootstrap.requested role=sender"
        in sender_log,
        "sender_routed_delivery": "routeIsDirect=false" in (sender_completion_line or "")
        or "routeIsDirect=false" in (sender_delivery_line or ""),
        "passive_proof_complete": passive_completion_line is not None,
        "relay_forward_queued": "forward.message.queued" in relay_log.text,
        "relay_forward_delivered": "forward.message.delivered" in relay_log.text,
        "temporary_peer_promoted": "promoted temporary peer" in relay_log.text,
        "no_session_failure_absent": "transport.data.noSession" not in relay_log.text
        and "transport.data.noSession" not in passive_log.text,
        "sender_route_retracted_after_proof": "ROUTE_RETRACTED" in sender_log,
        "passive_route_expired_after_proof": "ROUTE_EXPIRED" in passive_log.text,
    }

    passive_timings = collect_relative_timings(
        passive_log.text,
        parse_android_timestamp,
        markers={
            "passive_started": "REFERENCE_AUTOMATION started mode=LIVE_PROOF role=PASSIVE",
            "passive_peer_discovered": "REFERENCE_AUTOMATION peer.discovered role=PASSIVE",
            "passive_session_end_requested": "REFERENCE_AUTOMATION session.end.requested role=passive",
            "passive_export_requested": "REFERENCE_AUTOMATION export.requested role=passive",
            "passive_proof_complete": "REFERENCE_AUTOMATION proof.complete role=passive",
        },
    )
    relay_timings = collect_relative_timings(
        relay_log.text,
        parse_android_timestamp,
        markers={
            "relay_started": "REFERENCE_AUTOMATION started mode=LIVE_PROOF role=RELAY",
            "relay_temporary_peer_promoted": "promoted temporary peer",
            "relay_forward_queued": "forward.message.queued",
            "relay_forward_delivered": "forward.message.delivered",
        },
    )
    timings = {**passive_timings, **relay_timings}

    recommendations: list[str] = []
    if invariants["sender_proof_complete"] and not invariants["sender_routed_delivery"]:
        recommendations.append(
            "The sender completed without a routed delivery. Treat this as a false positive and keep relay pass criteria anchored on routeIsDirect=false plus passive completion."
        )
    if not invariants["no_session_failure_absent"]:
        recommendations.append(
            "The relay path hit transport.data.noSession. Re-check temporary-peer alias retention and canonical promotion before trusting routed delivery."
        )
    if invariants["sender_bootstrap_requested"] and not invariants["relay_forward_delivered"]:
        recommendations.append(
            "Bootstrap succeeded but the relay never logged forward.message.delivered. Inspect routed-target convergence and middle-hop forwarding before calling the scenario stable."
        )
    if invariants["sender_routed_delivery"] and not invariants["passive_proof_complete"]:
        recommendations.append(
            "The sender observed a routed delivery but the passive peer never retained/exported the session. Keep passive completion in the hard pass criteria so recipient-side regressions do not slip through."
        )
    if invariants["sender_routed_delivery"] and not invariants["sender_route_retracted_after_proof"]:
        recommendations.append(
            "The sender never logged route retraction after the proof. Consider capturing teardown-side route removal explicitly so stale routes are easier to diagnose."
        )
    if invariants["passive_proof_complete"] and not invariants["passive_route_expired_after_proof"]:
        recommendations.append(
            "The passive side completed without a later route-expiry diagnostic. Capturing that teardown side effect makes post-proof cleanup regressions easier to spot."
        )

    critical_keys = [
        "sender_proof_complete",
        "sender_bootstrap_requested",
        "sender_routed_delivery",
        "passive_proof_complete",
        "relay_forward_queued",
        "relay_forward_delivered",
        "no_session_failure_absent",
    ]
    status = "pass" if all(invariants[key] for key in critical_keys) else "fail"

    summary_lines = []
    if status == "pass":
        summary_lines.append(
            "The constrained relay proof completed with a routed sender delivery, middle-hop forwarding, and passive retained-export completion."
        )
    else:
        summary_lines.append(
            "The constrained relay proof is incomplete or unsafe to trust; at least one routed-delivery invariant is missing."
        )
    if invariants["temporary_peer_promoted"]:
        summary_lines.append(
            "The relay log shows temporary transport peers being promoted to canonical advertisement peers, which is a high-value diagnostic signal for responder/session continuity when that transport path is exercised."
        )

    artifacts = {
        "sender_completion_line": sender_completion_line,
        "sender_delivery_route_is_direct": route_is_direct_value(sender_completion_line)
        or route_is_direct_value(sender_delivery_line),
        "passive_completion_line": passive_completion_line,
        "relay_log_label": relay_log.label,
        "passive_log_label": passive_log.label,
        "export_relative_path": summary_export_relative_path(summary)
        or extract_export_path(passive_completion_line),
        "analysis_based_on_summary_json": summary.valid,
    }

    return RunAnalysis(
        scenario_type="relay",
        scenario_name=scenario_name,
        status=status,
        run_directory=str(run_dir),
        summary=summary_lines,
        invariants=invariants,
        timings_seconds=timings,
        artifacts=artifacts,
        recovery_window={
            "scenario_name": scenario_name,
            "contract": None,
            "verdict": "not-applicable",
            "reason": "Relay scenarios do not use the direct recovery-window contract.",
            "injection_requested_marker": None,
            "injection_observed_marker": None,
            "recovery_marker": None,
            "completion_marker": "REFERENCE_AUTOMATION proof.complete role=sender",
            "injection_requested_line": None,
            "injection_observed_line": None,
            "recovery_marker_line": None,
            "completion_line": sender_completion_line,
            "injection_requested_seconds": None,
            "injection_observed_seconds": None,
            "recovery_marker_seconds": None,
            "completion_seconds": parse_retained_timestamp(sender_completion_line) if sender_completion_line is not None else None,
            "completed_after_recovery_marker": None,
        },
        topology_evidence={
            "scenario_marker_line": extract_first_line(sender_log, f"scenario={scenario_name}"),
            "pointers": collect_first_matching_lines(
                sender_log,
                [f"scenario={scenario_name}", "ROUTE_RETRACTED", "ROUTE_EXPIRED", "routeIsDirect=false"],
            ),
        },
        recommendations=recommendations,
    )


def extract_export_path(passive_completion_line: str | None) -> str | None:
    if passive_completion_line is None:
        return None
    match = re.search(r"export=(?P<export>\S+)", passive_completion_line)
    return match.group("export") if match is not None else None


def markdown_cell(value: object) -> str:
    if value in (None, ""):
        return "n/a"
    if isinstance(value, list):
        return "<br>".join(str(item) for item in value) or "n/a"
    if isinstance(value, dict):
        return json.dumps(value, indent=2)
    return str(value)


def render_markdown(analysis: RunAnalysis) -> str:
    lines = [
        f"# Physical run analysis: {Path(analysis.run_directory).name}",
        "",
        f"- Scenario type: `{analysis.scenario_type}`",
        f"- Scenario name: `{analysis.scenario_name}`",
        f"- Status: `{analysis.status}`",
        "",
        "## Summary",
        "",
    ]
    lines.extend(f"- {item}" for item in analysis.summary)
    lines.extend(["", "## Invariants", "", "| Invariant | Observed |", "|---|---|"])
    for key, value in analysis.invariants.items():
        lines.append(f"| `{key}` | {'yes' if value else 'no'} |")
    lines.extend(["", "## Timings (seconds)", "", "| Marker | Seconds from first observed marker |", "|---|---|"])
    for key, value in analysis.timings_seconds.items():
        lines.append(f"| `{key}` | {value if value is not None else 'n/a'} |")
    lines.extend(["", "## Captured artifacts", "", "| Artifact | Value |", "|---|---|"])
    for key, value in analysis.artifacts.items():
        lines.append(f"| `{key}` | {markdown_cell(value)} |")
    lines.extend(["", "## Recovery window verdict", "", "| Field | Value |", "|---|---|"])
    for key, value in analysis.recovery_window.items():
        lines.append(f"| `{key}` | {markdown_cell(value)} |")
    lines.extend(["", "## Topology evidence pointers", "", "| Pointer | Value |", "|---|---|"])
    for key, value in analysis.topology_evidence.items():
        lines.append(f"| `{key}` | {markdown_cell(value)} |")
    lines.extend(["", "## Recommendations", ""])
    if analysis.recommendations:
        lines.extend(f"- {item}" for item in analysis.recommendations)
    else:
        lines.append("- No blocking follow-up was detected in this retained run.")
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    args = parse_args()
    run_dir = Path(args.run_dir)
    scenario_type = args.scenario_type
    if scenario_type == "auto":
        scenario_type = detect_scenario_type(run_dir)

    if scenario_type == "direct":
        analysis = analyze_direct_run(run_dir)
    else:
        analysis = analyze_relay_run(run_dir)

    output_json = Path(args.output_json) if args.output_json else run_dir / "analysis.json"
    output_markdown = (
        Path(args.output_markdown) if args.output_markdown else run_dir / "analysis.md"
    )
    output_json.write_text(json.dumps(asdict(analysis), indent=2), encoding="utf-8")
    output_markdown.write_text(render_markdown(analysis), encoding="utf-8")

    if args.print_json:
        print(json.dumps(asdict(analysis), indent=2))

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
