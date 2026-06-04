#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Mapping, Sequence

try:
    from run_headless_reference_live_proof import IOS_BUNDLE_ID, local_development_team_for_bundle_id
except Exception:  # pragma: no cover - fallback keeps the module importable in isolation.
    IOS_BUNDLE_ID = "ch.trancee.meshlink.reference.ios"

    def local_development_team_for_bundle_id(_: str) -> str | None:
        return None


DEFAULT_PROBE_TIMEOUT_SECONDS = 5.0
ADB_DEVICES_COMMAND = ["adb", "devices"]
IOS_DEVICE_LIST_COMMAND = ["xcrun", "devicectl", "list", "devices"]
ADB_KNOWN_STATES = {"device", "offline", "unauthorized"}
IOS_AVAILABLE_STATE_HINTS = ("connected", "available", "ready", "paired", "booted")
IOS_UNAVAILABLE_STATE_HINTS = ("disconnected", "unavailable", "busy", "shutdown")
IOS_NAME_HINTS = ("iphone", "ipad", "ipod")
IOS_IDENTIFIER_PATTERN = re.compile(r"^[A-Za-z0-9-]{10,}$")
ADB_DEVICE_PATTERN = re.compile(r"^(?P<control_id>\S+)\s+(?P<state>\S+)(?:\s+.*)?$")


@dataclass
class FleetReason:
    code: str
    kind: str
    message: str
    subject: str | None = None
    details: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "code": self.code,
            "kind": self.kind,
            "message": self.message,
        }
        if self.subject is not None:
            payload["subject"] = self.subject
        if self.details:
            payload["details"] = self.details
        return payload


@dataclass
class ProbeResult:
    command: list[str]
    returncode: int | None
    stdout: str = ""
    stderr: str = ""
    timed_out: bool = False
    missing: bool = False
    error: str | None = None


@dataclass
class ToolingStatus:
    name: str
    available: bool
    status: str
    probe_command: list[str] | None = None
    reasons: list[FleetReason] = field(default_factory=list)
    metadata: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "available": self.available,
            "status": self.status,
            "reasons": [reason.to_dict() for reason in self.reasons],
        }
        if self.probe_command is not None:
            payload["probeCommand"] = list(self.probe_command)
        if self.metadata:
            payload["metadata"] = self.metadata
        return payload


@dataclass
class DeviceRecord:
    alias: str
    platform: str
    control_id: str
    display_name: str
    state: str
    available: bool
    capabilities: list[str] = field(default_factory=list)
    reasons: list[FleetReason] = field(default_factory=list)
    metadata: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "alias": self.alias,
            "platform": self.platform,
            "controlId": self.control_id,
            "displayName": self.display_name,
            "state": self.state,
            "available": self.available,
            "capabilities": list(self.capabilities),
            "reasons": [reason.to_dict() for reason in self.reasons],
        }
        if self.metadata:
            payload["metadata"] = self.metadata
        return payload


@dataclass
class CandidateAssignment:
    assignment_id: str
    baseline: str
    shape: str
    participants: dict[str, str | None]
    considered_device_aliases: dict[str, list[str]]
    status: str
    runnable: bool
    reasons: list[FleetReason] = field(default_factory=list)
    priority: int = 0

    def to_dict(self) -> dict[str, Any]:
        return {
            "assignmentId": self.assignment_id,
            "baseline": self.baseline,
            "shape": self.shape,
            "participants": self.participants,
            "consideredDeviceAliases": self.considered_device_aliases,
            "status": self.status,
            "runnable": self.runnable,
            "priority": self.priority,
            "reasons": [reason.to_dict() for reason in self.reasons],
        }


@dataclass
class SelectionSummary:
    status: str
    selected_assignment_id: str | None
    reasons: list[FleetReason] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "status": self.status,
            "selectedAssignmentId": self.selected_assignment_id,
            "reasons": [reason.to_dict() for reason in self.reasons],
        }


CommandRunner = Callable[..., ProbeResult]


def reason(
    code: str,
    kind: str,
    message: str,
    *,
    subject: str | None = None,
    **details: Any,
) -> FleetReason:
    return FleetReason(
        code=code,
        kind=kind,
        message=message,
        subject=subject,
        details={key: value for key, value in details.items() if value is not None},
    )


def subprocess_runner(command: Sequence[str], *, timeout_seconds: float | None = None) -> ProbeResult:
    if isinstance(command, (str, bytes)):
        raise TypeError("Commands must be passed as argv sequences, not shell strings")
    normalized = [str(part) for part in command]
    if not normalized:
        raise ValueError("Command must not be empty")
    try:
        completed = subprocess.run(
            normalized,
            check=False,
            capture_output=True,
            text=True,
            timeout=timeout_seconds,
        )
        return ProbeResult(
            command=normalized,
            returncode=completed.returncode,
            stdout=completed.stdout,
            stderr=completed.stderr,
        )
    except FileNotFoundError as error:
        return ProbeResult(
            command=normalized,
            returncode=None,
            missing=True,
            error=str(error),
        )
    except subprocess.TimeoutExpired as error:
        return ProbeResult(
            command=normalized,
            returncode=None,
            stdout=(error.stdout or "") if isinstance(error.stdout, str) else "",
            stderr=(error.stderr or "") if isinstance(error.stderr, str) else "",
            timed_out=True,
            error=f"Timed out after {timeout_seconds} seconds",
        )
    except OSError as error:
        return ProbeResult(
            command=normalized,
            returncode=None,
            error=str(error),
        )


def build_fleet_manifest(
    *,
    command_runner: CommandRunner = subprocess_runner,
    environment: Mapping[str, str] | None = None,
    development_team_lookup: Callable[[str], str | None] = local_development_team_for_bundle_id,
    now: datetime | None = None,
) -> dict[str, Any]:
    android_tooling, android_devices = probe_android_devices(command_runner=command_runner)
    devicectl_tooling, ios_devices = probe_ios_devices(command_runner=command_runner)
    development_team = probe_development_team(
        environment=environment,
        development_team_lookup=development_team_lookup,
    )

    devices = assign_aliases([*android_devices, *ios_devices])
    candidates = build_candidate_assignments(
        devices=devices,
        tooling={
            "adb": android_tooling,
            "devicectl": devicectl_tooling,
            "developmentTeam": development_team,
        },
    )
    selection = select_assignment(candidates)
    selected_candidate = next(
        (candidate for candidate in candidates if candidate.assignment_id == selection.selected_assignment_id),
        None,
    )

    selection_log: list[dict[str, Any]] = []
    for candidate in candidates:
        selection_log.append(
            {
                "event": "candidate-evaluated",
                "assignmentId": candidate.assignment_id,
                "status": candidate.status,
                "runnable": candidate.runnable,
                "participants": candidate.participants,
                "reasonCodes": [entry.code for entry in candidate.reasons],
            }
        )
    selection_log.append(
        {
            "event": "assignment-selected" if selected_candidate is not None else "no-assignment-selected",
            "status": selection.status,
            "assignmentId": selection.selected_assignment_id,
            "reasonCodes": [entry.code for entry in selection.reasons],
        }
    )

    generated_at = (now or datetime.now(timezone.utc)).isoformat()
    return {
        "manifestVersion": 1,
        "generatedAt": generated_at,
        "tooling": {
            "adb": android_tooling.to_dict(),
            "devicectl": devicectl_tooling.to_dict(),
            "developmentTeam": development_team.to_dict(),
        },
        "devices": [device.to_dict() for device in devices],
        "candidateAssignments": [candidate.to_dict() for candidate in candidates],
        "selectedAssignment": selected_candidate.to_dict() if selected_candidate is not None else None,
        "selection": selection.to_dict(),
        "selectionLog": selection_log,
    }


def write_manifest(path: str | Path, manifest: Mapping[str, Any]) -> Path:
    destination = Path(path)
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = destination.with_name(f"{destination.name}.tmp")
    temporary_path.write_text(json.dumps(dict(manifest), indent=2) + "\n", encoding="utf-8")
    temporary_path.replace(destination)
    return destination


def read_manifest(path: str | Path) -> dict[str, Any]:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def find_device_by_alias(manifest: Mapping[str, Any], alias: str | None) -> dict[str, Any] | None:
    if alias is None:
        return None
    for device in manifest.get("devices", []):
        if isinstance(device, Mapping) and device.get("alias") == alias:
            return dict(device)
    return None


def find_candidate_assignment(
    manifest: Mapping[str, Any],
    assignment_id: str | None,
) -> dict[str, Any] | None:
    if assignment_id is None:
        return None
    for candidate in manifest.get("candidateAssignments", []):
        if isinstance(candidate, Mapping) and candidate.get("assignmentId") == assignment_id:
            return dict(candidate)
    return None


def probe_development_team(
    *,
    environment: Mapping[str, str] | None,
    development_team_lookup: Callable[[str], str | None],
) -> ToolingStatus:
    team = (environment or os.environ).get("DEVELOPMENT_TEAM", "").strip()
    if team:
        return ToolingStatus(
            name="developmentTeam",
            available=True,
            status="ready",
            reasons=[reason("ios-development-team-resolved", "info", "Resolved DEVELOPMENT_TEAM from the environment.")],
            metadata={"source": "environment"},
        )
    try:
        team = development_team_lookup(IOS_BUNDLE_ID)
    except Exception as error:  # pragma: no cover - defensive branch.
        return ToolingStatus(
            name="developmentTeam",
            available=False,
            status="error",
            reasons=[
                reason(
                    "ios-development-team-lookup-failed",
                    "invalid-environment",
                    "Failed to resolve a local Apple development team.",
                    error=str(error),
                )
            ],
        )
    if team:
        return ToolingStatus(
            name="developmentTeam",
            available=True,
            status="ready",
            reasons=[reason("ios-development-team-resolved", "info", "Resolved DEVELOPMENT_TEAM from local provisioning assets.")],
            metadata={"source": "local-provisioning-profile"},
        )
    return ToolingStatus(
        name="developmentTeam",
        available=False,
        status="unresolved",
        reasons=[
            reason(
                "ios-development-team-unresolved",
                "invalid-environment",
                "No DEVELOPMENT_TEAM was found in the environment or local provisioning assets.",
            )
        ],
    )


def probe_android_devices(*, command_runner: CommandRunner) -> tuple[ToolingStatus, list[DeviceRecord]]:
    result = command_runner(ADB_DEVICES_COMMAND, timeout_seconds=DEFAULT_PROBE_TIMEOUT_SECONDS)
    if result.missing:
        return (
            ToolingStatus(
                name="adb",
                available=False,
                status="missing",
                probe_command=ADB_DEVICES_COMMAND,
                reasons=[reason("adb-missing", "invalid-environment", "ADB is not available on this workstation.")],
            ),
            [],
        )
    if result.timed_out:
        return (
            ToolingStatus(
                name="adb",
                available=False,
                status="timeout",
                probe_command=ADB_DEVICES_COMMAND,
                reasons=[reason("adb-discovery-timeout", "invalid-environment", "ADB device discovery timed out.")],
            ),
            [],
        )
    if result.returncode != 0:
        return (
            ToolingStatus(
                name="adb",
                available=False,
                status="error",
                probe_command=ADB_DEVICES_COMMAND,
                reasons=[
                    reason(
                        "adb-discovery-failed",
                        "invalid-environment",
                        "ADB device discovery failed.",
                        returncode=result.returncode,
                    )
                ],
            ),
            [],
        )

    lines = result.stdout.splitlines()
    if not lines:
        return (
            ToolingStatus(
                name="adb",
                available=False,
                status="malformed-output",
                probe_command=ADB_DEVICES_COMMAND,
                reasons=[reason("adb-empty-output", "invalid-environment", "ADB discovery returned empty output.")],
            ),
            [],
        )

    devices: list[DeviceRecord] = []
    tooling_reasons: list[FleetReason] = []
    seen_control_ids: set[str] = set()

    for raw_line in lines:
        line = raw_line.strip()
        if not line or line.startswith("List of devices attached"):
            continue
        if line.startswith("*"):
            continue

        match = ADB_DEVICE_PATTERN.match(line)
        if match is None:
            tooling_reasons.append(
                reason(
                    "adb-line-unparseable",
                    "warning",
                    "Ignored an ADB discovery line that did not match the expected shape.",
                    line=line,
                )
            )
            continue

        control_id = match.group("control_id")
        state = match.group("state")
        if control_id in seen_control_ids:
            tooling_reasons.append(
                reason(
                    "duplicate-android-control-id",
                    "invalid-environment",
                    "ADB discovery returned the same Android control ID more than once.",
                    controlId=control_id,
                )
            )
            continue
        seen_control_ids.add(control_id)

        device_reasons: list[FleetReason] = []
        display_name = control_id
        available = False
        capabilities: list[str] = []

        if state == "device":
            model_probe = command_runner(
                ["adb", "-s", control_id, "shell", "getprop", "ro.product.model"],
                timeout_seconds=DEFAULT_PROBE_TIMEOUT_SECONDS,
            )
            if model_probe.timed_out:
                device_reasons.append(
                    reason(
                        "android-device-probe-timeout",
                        "invalid-environment",
                        "Timed out while probing an Android device model.",
                        subject=control_id,
                    )
                )
            elif model_probe.missing or model_probe.returncode not in (0, None):
                device_reasons.append(
                    reason(
                        "android-device-probe-failed",
                        "invalid-environment",
                        "Failed to probe an Android device model.",
                        subject=control_id,
                        returncode=model_probe.returncode,
                    )
                )
            else:
                model = model_probe.stdout.strip()
                if model:
                    display_name = model
                    available = True
                    capabilities = ["sender", "passive"]
                else:
                    device_reasons.append(
                        reason(
                            "android-device-probe-malformed",
                            "invalid-environment",
                            "Android device model probing returned no usable model name.",
                            subject=control_id,
                        )
                    )
        elif state == "offline":
            device_reasons.append(
                reason(
                    "android-device-offline",
                    "invalid-environment",
                    "Android discovery found a device that is offline.",
                    subject=control_id,
                )
            )
        elif state == "unauthorized":
            device_reasons.append(
                reason(
                    "android-device-unauthorized",
                    "invalid-environment",
                    "Android discovery found a device that is unauthorized for ADB.",
                    subject=control_id,
                )
            )
        else:
            device_reasons.append(
                reason(
                    "android-state-unrecognized",
                    "invalid-environment",
                    "Android discovery found an unrecognized device state.",
                    subject=control_id,
                    state=state,
                )
            )

        devices.append(
            DeviceRecord(
                alias="",
                platform="android",
                control_id=control_id,
                display_name=display_name,
                state=state,
                available=available,
                capabilities=capabilities,
                reasons=device_reasons,
                metadata={"model": display_name if display_name != control_id else None},
            )
        )

    tooling_status = finalize_tooling_status(
        name="adb",
        probe_command=ADB_DEVICES_COMMAND,
        devices=devices,
        reasons=tooling_reasons,
    )
    return tooling_status, devices


def probe_ios_devices(*, command_runner: CommandRunner) -> tuple[ToolingStatus, list[DeviceRecord]]:
    result = command_runner(IOS_DEVICE_LIST_COMMAND, timeout_seconds=DEFAULT_PROBE_TIMEOUT_SECONDS)
    if result.missing:
        return (
            ToolingStatus(
                name="devicectl",
                available=False,
                status="missing",
                probe_command=IOS_DEVICE_LIST_COMMAND,
                reasons=[
                    reason(
                        "apple-tooling-missing",
                        "invalid-environment",
                        "Apple device discovery tooling is not available on this workstation.",
                    )
                ],
            ),
            [],
        )
    if result.timed_out:
        return (
            ToolingStatus(
                name="devicectl",
                available=False,
                status="timeout",
                probe_command=IOS_DEVICE_LIST_COMMAND,
                reasons=[
                    reason(
                        "ios-discovery-timeout",
                        "invalid-environment",
                        "Apple device discovery timed out.",
                    )
                ],
            ),
            [],
        )
    if result.returncode != 0:
        return (
            ToolingStatus(
                name="devicectl",
                available=False,
                status="error",
                probe_command=IOS_DEVICE_LIST_COMMAND,
                reasons=[
                    reason(
                        "ios-discovery-failed",
                        "invalid-environment",
                        "Apple device discovery failed.",
                        returncode=result.returncode,
                    )
                ],
            ),
            [],
        )

    parsed_rows, tooling_reasons = parse_devicectl_output(result.stdout)
    devices: list[DeviceRecord] = []
    for row in parsed_rows:
        state = row["state"]
        available = ios_state_is_available(state)
        device_reasons: list[FleetReason] = []
        if not available:
            device_reasons.append(
                reason(
                    "ios-device-unavailable",
                    "invalid-environment",
                    "Apple discovery found an iOS device that is not in a runnable state.",
                    subject=row["controlId"],
                    state=state,
                )
            )
        devices.append(
            DeviceRecord(
                alias="",
                platform="ios",
                control_id=row["controlId"],
                display_name=row["displayName"],
                state=state,
                available=available,
                capabilities=["sender"] if available else [],
                reasons=device_reasons,
                metadata={"platform": row["platform"]},
            )
        )

    tooling_status = finalize_tooling_status(
        name="devicectl",
        probe_command=IOS_DEVICE_LIST_COMMAND,
        devices=devices,
        reasons=tooling_reasons,
    )
    return tooling_status, devices


def finalize_tooling_status(
    *,
    name: str,
    probe_command: list[str],
    devices: list[DeviceRecord],
    reasons: list[FleetReason],
) -> ToolingStatus:
    if not devices and any(entry.code.endswith("empty-output") for entry in reasons):
        status = "malformed-output"
        available = False
    elif not devices and any(entry.code.endswith("row-malformed") or entry.code.endswith("json-unrecognized") for entry in reasons):
        status = "malformed-output"
        available = False
    elif reasons:
        status = "ready-with-warnings"
        available = True
    else:
        status = "ready"
        available = True
    return ToolingStatus(
        name=name,
        available=available,
        status=status,
        probe_command=probe_command,
        reasons=dedupe_reasons(reasons),
        metadata={
            "discoveredCount": len(devices),
            "healthyCount": len([device for device in devices if device.available]),
        },
    )


def parse_devicectl_output(output: str) -> tuple[list[dict[str, str]], list[FleetReason]]:
    trimmed = output.strip()
    if not trimmed:
        return [], [reason("ios-devicectl-empty-output", "invalid-environment", "Apple device discovery returned empty output.")]
    if trimmed.startswith("{") or trimmed.startswith("["):
        return parse_devicectl_json(trimmed)
    if "no devices" in trimmed.lower() and "found" in trimmed.lower():
        return [], []
    return parse_devicectl_text(output)


def parse_devicectl_json(output: str) -> tuple[list[dict[str, str]], list[FleetReason]]:
    try:
        payload = json.loads(output)
    except json.JSONDecodeError as error:
        return (
            [],
            [
                reason(
                    "ios-devicectl-json-invalid",
                    "invalid-environment",
                    "Apple device discovery returned invalid JSON output.",
                    error=str(error),
                )
            ],
        )

    rows: list[dict[str, str]] = []
    reasons: list[FleetReason] = []
    seen_control_ids: set[str] = set()
    for node in iter_json_nodes(payload):
        normalized = normalize_devicectl_json_node(node)
        if normalized is None:
            continue
        control_id = normalized["controlId"]
        if control_id in seen_control_ids:
            reasons.append(
                reason(
                    "ios-devicectl-duplicate-control-id",
                    "invalid-environment",
                    "Apple device discovery returned the same iOS control ID more than once.",
                    controlId=control_id,
                )
            )
            continue
        seen_control_ids.add(control_id)
        rows.append(normalized)

    if not rows and payload:
        reasons.append(
            reason(
                "ios-devicectl-json-unrecognized",
                "invalid-environment",
                "Apple device discovery JSON did not contain any recognizable physical iOS devices.",
            )
        )
    return rows, reasons


def iter_json_nodes(value: Any) -> list[dict[str, Any]]:
    nodes: list[dict[str, Any]] = []
    if isinstance(value, dict):
        nodes.append(value)
        for nested in value.values():
            nodes.extend(iter_json_nodes(nested))
    elif isinstance(value, list):
        for nested in value:
            nodes.extend(iter_json_nodes(nested))
    return nodes


def normalize_devicectl_json_node(node: Mapping[str, Any]) -> dict[str, str] | None:
    control_id = first_string(
        node,
        "identifier",
        "udid",
        "deviceIdentifier",
        "deviceIdentifierString",
        "id",
    )
    display_name = first_string(node, "name", "deviceName", "title")
    platform = first_string(node, "platform", "platformName", "osType", "deviceClass", "runtime")
    state = first_string(node, "state", "availability", "status", "connectionState")
    if state is None and isinstance(node.get("isAvailable"), bool):
        state = "available" if node["isAvailable"] else "unavailable"
    if not control_id or not display_name:
        return None
    if platform is None and not device_name_looks_like_ios(display_name):
        return None
    if platform is None:
        platform = "iOS"
    if state is None:
        return None
    if not device_name_looks_like_ios(display_name) and "ios" not in platform.lower():
        return None
    return {
        "displayName": display_name,
        "platform": platform,
        "state": state,
        "controlId": control_id,
    }


def first_string(node: Mapping[str, Any], *keys: str) -> str | None:
    for key in keys:
        value = node.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return None


def parse_devicectl_text(output: str) -> tuple[list[dict[str, str]], list[FleetReason]]:
    rows: list[dict[str, str]] = []
    reasons: list[FleetReason] = []
    seen_control_ids: set[str] = set()

    for raw_line in output.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        if looks_like_devicectl_header(line) or set(line) <= {"-", "=", "|", " "}:
            continue

        parsed, row_reason = parse_devicectl_text_row(line)
        if row_reason is not None:
            reasons.append(row_reason)
            continue
        if parsed is None:
            continue
        control_id = parsed["controlId"]
        if control_id in seen_control_ids:
            reasons.append(
                reason(
                    "ios-devicectl-duplicate-control-id",
                    "invalid-environment",
                    "Apple device discovery returned the same iOS control ID more than once.",
                    controlId=control_id,
                )
            )
            continue
        seen_control_ids.add(control_id)
        rows.append(parsed)

    return rows, reasons


def looks_like_devicectl_header(line: str) -> bool:
    lowered = line.lower()
    return "identifier" in lowered and ("name" in lowered or "device" in lowered)


def parse_devicectl_text_row(line: str) -> tuple[dict[str, str] | None, FleetReason | None]:
    parts = split_devicectl_row(line)
    if not parts:
        return None, None
    identifiers = [part for part in parts if looks_like_ios_identifier(part)]
    if not identifiers:
        if any(hint in line.lower() for hint in IOS_NAME_HINTS):
            return (
                None,
                reason(
                    "ios-devicectl-row-malformed",
                    "invalid-environment",
                    "Ignored a partially parsed iOS device row from Apple discovery output.",
                    line=line,
                ),
            )
        return None, None
    if len(identifiers) > 1:
        return (
            None,
            reason(
                "ios-devicectl-row-ambiguous",
                "invalid-environment",
                "Ignored an ambiguous iOS device row from Apple discovery output.",
                line=line,
            ),
        )

    control_id = identifiers[0]
    display_name = parts[0].strip()
    if not display_name:
        return (
            None,
            reason(
                "ios-devicectl-row-malformed",
                "invalid-environment",
                "Ignored an iOS device row with no device name.",
                line=line,
            ),
        )

    middle_parts = [part for part in parts[1:] if part != control_id]
    middle_text = " ".join(middle_parts)
    if not middle_text and not device_name_looks_like_ios(display_name):
        return (
            None,
            reason(
                "ios-devicectl-row-malformed",
                "invalid-environment",
                "Ignored an iOS device row without platform or state details.",
                line=line,
            ),
        )

    platform = extract_ios_platform(display_name, middle_text)
    state = extract_ios_state(middle_text)
    if platform is None or state is None:
        return (
            None,
            reason(
                "ios-devicectl-row-malformed",
                "invalid-environment",
                "Ignored a partially parsed iOS device row from Apple discovery output.",
                line=line,
            ),
        )

    return (
        {
            "displayName": display_name,
            "platform": platform,
            "state": state,
            "controlId": control_id,
        },
        None,
    )


def split_devicectl_row(line: str) -> list[str]:
    if "|" in line:
        return [part.strip() for part in line.split("|") if part.strip()]
    if "\t" in line:
        return [part.strip() for part in line.split("\t") if part.strip()]
    return [part.strip() for part in re.split(r"\s{2,}", line) if part.strip()]


def looks_like_ios_identifier(token: str) -> bool:
    return bool(IOS_IDENTIFIER_PATTERN.match(token)) and any(character.isdigit() for character in token)


def device_name_looks_like_ios(display_name: str) -> bool:
    lowered = display_name.lower()
    return any(hint in lowered for hint in IOS_NAME_HINTS)


def extract_ios_platform(display_name: str, middle_text: str) -> str | None:
    lowered = middle_text.lower()
    if "ios" in lowered or "iphoneos" in lowered:
        return "iOS"
    if device_name_looks_like_ios(display_name):
        return "iOS"
    return None


def extract_ios_state(middle_text: str) -> str | None:
    lowered = middle_text.lower()
    for hint in (*IOS_AVAILABLE_STATE_HINTS, *IOS_UNAVAILABLE_STATE_HINTS):
        if hint in lowered:
            return hint
    return None


def ios_state_is_available(state: str) -> bool:
    lowered = state.lower()
    return any(hint in lowered for hint in IOS_AVAILABLE_STATE_HINTS) and not any(
        hint in lowered for hint in IOS_UNAVAILABLE_STATE_HINTS
    )


def assign_aliases(devices: list[DeviceRecord]) -> list[DeviceRecord]:
    assigned: list[DeviceRecord] = []
    seen_aliases: set[str] = set()
    for device in sorted(devices, key=lambda entry: (entry.platform, entry.display_name.lower(), entry.control_id)):
        alias = stable_device_alias(
            platform=device.platform,
            display_name=device.display_name,
            control_id=device.control_id,
            seen_aliases=seen_aliases,
        )
        assigned.append(
            DeviceRecord(
                alias=alias,
                platform=device.platform,
                control_id=device.control_id,
                display_name=device.display_name,
                state=device.state,
                available=device.available,
                capabilities=list(device.capabilities),
                reasons=list(device.reasons),
                metadata=dict(device.metadata),
            )
        )
    return assigned


def stable_device_alias(
    *,
    platform: str,
    display_name: str,
    control_id: str,
    seen_aliases: set[str],
) -> str:
    slug = slugify(display_name) or platform
    digest = hashlib.sha1(control_id.encode("utf-8")).hexdigest()[:6]
    alias = f"{platform}-{slug}-{digest}"
    candidate = alias
    counter = 2
    while candidate in seen_aliases:
        candidate = f"{alias}-{counter}"
        counter += 1
    seen_aliases.add(candidate)
    return candidate


def slugify(value: str) -> str:
    normalized = re.sub(r"[^a-z0-9]+", "-", value.lower()).strip("-")
    return normalized[:24].strip("-")


def build_candidate_assignments(
    *,
    devices: list[DeviceRecord],
    tooling: dict[str, ToolingStatus],
) -> list[CandidateAssignment]:
    android_devices = [device for device in devices if device.platform == "android"]
    ios_devices = [device for device in devices if device.platform == "ios"]
    healthy_android = [device for device in android_devices if device.available]
    healthy_ios = [device for device in ios_devices if device.available]

    mixed_sender = healthy_ios[0] if healthy_ios else (ios_devices[0] if ios_devices else None)
    mixed_passive = healthy_android[0] if healthy_android else (android_devices[0] if android_devices else None)
    mixed_reasons: list[FleetReason] = []
    if mixed_sender is None:
        if tooling["devicectl"].available:
            mixed_reasons.append(
                reason(
                    "ios-sender-unavailable",
                    "skip",
                    "No physical iOS sender was discovered for the preferred mixed direct baseline.",
                )
            )
        else:
            mixed_reasons.extend(tooling["devicectl"].reasons)
    elif not mixed_sender.available:
        mixed_reasons.extend(mixed_sender.reasons)

    if not tooling["developmentTeam"].available:
        mixed_reasons.extend(tooling["developmentTeam"].reasons)

    if mixed_passive is None:
        if tooling["adb"].available:
            mixed_reasons.append(
                reason(
                    "android-passive-required",
                    "skip",
                    "At least one healthy Android passive device is required for the preferred mixed direct baseline.",
                )
            )
        else:
            mixed_reasons.extend(tooling["adb"].reasons)
    elif not mixed_passive.available:
        mixed_reasons.extend(mixed_passive.reasons)

    mixed_runnable = (
        mixed_sender is not None
        and mixed_sender.available
        and tooling["devicectl"].available
        and tooling["developmentTeam"].available
        and mixed_passive is not None
        and mixed_passive.available
        and tooling["adb"].available
    )
    if mixed_runnable:
        mixed_reasons.append(
            reason(
                "mixed-direct-guided-runnable",
                "info",
                "The preferred mixed direct-guided baseline is runnable.",
            )
        )
    mixed_status = candidate_status(runnable=mixed_runnable, reasons=mixed_reasons)

    android_only_devices = healthy_android[:2] if len(healthy_android) >= 2 else android_devices[:2]
    android_sender = android_only_devices[0] if len(android_only_devices) >= 1 else None
    android_passive = android_only_devices[1] if len(android_only_devices) >= 2 else None
    android_only_reasons: list[FleetReason] = []
    if len(android_devices) < 2:
        if tooling["adb"].available:
            android_only_reasons.append(
                reason(
                    "android-fleet-too-small",
                    "skip",
                    "At least two Android devices are required for the Android-only direct baseline.",
                )
            )
        else:
            android_only_reasons.extend(tooling["adb"].reasons)
    elif len(healthy_android) < 2:
        android_only_reasons.append(
            reason(
                "android-devices-not-healthy",
                "invalid-environment",
                "Android discovery found enough devices for the Android-only baseline, but fewer than two are healthy.",
            )
        )
        if android_sender is not None:
            android_only_reasons.extend(android_sender.reasons)
        if android_passive is not None:
            android_only_reasons.extend(android_passive.reasons)

    android_only_runnable = len(healthy_android) >= 2 and tooling["adb"].available
    if android_only_runnable:
        android_only_reasons.append(
            reason(
                "android-only-direct-guided-runnable",
                "info",
                "The Android-only direct-guided fallback baseline is runnable.",
            )
        )
    android_only_status = candidate_status(runnable=android_only_runnable, reasons=android_only_reasons)

    return [
        CandidateAssignment(
            assignment_id="direct-guided-mixed",
            baseline="direct-guided",
            shape="mixed",
            participants={
                "sender": mixed_sender.alias if mixed_sender is not None else None,
                "passive": mixed_passive.alias if mixed_passive is not None else None,
            },
            considered_device_aliases={
                "ios": [device.alias for device in ios_devices],
                "android": [device.alias for device in android_devices],
            },
            status=mixed_status,
            runnable=mixed_runnable,
            reasons=dedupe_reasons(mixed_reasons),
            priority=0,
        ),
        CandidateAssignment(
            assignment_id="direct-guided-android-only",
            baseline="direct-guided",
            shape="android-only",
            participants={
                "sender": android_sender.alias if android_sender is not None else None,
                "passive": android_passive.alias if android_passive is not None else None,
            },
            considered_device_aliases={
                "android": [device.alias for device in android_devices],
            },
            status=android_only_status,
            runnable=android_only_runnable,
            reasons=dedupe_reasons(android_only_reasons),
            priority=1,
        ),
    ]


def candidate_status(*, runnable: bool, reasons: list[FleetReason]) -> str:
    if runnable:
        return "runnable"
    if any(entry.kind == "invalid-environment" for entry in reasons):
        return "invalid-environment"
    return "skipped"


def select_assignment(candidates: list[CandidateAssignment]) -> SelectionSummary:
    mixed_candidate = next(candidate for candidate in candidates if candidate.shape == "mixed")
    android_only_candidate = next(candidate for candidate in candidates if candidate.shape == "android-only")

    if mixed_candidate.runnable:
        return SelectionSummary(
            status="selected",
            selected_assignment_id=mixed_candidate.assignment_id,
            reasons=[
                reason(
                    "selected-preferred-mixed",
                    "info",
                    "Selected the preferred mixed direct-guided baseline because an iOS sender and Android passive are runnable.",
                )
            ],
        )
    if android_only_candidate.runnable:
        return SelectionSummary(
            status="selected",
            selected_assignment_id=android_only_candidate.assignment_id,
            reasons=[
                reason(
                    "selected-android-only-fallback",
                    "info",
                    "Selected the Android-only direct-guided fallback because the preferred mixed baseline is not runnable.",
                )
            ],
        )

    if any(candidate.status == "invalid-environment" for candidate in candidates):
        return SelectionSummary(
            status="invalid-environment",
            selected_assignment_id=None,
            reasons=dedupe_reasons(
                [
                    reason(
                        "no-runnable-candidate",
                        "invalid-environment",
                        "No runnable direct baseline was found because the discovered fleet or tooling is not healthy enough.",
                    ),
                    *[entry for candidate in candidates for entry in candidate.reasons if entry.kind != "info"],
                ]
            ),
        )

    return SelectionSummary(
        status="skipped",
        selected_assignment_id=None,
        reasons=dedupe_reasons(
            [
                reason(
                    "no-runnable-candidate",
                    "skip",
                    "No runnable direct baseline was found for the discovered fleet shape.",
                ),
                *[entry for candidate in candidates for entry in candidate.reasons if entry.kind != "info"],
            ]
        ),
    )


def dedupe_reasons(reasons: list[FleetReason]) -> list[FleetReason]:
    deduped: list[FleetReason] = []
    seen: set[tuple[str, str, str | None]] = set()
    for entry in reasons:
        key = (entry.code, entry.kind, entry.subject)
        if key in seen:
            continue
        seen.add(key)
        deduped.append(entry)
    return deduped
