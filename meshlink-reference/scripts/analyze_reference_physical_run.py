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
    recommendations: list[str]


@dataclass
class AndroidRoleLog:
    label: str
    role: str
    path: Path
    text: str


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


def load_summary_json(run_dir: Path) -> dict[str, object]:
    summary_path = run_dir / "summary.json"
    if not summary_path.exists():
        return {}
    return json.loads(summary_path.read_text(encoding="utf-8"))


def detect_scenario_type(run_dir: Path) -> str:
    if (run_dir / "android_logcat.log").exists():
        return "direct"

    role_logs = classify_android_role_logs(run_dir)
    if "PASSIVE" in role_logs and "RELAY" in role_logs:
        return "relay"

    raise SystemExit(
        "Could not detect the retained run type. Pass --scenario-type direct|relay explicitly."
    )


def classify_android_role_logs(run_dir: Path) -> dict[str, AndroidRoleLog]:
    role_logs: dict[str, AndroidRoleLog] = {}
    for candidate in sorted(run_dir.glob("*_logcat.log")):
        text = load_text(candidate)
        if "role=RELAY" in text and "RELAY" not in role_logs:
            role_logs["RELAY"] = AndroidRoleLog(
                label=candidate.stem.removesuffix("_logcat"),
                role="RELAY",
                path=candidate,
                text=text,
            )
        elif "role=PASSIVE" in text and "PASSIVE" not in role_logs:
            role_logs["PASSIVE"] = AndroidRoleLog(
                label=candidate.stem.removesuffix("_logcat"),
                role="PASSIVE",
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


def analyze_direct_run(run_dir: Path) -> RunAnalysis:
    summary_json = load_summary_json(run_dir)
    scenario_name = str(summary_json.get("scenario") or "direct-guided")
    sender_console_path = run_dir / "iphone_console.log"
    sender_xcodebuild_path = run_dir / "ios_xcodebuild.log"
    passive_log_path = run_dir / "android_logcat.log"
    sender_console = load_text(sender_console_path)
    sender_xcodebuild = load_text(sender_xcodebuild_path)
    passive_log = load_text(passive_log_path)
    export_text = load_text(run_dir / "android_export.json")
    full_export_text = load_text(run_dir / "android_export_full.json")
    history_text = load_text(run_dir / "android_history.json")

    sender_completion_line = (
        extract_first_line(sender_console, "REFERENCE_AUTOMATION proof.complete role=sender")
        or str(summary_json.get("ios_completion") or "")
    )
    passive_completion_line = extract_first_line(
        passive_log, "REFERENCE_AUTOMATION proof.complete role=passive"
    )
    sender_via_xcuitest = "** TEST SUCCEEDED **" in sender_xcodebuild
    passive_inbound_count = extract_integer_value(passive_completion_line, "inboundCount")
    passive_largest_inbound_bytes = extract_integer_value(
        passive_completion_line, "largestInboundBytes"
    )
    recovery_delivery_count = extract_integer_value(sender_completion_line, "deliveries")
    large_transfer_request_line = extract_first_matching_line(
        sender_console, r"payload=large-transfer"
    )
    large_transfer_request_bytes = extract_integer_value(large_transfer_request_line, "bytes")

    invariants = {
        "sender_proof_complete": bool(sender_completion_line) or sender_via_xcuitest,
        "sender_proof_failed": "REFERENCE_AUTOMATION proof.failed role=sender" in sender_console,
        "passive_proof_complete": passive_completion_line is not None,
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
        "ios_gatt_side_link_retained_after_l2cap_close": "retaining peer" in sender_console
        and "GATT side link is still active" in sender_console,
        "unexpected_routed_delivery": "routeIsDirect=false" in sender_console,
        "pause_requested": "REFERENCE_AUTOMATION pause.requested role=sender" in sender_console,
        "pause_observed": "REFERENCE_AUTOMATION pause.observed role=sender" in sender_console,
        "resume_requested": "REFERENCE_AUTOMATION resume.requested role=sender" in sender_console,
        "resume_observed": "REFERENCE_AUTOMATION resume.observed role=sender" in sender_console,
        "full_export_requested": "REFERENCE_AUTOMATION export.requested role=passive policy=full-payload"
        in passive_log,
        "full_export_file_captured": bool(full_export_text),
        "full_export_flags_present": '"fullPayloadIncluded": true' in full_export_text
        and '"operatorOptInRecorded": true' in full_export_text
        and '"fullPayload":' in full_export_text,
        "trust_reset_requested": "REFERENCE_AUTOMATION trust.reset.requested role=sender"
        in sender_console,
        "trust_reset_observed": "REFERENCE_AUTOMATION trust.reset.observed role=sender"
        in sender_console,
        "recovery_send_requested": "phase=recovery" in sender_console,
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
    if invariants["unexpected_routed_delivery"]:
        recommendations.append(
            "The direct scenario observed routeIsDirect=false on the sender. Keep this scenario focused on one-hop proof or split multi-hop coverage into the relay scenario."
        )
    if not invariants["sender_proof_complete"]:
        recommendations.append(
            "The iPhone sender never reached proof.complete. Re-run after clearing Bluetooth prompts or use the xcuitest sender path once to settle first-run permissions."
        )
    if not invariants["passive_proof_complete"]:
        recommendations.append(
            "The Android passive peer never retained/exported the session. Treat sender-only success as incomplete and inspect discovery, trust, or export automation before trusting the run."
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
        "passive_proof_complete",
        "peer_discovered_on_passive",
        "passive_requested_redacted_export",
        "redacted_export_file_captured",
        "retained_history_file_captured",
        "full_payload_absent_from_redacted_export",
        "redacted_export_flags_present",
        "retained_history_marked_retained",
    ]
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

    status = "pass" if all(invariants[key] for key in critical_keys + scenario_critical_keys) else "fail"

    summary: list[str] = []
    scenario_summary_text = {
        "direct-guided": "The direct guided proof completed end-to-end, retained the session on Android, and captured a redacted export without full payload bytes.",
        "direct-pause-resume": "The direct pause/resume proof completed after an explicit lifecycle pause and resume on the sender, then retained a redacted export on the passive peer.",
        "direct-full-export": "The direct full-export proof captured a live full-payload export before session end, then retained a separate redacted export after the session closed.",
        "direct-trust-reset-recovery": "The direct trust-reset recovery proof completed an initial send, reset trust on the sender, then re-established delivery and retained evidence on the passive peer.",
        "direct-large-transfer": "The direct large-transfer proof delivered a large physical payload and retained a redacted export after the passive peer received the oversized message.",
    }
    summary.append(
        scenario_summary_text.get(
            scenario_name,
            "The direct physical proof completed with retained history and a redacted export.",
        )
        if status == "pass"
        else "The direct physical scenario is incomplete; at least one sender, passive, or scenario-specific invariant is missing."
    )
    if invariants["ios_gatt_side_link_retained_after_l2cap_close"]:
        summary.append(
            "The sender log shows the iPhone keeping a GATT notify side link alive after the L2CAP channel closes, which matches the mixed-bearer resilience posture."
        )

    artifacts = {
        "sender_completion_line": sender_completion_line or None,
        "passive_completion_line": passive_completion_line,
        "sender_launch_mode": "xcuitest" if sender_via_xcuitest else ("devicectl" if sender_console else None),
        "export_relative_path": summary_json.get("export_relative_path"),
        "full_export_relative_path": summary_json.get("full_export_relative_path"),
        "passive_inbound_count": passive_inbound_count,
        "passive_largest_inbound_bytes": passive_largest_inbound_bytes,
        "large_transfer_request_bytes": large_transfer_request_bytes,
        "analysis_based_on_summary_json": bool(summary_json),
    }

    return RunAnalysis(
        scenario_type="direct",
        scenario_name=scenario_name,
        status=status,
        run_directory=str(run_dir),
        summary=summary,
        invariants=invariants,
        timings_seconds=timings,
        artifacts=artifacts,
        recommendations=recommendations,
    )


def analyze_relay_run(run_dir: Path) -> RunAnalysis:
    summary_json = load_summary_json(run_dir)
    scenario_name = str(summary_json.get("scenario") or "relay-constrained")
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

    summary = []
    if status == "pass":
        summary.append(
            "The constrained relay proof completed with a routed sender delivery, middle-hop forwarding, and passive retained-export completion."
        )
    else:
        summary.append(
            "The constrained relay proof is incomplete or unsafe to trust; at least one routed-delivery invariant is missing."
        )
    if invariants["temporary_peer_promoted"]:
        summary.append(
            "The relay log shows temporary transport peers being promoted to canonical advertisement peers, which is a high-value diagnostic signal for responder/session continuity when that transport path is exercised."
        )

    artifacts = {
        "sender_completion_line": sender_completion_line,
        "sender_delivery_route_is_direct": route_is_direct_value(sender_completion_line)
        or route_is_direct_value(sender_delivery_line),
        "passive_completion_line": passive_completion_line,
        "relay_log_label": relay_log.label,
        "passive_log_label": passive_log.label,
        "export_relative_path": summary_json.get("export_relative_path")
        or extract_export_path(passive_completion_line),
        "analysis_based_on_summary_json": bool(summary_json),
    }

    return RunAnalysis(
        scenario_type="relay",
        scenario_name=scenario_name,
        status=status,
        run_directory=str(run_dir),
        summary=summary,
        invariants=invariants,
        timings_seconds=timings,
        artifacts=artifacts,
        recommendations=recommendations,
    )


def extract_export_path(passive_completion_line: str | None) -> str | None:
    if passive_completion_line is None:
        return None
    match = re.search(r"export=(?P<export>\S+)", passive_completion_line)
    return match.group("export") if match is not None else None


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
        lines.append(f"| `{key}` | {value if value not in (None, '') else 'n/a'} |")
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
