#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Mapping, Sequence

import campaign_report_data
import reference_fleet
import render_reference_release_review_report

DEFAULT_APP_ID_PREFIX = "demo.meshlink.reference.release"
DEFAULT_CHILD_TIMEOUT_SECONDS = 30 * 60
EXIT_PASS = 0
EXIT_FAIL = 1
EXIT_SKIPPED = 2
EXIT_INVALID_ENVIRONMENT = 3
INVALID_ENVIRONMENT_MARKERS = (
    "invalid-environment",
    "environment-sentinel=invalid",
)

SCRIPTS_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPTS_DIR.parents[1]
SCENARIO_CATALOG_VERSION = 1
CAMPAIGN_STATE_VERSION = 2
SCENARIO_ARTIFACT_KEYS = (
    "summary",
    "analysisJson",
    "analysisMarkdown",
    "runnerStdout",
    "runnerStderr",
    "analysisStdout",
    "analysisStderr",
)
RUNNER_SCRIPTS = {
    "direct-guided-mixed": SCRIPTS_DIR / "run_headless_reference_live_proof.py",
    "direct-guided-android-only": SCRIPTS_DIR / "run_headless_reference_android_direct_proof.py",
}
RELAY_RUNNER_SCRIPT = SCRIPTS_DIR / "run_headless_reference_relay_proof.py"
ANALYSIS_SCRIPT = SCRIPTS_DIR / "analyze_reference_physical_run.py"

ManifestBuilder = Callable[[], dict[str, Any]]
ProcessRunner = Callable[[Sequence[str], float | None], reference_fleet.ProbeResult]


class CampaignError(RuntimeError):
    def __init__(
        self,
        code: str,
        kind: str,
        message: str,
        *,
        subject: str | None = None,
        **details: Any,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.kind = kind
        self.subject = subject
        self.details = details

    def to_reason(self) -> dict[str, Any]:
        return reference_fleet.reason(
            self.code,
            self.kind,
            str(self),
            subject=self.subject,
            **self.details,
        ).to_dict()


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Discover the available MeshLink reference-app fleet, retain fleet-manifest.json, "
            "campaign-plan.json, and campaign-state.json, plan the ordered happy-path scenario "
            "catalog, and execute the first eligible direct baseline without manual serial or UDID flags."
        ),
        epilog=(
            f"Exit codes: {EXIT_PASS}=pass, {EXIT_FAIL}=fail, "
            f"{EXIT_SKIPPED}=skipped, {EXIT_INVALID_ENVIRONMENT}=invalid-environment."
        ),
    )
    parser.add_argument(
        "--run-root",
        help="Campaign run root. Defaults to /tmp/reference_release_campaign_<timestamp>.",
    )
    parser.add_argument(
        "--child-timeout-seconds",
        type=float,
        default=DEFAULT_CHILD_TIMEOUT_SECONDS,
        help="Hard timeout for the selected child runner and retained analyzer.",
    )
    return parser.parse_args(argv)


def iso_timestamp() -> str:
    return datetime.now(timezone.utc).isoformat()


def compact_timestamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S")


def default_run_root() -> Path:
    return Path("/tmp") / f"reference_release_campaign_{compact_timestamp()}"


def relative_path(path: Path, *, run_root: Path) -> str:
    return str(path.relative_to(run_root))


def display_path(path: Path) -> str:
    try:
        return str(path.relative_to(PROJECT_ROOT))
    except ValueError:
        return str(path)


def display_command(command: Sequence[str]) -> list[str]:
    rendered: list[str] = []
    for part in command:
        candidate = Path(str(part))
        if candidate.is_absolute() and candidate.exists():
            rendered.append(display_path(candidate))
        else:
            rendered.append(str(part))
    return rendered


def default_manifest_builder() -> dict[str, Any]:
    return reference_fleet.build_fleet_manifest()


def process_subprocess(
    command: Sequence[str],
    timeout_seconds: float | None = None,
) -> reference_fleet.ProbeResult:
    return reference_fleet.subprocess_runner(command, timeout_seconds=timeout_seconds)


def write_json_document(path: Path, payload: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    reference_fleet.write_manifest(path, payload)


def load_json_object(path: Path) -> tuple[dict[str, Any] | None, str | None]:
    if not path.exists():
        return None, f"{path.name} is missing"
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        return None, (
            f"{path.name} could not be parsed: {error.msg} "
            f"(line {error.lineno}, column {error.colno})"
        )
    if not isinstance(payload, dict):
        return None, f"{path.name} must contain a JSON object"
    return payload, None


def dedupe_reason_dicts(reasons: list[dict[str, Any]]) -> list[dict[str, Any]]:
    deduped: list[dict[str, Any]] = []
    seen: set[tuple[str, str, str | None]] = set()
    for reason in reasons:
        key = (
            str(reason.get("code")),
            str(reason.get("kind")),
            str(reason.get("subject")) if reason.get("subject") is not None else None,
        )
        if key in seen:
            continue
        seen.add(key)
        deduped.append(reason)
    return deduped


def append_campaign_event(manifest: dict[str, Any], event: str, **details: Any) -> None:
    manifest.setdefault("campaignLog", []).append(
        {
            "ts": iso_timestamp(),
            "event": event,
            **{key: value for key, value in details.items() if value is not None},
        }
    )


def discovery_failure_manifest(run_root: Path, error: Exception) -> dict[str, Any]:
    manifest = {
        "manifestVersion": 1,
        "generatedAt": iso_timestamp(),
        "tooling": {},
        "devices": [],
        "candidateAssignments": [],
        "selectedAssignment": None,
        "selection": {
            "status": "invalid-environment",
            "selectedAssignmentId": None,
            "reasons": [
                reference_fleet.reason(
                    "fleet-discovery-failed",
                    "invalid-environment",
                    "Fleet discovery failed before a campaign baseline could be selected.",
                    error=str(error),
                ).to_dict()
            ],
        },
        "selectionLog": [
            {
                "event": "fleet-discovery-failed",
                "status": "invalid-environment",
                "assignmentId": None,
                "reasonCodes": ["fleet-discovery-failed"],
            }
        ],
    }
    initialize_campaign_state(manifest, run_root=run_root)
    append_campaign_event(manifest, "campaign-discovery-failed", error=str(error))
    return manifest


def scenario_reason(code: str, kind: str, message: str, **details: Any) -> dict[str, Any]:
    return reference_fleet.reason(code, kind, message, **details).to_dict()


def matching_devices_by_alias(
    manifest: Mapping[str, Any],
    alias: str | None,
) -> list[dict[str, Any]]:
    if alias is None:
        return []
    return [
        dict(device)
        for device in manifest.get("devices", [])
        if isinstance(device, Mapping) and device.get("alias") == alias
    ]


def platform_devices(manifest: Mapping[str, Any], platform: str) -> list[dict[str, Any]]:
    return [
        dict(device)
        for device in manifest.get("devices", [])
        if isinstance(device, Mapping) and device.get("platform") == platform
    ]


def healthy_platform_devices(manifest: Mapping[str, Any], platform: str) -> list[dict[str, Any]]:
    return [device for device in platform_devices(manifest, platform) if device.get("available")]


def tooling_status(manifest: Mapping[str, Any], tooling_name: str) -> Mapping[str, Any]:
    tooling = manifest.get("tooling")
    if not isinstance(tooling, Mapping):
        return {}
    candidate = tooling.get(tooling_name)
    return candidate if isinstance(candidate, Mapping) else {}


def tooling_reasons(manifest: Mapping[str, Any], tooling_name: str) -> list[dict[str, Any]]:
    status = tooling_status(manifest, tooling_name)
    return list(status.get("reasons", [])) if isinstance(status.get("reasons"), list) else []


def resolve_unique_device_alias(
    manifest: Mapping[str, Any],
    alias: str | None,
    *,
    role: str,
    expected_platform: str,
    code_prefix: str,
    message_context: str,
    assignment_id: str | None = None,
    scenario_id: str | None = None,
) -> dict[str, Any]:
    if alias is None:
        raise CampaignError(
            f"{code_prefix}-alias-missing",
            "invalid-environment",
            f"{message_context} is missing the {role} participant alias.",
            role=role,
            assignmentId=assignment_id,
            scenarioId=scenario_id,
        )
    matches = matching_devices_by_alias(manifest, str(alias))
    if not matches:
        raise CampaignError(
            f"{code_prefix}-device-missing",
            "invalid-environment",
            f"{message_context} points at a device alias that is not present in the retained fleet manifest.",
            role=role,
            alias=alias,
            assignmentId=assignment_id,
            scenarioId=scenario_id,
        )
    if len(matches) > 1:
        raise CampaignError(
            f"{code_prefix}-device-duplicate",
            "invalid-environment",
            f"{message_context} points at a duplicated device alias in the retained fleet manifest.",
            role=role,
            alias=alias,
            assignmentId=assignment_id,
            scenarioId=scenario_id,
        )
    device = matches[0]
    if device.get("platform") != expected_platform:
        raise CampaignError(
            f"{code_prefix}-platform-mismatch",
            "invalid-environment",
            f"{message_context} points at a device with the wrong platform for the requested role.",
            role=role,
            alias=alias,
            expectedPlatform=expected_platform,
            actualPlatform=device.get("platform"),
            assignmentId=assignment_id,
            scenarioId=scenario_id,
        )
    return device


def run_directory_artifacts(*, run_root: Path, run_directory: Path) -> dict[str, str]:
    return {
        "summary": relative_path(run_directory / "summary.json", run_root=run_root),
        "analysisJson": relative_path(run_directory / "analysis.json", run_root=run_root),
        "analysisMarkdown": relative_path(run_directory / "analysis.md", run_root=run_root),
        "runnerStdout": relative_path(run_directory / "runner.stdout.log", run_root=run_root),
        "runnerStderr": relative_path(run_directory / "runner.stderr.log", run_root=run_root),
        "analysisStdout": relative_path(run_directory / "analysis.stdout.log", run_root=run_root),
        "analysisStderr": relative_path(run_directory / "analysis.stderr.log", run_root=run_root),
    }


def scenario_reason_prefix(scenario: Mapping[str, Any]) -> str:
    scenario_id = str(scenario.get("scenarioId") or "scenario")
    return "baseline" if scenario_id == "direct-guided" else scenario_id


def scenario_label(scenario: Mapping[str, Any]) -> str:
    scenario_id = str(scenario.get("scenarioId") or "scenario")
    return "selected baseline" if scenario_id == "direct-guided" else f"{scenario_id} happy-path scenario"


def scenario_event_history(scenario: dict[str, Any]) -> list[dict[str, Any]]:
    history = scenario.get("eventHistory")
    if isinstance(history, list):
        return history
    initialized_history: list[dict[str, Any]] = []
    scenario["eventHistory"] = initialized_history
    return initialized_history


def append_scenario_event(scenario: dict[str, Any], event: str, **details: Any) -> None:
    scenario_event_history(scenario).append(
        {
            "ts": iso_timestamp(),
            "event": event,
            **{key: value for key, value in details.items() if value is not None},
        }
    )


def initialize_scenario_history(scenario: dict[str, Any]) -> None:
    append_scenario_event(
        scenario,
        "scenario-initialized",
        status=scenario.get("status"),
        eligibilityStatus=scenario.get("eligibilityStatus"),
        runDirectory=scenario.get("runDirectory"),
        reasonCodes=[
            reason.get("code")
            for reason in scenario.get("reasons", [])
            if isinstance(reason, Mapping) and reason.get("code") is not None
        ],
    )


def ordered_campaign_scenarios(manifest: Mapping[str, Any]) -> list[dict[str, Any]]:
    campaign = manifest.get("campaign")
    if not isinstance(campaign, Mapping):
        return []
    scenarios = [
        scenario
        for scenario in campaign.get("scenarios", [])
        if isinstance(scenario, dict)
    ]
    scenarios.sort(key=lambda scenario: int(scenario.get("order") or 0))
    return scenarios


def aggregate_campaign_status(scenarios: Sequence[Mapping[str, Any]]) -> str:
    statuses = [str(scenario.get("status") or "") for scenario in scenarios]
    if any(status == "invalid-environment" for status in statuses):
        return "invalid-environment"
    if any(status == "fail" for status in statuses):
        return "fail"
    if any(status == "pass" for status in statuses):
        return "pass"
    if any(status == "skipped" for status in statuses):
        return "skipped"
    if any(status == "running" for status in statuses):
        return "running"
    if any(status == "planned" for status in statuses):
        return "planned"
    return "invalid-environment"


def invoke_process_runner(
    process_runner: ProcessRunner,
    command: Sequence[str],
    *,
    timeout_seconds: float,
) -> reference_fleet.ProbeResult:
    normalized_command = [str(part) for part in command]
    try:
        return process_runner(normalized_command, timeout_seconds=timeout_seconds)
    except Exception as error:  # pragma: no cover - defensive conversion for unexpected runner wrappers.
        return reference_fleet.ProbeResult(
            command=normalized_command,
            returncode=None,
            error=str(error),
        )


def build_initial_campaign_status(scenarios: Sequence[Mapping[str, Any]]) -> str:
    if any(scenario.get("status") in {"planned", "running"} for scenario in scenarios):
        return "planned"
    if any(scenario.get("status") == "invalid-environment" for scenario in scenarios):
        return "invalid-environment"
    return "skipped"


def choose_direct_guided_projection_candidate(manifest: Mapping[str, Any]) -> Mapping[str, Any] | None:
    selection = manifest.get("selection")
    if isinstance(selection, Mapping) and selection.get("status") == "selected":
        selected_assignment = manifest.get("selectedAssignment")
        if isinstance(selected_assignment, Mapping):
            return selected_assignment

    candidates = [
        candidate
        for candidate in manifest.get("candidateAssignments", [])
        if isinstance(candidate, Mapping) and candidate.get("baseline") == "direct-guided"
    ]
    if not candidates:
        return None

    invalid_candidates = sorted(
        [candidate for candidate in candidates if candidate.get("status") == "invalid-environment"],
        key=lambda candidate: int(candidate.get("priority") or 0),
    )
    if invalid_candidates:
        return invalid_candidates[0]

    mixed_candidate = next((candidate for candidate in candidates if candidate.get("shape") == "mixed"), None)
    android_only_candidate = next(
        (candidate for candidate in candidates if candidate.get("shape") == "android-only"),
        None,
    )
    if platform_devices(manifest, "ios"):
        return mixed_candidate or android_only_candidate or candidates[0]
    return android_only_candidate or mixed_candidate or candidates[0]


def direct_run_directory(
    *,
    run_root: Path,
    assignment_id: str | None,
    assignment_shape: str | None,
) -> Path:
    projected_assignment = assignment_id
    if not projected_assignment:
        projected_assignment = (
            "direct-guided-android-only"
            if assignment_shape == "android-only"
            else "direct-guided-mixed"
        )
    return run_root / "baseline" / projected_assignment


def direct_runner_script(candidate: Mapping[str, Any] | None) -> str | None:
    if candidate is None:
        return None
    assignment_id = str(candidate.get("assignmentId") or "")
    if assignment_id in RUNNER_SCRIPTS:
        return display_path(RUNNER_SCRIPTS[assignment_id])
    if candidate.get("shape") == "android-only":
        return display_path(RUNNER_SCRIPTS["direct-guided-android-only"])
    return display_path(RUNNER_SCRIPTS["direct-guided-mixed"])


def build_direct_guided_scenario(manifest: Mapping[str, Any], *, run_root: Path) -> dict[str, Any]:
    selection = manifest.get("selection")
    selection_status = str(selection.get("status") or "invalid-environment") if isinstance(selection, Mapping) else "invalid-environment"
    projection = choose_direct_guided_projection_candidate(manifest)
    assignment_id = str(projection.get("assignmentId") or "") if isinstance(projection, Mapping) else None
    assignment_shape = str(projection.get("shape") or "") if isinstance(projection, Mapping) else None
    run_directory = direct_run_directory(
        run_root=run_root,
        assignment_id=assignment_id,
        assignment_shape=assignment_shape,
    )

    reasons: list[dict[str, Any]] = []
    if isinstance(projection, Mapping):
        reasons.extend(list(projection.get("reasons", [])))
    if isinstance(selection, Mapping):
        reasons.extend(list(selection.get("reasons", [])))
    else:
        reasons.append(
            CampaignError(
                "selection-missing",
                "invalid-environment",
                "Fleet manifest is missing a selection block.",
            ).to_reason()
        )

    scenario = {
        "scenarioId": "direct-guided",
        "order": 1,
        "baseline": "direct-guided",
        "assignmentId": assignment_id,
        "assignmentShape": assignment_shape,
        "participants": dict(projection.get("participants", {})) if isinstance(projection, Mapping) else {},
        "runnerScript": direct_runner_script(projection),
        "analysisScript": display_path(ANALYSIS_SCRIPT),
        "runnerCommand": None,
        "analysisCommand": None,
        "runDirectory": relative_path(run_directory, run_root=run_root),
        "appId": None,
        "initialStatus": None,
        "status": None,
        "eligibilityStatus": None,
        "startedAt": None,
        "finishedAt": None,
        "childExitCode": None,
        "analysisExitCode": None,
        "analysisStatus": None,
        "timedOut": False,
        "artifacts": run_directory_artifacts(run_root=run_root, run_directory=run_directory),
        "reasons": [],
        "eventHistory": [],
    }

    if selection_status == "selected":
        try:
            spec = resolve_runner_spec(manifest, run_root=run_root)
            scenario.update(
                {
                    "assignmentId": spec.get("assignmentId"),
                    "assignmentShape": spec.get("shape"),
                    "participants": dict(spec.get("participants", {})),
                    "runnerScript": spec.get("runnerScript"),
                    "analysisScript": spec.get("analysisScript"),
                    "runnerCommand": display_command(spec["runnerCommand"]),
                    "analysisCommand": display_command(spec["analysisCommand"]),
                    "runDirectory": relative_path(spec["runDirectory"], run_root=run_root),
                    "appId": spec.get("appId"),
                    "initialStatus": "planned",
                    "status": "planned",
                    "eligibilityStatus": "runnable",
                    "artifacts": dict(spec.get("artifacts", {})),
                }
            )
        except CampaignError as error:
            reasons.append(error.to_reason())
            scenario["initialStatus"] = "invalid-environment"
            scenario["status"] = "invalid-environment"
            scenario["eligibilityStatus"] = "invalid-environment"
    elif selection_status == "skipped":
        scenario["initialStatus"] = "skipped"
        scenario["status"] = "skipped"
        scenario["eligibilityStatus"] = "skipped"
    else:
        if selection_status not in {"invalid-environment", "selected", "skipped"}:
            reasons.append(
                scenario_reason(
                    "selection-status-invalid",
                    "invalid-environment",
                    "Fleet manifest reported an unknown selection status.",
                    selectionStatus=selection_status,
                )
            )
        scenario["initialStatus"] = "invalid-environment"
        scenario["status"] = "invalid-environment"
        scenario["eligibilityStatus"] = "invalid-environment"

    scenario["reasons"] = dedupe_reason_dicts(reasons)
    initialize_scenario_history(scenario)
    return scenario


def build_relay_constrained_scenario(
    manifest: Mapping[str, Any],
    *,
    run_root: Path,
    direct_scenario: Mapping[str, Any],
) -> dict[str, Any]:
    run_directory = run_root / "scenarios" / "02-relay-constrained"
    reasons: list[dict[str, Any]] = []
    scenario = {
        "scenarioId": "relay-constrained",
        "order": 2,
        "baseline": "relay-constrained",
        "assignmentId": "relay-constrained",
        "assignmentShape": "mixed",
        "participants": {},
        "runnerScript": display_path(RELAY_RUNNER_SCRIPT),
        "analysisScript": display_path(ANALYSIS_SCRIPT),
        "runnerCommand": None,
        "analysisCommand": None,
        "runDirectory": relative_path(run_directory, run_root=run_root),
        "appId": None,
        "initialStatus": None,
        "status": None,
        "eligibilityStatus": None,
        "startedAt": None,
        "finishedAt": None,
        "childExitCode": None,
        "analysisExitCode": None,
        "analysisStatus": None,
        "timedOut": False,
        "artifacts": run_directory_artifacts(run_root=run_root, run_directory=run_directory),
        "reasons": [],
        "eventHistory": [],
    }

    direct_status = str(direct_scenario.get("status") or "")
    direct_analysis_status = str(direct_scenario.get("analysisStatus") or "")
    direct_reasons = direct_scenario.get("reasons", []) if isinstance(direct_scenario.get("reasons"), list) else []
    direct_has_analysis_invalid_env = direct_analysis_status == "invalid-environment" or any(
        isinstance(reason, Mapping)
        and str(reason.get("code") or "").endswith("analysis-invalid-environment")
        for reason in direct_reasons
    )
    if direct_scenario.get("eligibilityStatus") == "invalid-environment":
        reasons.append(
            scenario_reason(
                "relay-prerequisite-invalid",
                "invalid-environment",
                "relay-constrained is not runnable because direct-guided could not be planned from the retained fleet manifest.",
            )
        )
    if direct_status == "invalid-environment" or direct_has_analysis_invalid_env:
        reasons.append(
            scenario_reason(
                "relay-constrained-analysis-invalid-environment",
                "invalid-environment",
                "relay-constrained inherited an explicit invalid-environment signal from the direct-guided analysis path.",
            )
        )

    adb_ready = bool(tooling_status(manifest, "adb").get("available"))
    devicectl_ready = bool(tooling_status(manifest, "devicectl").get("available"))
    development_team_ready = bool(tooling_status(manifest, "developmentTeam").get("available"))
    if not adb_ready:
        reasons.extend(tooling_reasons(manifest, "adb"))
    if not devicectl_ready:
        reasons.extend(tooling_reasons(manifest, "devicectl"))
    if not development_team_ready:
        reasons.extend(tooling_reasons(manifest, "developmentTeam"))

    ios_devices = platform_devices(manifest, "ios")
    healthy_ios = healthy_platform_devices(manifest, "ios")
    android_devices = platform_devices(manifest, "android")
    healthy_android = healthy_platform_devices(manifest, "android")

    if not ios_devices and devicectl_ready:
        reasons.append(
            scenario_reason(
                "relay-ios-sender-required",
                "skip",
                "relay-constrained requires one healthy iOS sender from the retained fleet manifest.",
            )
        )
    elif not healthy_ios and ios_devices:
        reasons.append(
            scenario_reason(
                "relay-ios-sender-unhealthy",
                "invalid-environment",
                "relay-constrained requires a healthy iOS sender, but none of the discovered iOS devices are runnable.",
            )
        )

    if len(android_devices) < 2 and adb_ready:
        reasons.append(
            scenario_reason(
                "relay-android-fleet-too-small",
                "skip",
                "relay-constrained requires two healthy Android devices from the retained fleet manifest.",
            )
        )
        if not any(
            reason.get("code") == "relay-constrained-analysis-invalid-environment"
            for reason in reasons
            if isinstance(reason, Mapping)
        ):
            reasons.append(
                scenario_reason(
                    "relay-constrained-analysis-invalid-environment",
                    "info",
                    "relay-constrained retains the explicit invalid-environment sentinel alongside the fleet-size skip reason.",
                )
            )
    elif len(healthy_android) < 2 and android_devices:
        reasons.append(
            scenario_reason(
                "relay-android-devices-unhealthy",
                "invalid-environment",
                "relay-constrained requires two healthy Android devices, but fewer than two are runnable.",
            )
        )

    sender_device: dict[str, Any] | None = None
    passive_device: dict[str, Any] | None = None
    relay_device: dict[str, Any] | None = None
    preferred_sender_alias = None
    preferred_passive_alias = None
    preferred_relay_participants = (
        direct_scenario.get("eligibilityStatus") == "runnable"
        and direct_scenario.get("assignmentShape") == "mixed"
    )
    if preferred_relay_participants:
        preferred_sender_alias = direct_scenario.get("participants", {}).get("sender")
        preferred_passive_alias = direct_scenario.get("participants", {}).get("passive")
        try:
            sender_device = resolve_unique_device_alias(
                manifest,
                preferred_sender_alias,
                role="sender",
                expected_platform="ios",
                code_prefix="relay-participant",
                message_context="relay-constrained participant selection",
                scenario_id="relay-constrained",
            )
            passive_device = resolve_unique_device_alias(
                manifest,
                preferred_passive_alias,
                role="passive",
                expected_platform="android",
                code_prefix="relay-participant",
                message_context="relay-constrained participant selection",
                scenario_id="relay-constrained",
            )
        except CampaignError as error:
            reasons.append(error.to_reason())
    else:
        sender_device = healthy_ios[0] if healthy_ios else None
        passive_device = healthy_android[0] if healthy_android else None

    if sender_device is not None and not sender_device.get("available"):
        reasons.append(
            scenario_reason(
                "relay-ios-sender-unhealthy",
                "invalid-environment",
                "relay-constrained selected an iOS sender that is not runnable.",
                alias=sender_device.get("alias"),
            )
        )
        sender_device = None
    if passive_device is not None and not passive_device.get("available"):
        reasons.append(
            scenario_reason(
                "relay-android-passive-unhealthy",
                "invalid-environment",
                "relay-constrained selected an Android passive device that is not runnable.",
                alias=passive_device.get("alias"),
            )
        )
        passive_device = None

    if passive_device is not None:
        relay_device = next(
            (
                device
                for device in healthy_android
                if device.get("alias") != passive_device.get("alias")
            ),
            None,
        )
    if relay_device is None and len(healthy_android) >= 2 and passive_device is None:
        relay_device = healthy_android[1]
    if relay_device is not None and not relay_device.get("available"):
        reasons.append(
            scenario_reason(
                "relay-android-relay-unhealthy",
                "invalid-environment",
                "relay-constrained selected an Android relay device that is not runnable.",
                alias=relay_device.get("alias"),
            )
        )
        relay_device = None

    scenario["participants"] = {
        "sender": sender_device.get("alias") if sender_device is not None else preferred_sender_alias,
        "relay": relay_device.get("alias") if relay_device is not None else None,
        "passive": passive_device.get("alias") if passive_device is not None else preferred_passive_alias,
    }

    if (
        passive_device is not None
        and relay_device is not None
        and passive_device.get("alias") == relay_device.get("alias")
    ):
        reasons.append(
            scenario_reason(
                "relay-android-participants-duplicate",
                "invalid-environment",
                "relay-constrained resolved the same Android alias for both the relay and passive roles.",
                alias=passive_device.get("alias"),
            )
        )

    runnable = (
        sender_device is not None
        and passive_device is not None
        and relay_device is not None
        and adb_ready
        and devicectl_ready
        and development_team_ready
        and not any(reason.get("kind") == "invalid-environment" for reason in reasons)
        and not any(reason.get("kind") == "skip" for reason in reasons)
    )
    if runnable:
        app_id = build_app_id("relay-constrained")
        runner_command = [
            "python3",
            str(RELAY_RUNNER_SCRIPT),
            "--ios-device",
            str(sender_device["controlId"]),
            "--relay-android-serial",
            str(relay_device["controlId"]),
            "--passive-android-serial",
            str(passive_device["controlId"]),
            "--app-id",
            app_id,
            "--run-dir",
            str(run_directory),
        ]
        analysis_command = [
            "python3",
            str(ANALYSIS_SCRIPT),
            "--run-dir",
            str(run_directory),
        ]
        scenario.update(
            {
                "appId": app_id,
                "runnerCommand": display_command(runner_command),
                "analysisCommand": display_command(analysis_command),
            }
        )
        reasons.append(
            scenario_reason(
                "relay-constrained-runnable",
                "info",
                "relay-constrained is runnable from the retained happy-path fleet projection.",
            )
        )
        scenario["eligibilityStatus"] = "runnable"
        scenario["initialStatus"] = "planned"
        scenario["status"] = "planned"
    else:
        eligibility_status = (
            "invalid-environment"
            if any(reason.get("kind") == "invalid-environment" for reason in reasons)
            else "skipped"
        )
        scenario["eligibilityStatus"] = eligibility_status
        scenario["initialStatus"] = eligibility_status
        scenario["status"] = eligibility_status

    scenario["reasons"] = dedupe_reason_dicts(reasons)
    initialize_scenario_history(scenario)
    return scenario


def build_happy_path_scenarios(manifest: Mapping[str, Any], *, run_root: Path) -> list[dict[str, Any]]:
    direct_scenario = build_direct_guided_scenario(manifest, run_root=run_root)
    relay_scenario = build_relay_constrained_scenario(
        manifest,
        run_root=run_root,
        direct_scenario=direct_scenario,
    )
    return [direct_scenario, relay_scenario]


def baseline_execution_from_scenario(scenario: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "status": scenario.get("status"),
        "assignmentId": scenario.get("assignmentId"),
        "baseline": scenario.get("baseline"),
        "shape": scenario.get("assignmentShape"),
        "participants": dict(scenario.get("participants", {})),
        "runnerScript": scenario.get("runnerScript"),
        "analysisScript": scenario.get("analysisScript"),
        "runnerCommand": list(scenario.get("runnerCommand") or []),
        "analysisCommand": list(scenario.get("analysisCommand") or []),
        "runDirectory": scenario.get("runDirectory"),
        "appId": scenario.get("appId"),
        "startedAt": scenario.get("startedAt"),
        "finishedAt": scenario.get("finishedAt"),
        "childExitCode": scenario.get("childExitCode"),
        "analysisExitCode": scenario.get("analysisExitCode"),
        "analysisStatus": scenario.get("analysisStatus"),
        "timedOut": scenario.get("timedOut"),
        "reasons": list(scenario.get("reasons", [])),
        "artifacts": dict(scenario.get("artifacts", {})),
    }


def serialize_scenario_plan(manifest: Mapping[str, Any], scenario: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "order": scenario.get("order"),
        "scenarioId": scenario.get("scenarioId"),
        "baseline": scenario.get("baseline"),
        "assignmentId": scenario.get("assignmentId"),
        "assignmentShape": scenario.get("assignmentShape"),
        "initialStatus": scenario.get("initialStatus"),
        "eligibilityStatus": scenario.get("eligibilityStatus"),
        "runnable": scenario.get("eligibilityStatus") == "runnable",
        "appId": scenario.get("appId"),
        "participants": resolve_participant_details(
            manifest,
            scenario.get("participants") if isinstance(scenario.get("participants"), Mapping) else {},
        ),
        "reasons": list(scenario.get("reasons", [])),
        "runnerScript": scenario.get("runnerScript"),
        "analysisScript": scenario.get("analysisScript"),
        "runnerCommand": list(scenario.get("runnerCommand") or []),
        "analysisCommand": list(scenario.get("analysisCommand") or []),
        "runDirectory": scenario.get("runDirectory"),
        "artifacts": dict(scenario.get("artifacts", {})),
    }


def serialize_scenario_state(manifest: Mapping[str, Any], scenario: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "order": scenario.get("order"),
        "scenarioId": scenario.get("scenarioId"),
        "baseline": scenario.get("baseline"),
        "assignmentId": scenario.get("assignmentId"),
        "assignmentShape": scenario.get("assignmentShape"),
        "initialStatus": scenario.get("initialStatus"),
        "eligibilityStatus": scenario.get("eligibilityStatus"),
        "status": scenario.get("status"),
        "appId": scenario.get("appId"),
        "participants": resolve_participant_details(
            manifest,
            scenario.get("participants") if isinstance(scenario.get("participants"), Mapping) else {},
        ),
        "reasons": list(scenario.get("reasons", [])),
        "runnerScript": scenario.get("runnerScript"),
        "analysisScript": scenario.get("analysisScript"),
        "runnerCommand": list(scenario.get("runnerCommand") or []),
        "analysisCommand": list(scenario.get("analysisCommand") or []),
        "runDirectory": scenario.get("runDirectory"),
        "startedAt": scenario.get("startedAt"),
        "finishedAt": scenario.get("finishedAt"),
        "childExitCode": scenario.get("childExitCode"),
        "analysisExitCode": scenario.get("analysisExitCode"),
        "analysisStatus": scenario.get("analysisStatus"),
        "timedOut": scenario.get("timedOut"),
        "artifacts": dict(scenario.get("artifacts", {})),
        "eventHistory": list(scenario.get("eventHistory", [])),
    }


def build_campaign_state_document(manifest: Mapping[str, Any]) -> dict[str, Any]:
    campaign = manifest.get("campaign") if isinstance(manifest.get("campaign"), Mapping) else {}
    return {
        "stateVersion": CAMPAIGN_STATE_VERSION,
        "scenarioCatalogVersion": campaign.get("scenarioCatalogVersion"),
        "generatedAt": manifest.get("generatedAt"),
        "updatedAt": iso_timestamp(),
        "status": campaign.get("status"),
        "runRoot": campaign.get("runRoot"),
        "fleetManifestPath": campaign.get("fleetManifestPath"),
        "campaignPlanPath": campaign.get("campaignPlanPath"),
        "happyPathGate": dict(campaign.get("happyPathGate", {})),
        "scenarios": [
            serialize_scenario_state(manifest, scenario)
            for scenario in campaign.get("scenarios", [])
            if isinstance(scenario, Mapping)
        ],
        "eventLog": list(manifest.get("campaignLog", [])),
    }


def plan_happy_path_campaign(manifest: Mapping[str, Any], *, run_root: Path) -> dict[str, Any]:
    scenarios = build_happy_path_scenarios(manifest, run_root=run_root)
    direct_scenario = next(
        scenario for scenario in scenarios if scenario.get("scenarioId") == "direct-guided"
    )
    campaign = {
        "status": build_initial_campaign_status(scenarios),
        "runRoot": str(run_root),
        "fleetManifestPath": "fleet-manifest.json",
        "campaignPlanPath": "campaign-plan.json",
        "campaignStatePath": "campaign-state.json",
        "scenarioCatalogVersion": SCENARIO_CATALOG_VERSION,
        "happyPathGate": {
            "status": "green",
            "firstFailScenarioId": None,
            "triggeredAt": None,
            "updatedAt": iso_timestamp(),
        },
        "scenarios": scenarios,
        "baselineExecution": baseline_execution_from_scenario(direct_scenario),
    }
    working_manifest = dict(manifest)
    working_manifest["campaign"] = campaign
    return {
        "campaign": campaign,
        "plan": build_campaign_plan(working_manifest),
        "state": build_campaign_state_document(working_manifest),
    }


def initialize_campaign_state(manifest: dict[str, Any], *, run_root: Path) -> None:
    planned = plan_happy_path_campaign(manifest, run_root=run_root)
    manifest["campaign"] = planned["campaign"]
    selection = manifest.get("selection") if isinstance(manifest.get("selection"), Mapping) else {}
    append_campaign_event(
        manifest,
        "campaign-initialized",
        selectionStatus=selection.get("status"),
        scenarioIds=[
            scenario.get("scenarioId")
            for scenario in manifest["campaign"].get("scenarios", [])
            if isinstance(scenario, Mapping)
        ],
    )


def exit_code_for_status(status: str) -> int:
    if status == "pass":
        return EXIT_PASS
    if status == "skipped":
        return EXIT_SKIPPED
    if status == "invalid-environment":
        return EXIT_INVALID_ENVIRONMENT
    return EXIT_FAIL


def merge_status(current: str, new_status: str) -> str:
    precedence = {
        "pass": 0,
        "fail": 1,
        "invalid-environment": 2,
    }
    return new_status if precedence.get(new_status, 0) > precedence.get(current, 0) else current


def campaign_paths(run_root: Path) -> tuple[Path, Path, Path]:
    return (
        run_root / "fleet-manifest.json",
        run_root / "campaign-plan.json",
        run_root / "campaign-state.json",
    )


def persist_campaign_state(manifest: dict[str, Any], *, run_root: Path) -> None:
    manifest_path, campaign_plan_path, campaign_state_path = campaign_paths(run_root)
    write_json_document(manifest_path, manifest)
    write_json_document(campaign_plan_path, build_campaign_plan(manifest))
    write_json_document(campaign_state_path, build_campaign_state_document(manifest))
    report_data_path = run_root / campaign_report_data.DEFAULT_OUTPUT_NAME
    campaign_report_data.write_report_data(report_data_path, campaign_report_data.build_report_data(run_root))
    render_reference_release_review_report.render_release_review_report(run_root, report_data_path=report_data_path)


def build_campaign_plan(manifest: Mapping[str, Any]) -> dict[str, Any]:
    campaign = manifest.get("campaign") if isinstance(manifest.get("campaign"), Mapping) else {}
    baseline_execution = (
        campaign.get("baselineExecution")
        if isinstance(campaign.get("baselineExecution"), Mapping)
        else {}
    )
    candidate_assignments = []
    for candidate in manifest.get("candidateAssignments", []):
        if not isinstance(candidate, Mapping):
            continue
        candidate_assignments.append(
            {
                "assignmentId": candidate.get("assignmentId"),
                "baseline": candidate.get("baseline"),
                "shape": candidate.get("shape"),
                "status": candidate.get("status"),
                "runnable": candidate.get("runnable"),
                "participants": resolve_participant_details(
                    manifest,
                    candidate.get("participants") if isinstance(candidate.get("participants"), Mapping) else {},
                ),
                "reasons": list(candidate.get("reasons", [])),
            }
        )

    selection = manifest.get("selection") if isinstance(manifest.get("selection"), Mapping) else {}
    selected_baseline = None
    if selection.get("status") == "selected" and baseline_execution.get("assignmentId"):
        selected_baseline = {
            "assignmentId": baseline_execution.get("assignmentId"),
            "baseline": baseline_execution.get("baseline"),
            "shape": baseline_execution.get("shape"),
            "status": baseline_execution.get("status"),
            "appId": baseline_execution.get("appId"),
            "runnerScript": baseline_execution.get("runnerScript"),
            "runnerCommand": list(baseline_execution.get("runnerCommand") or []),
            "runDirectory": baseline_execution.get("runDirectory"),
            "participants": resolve_participant_details(
                manifest,
                baseline_execution.get("participants")
                if isinstance(baseline_execution.get("participants"), Mapping)
                else {},
            ),
            "artifacts": dict(baseline_execution.get("artifacts", {})),
            "reasons": list(baseline_execution.get("reasons", [])),
        }

    return {
        "planVersion": 2,
        "scenarioCatalogVersion": campaign.get("scenarioCatalogVersion"),
        "generatedAt": manifest.get("generatedAt"),
        "status": campaign.get("status"),
        "runRoot": campaign.get("runRoot"),
        "fleetManifestPath": campaign.get("fleetManifestPath"),
        "campaignStatePath": campaign.get("campaignStatePath"),
        "selection": dict(selection),
        "candidateAssignments": candidate_assignments,
        "selectedBaseline": selected_baseline,
        "scenarios": [
            serialize_scenario_plan(manifest, scenario)
            for scenario in campaign.get("scenarios", [])
            if isinstance(scenario, Mapping)
        ],
    }


def find_campaign_scenario_entry(
    manifest: Mapping[str, Any],
    scenario_id: str,
) -> dict[str, Any] | None:
    campaign = manifest.get("campaign")
    if not isinstance(campaign, Mapping):
        return None
    for scenario in campaign.get("scenarios", []):
        if isinstance(scenario, Mapping) and scenario.get("scenarioId") == scenario_id:
            return scenario
    return None


def sync_baseline_execution_from_direct_scenario(manifest: dict[str, Any]) -> None:
    direct_scenario = find_campaign_scenario_entry(manifest, "direct-guided")
    if direct_scenario is None:
        return
    manifest["campaign"]["baselineExecution"] = baseline_execution_from_scenario(direct_scenario)

    relay_scenario = find_campaign_scenario_entry(manifest, "relay-constrained")
    if relay_scenario is None:
        return

    direct_status = str(direct_scenario.get("status") or "")
    direct_analysis_status = str(direct_scenario.get("analysisStatus") or "")
    direct_reasons = direct_scenario.get("reasons", []) if isinstance(direct_scenario.get("reasons"), list) else []
    direct_has_analysis_invalid_env = direct_analysis_status == "invalid-environment" or any(
        isinstance(reason, Mapping)
        and str(reason.get("code") or "").endswith("analysis-invalid-environment")
        for reason in direct_reasons
    )
    if direct_status == "invalid-environment" or direct_has_analysis_invalid_env:
        relay_code = "relay-constrained-analysis-invalid-environment"
        relay_reason = scenario_reason(
            relay_code,
            "invalid-environment",
            "relay-constrained inherited an explicit invalid-environment signal from the direct-guided analysis path.",
        )
        relay_reasons = relay_scenario.get("reasons", [])
        if not isinstance(relay_reasons, list):
            relay_reasons = []
        relay_scenario["reasons"] = dedupe_reason_dicts([*relay_reasons, relay_reason])


def trip_happy_path_gate(manifest: dict[str, Any], *, scenario_id: str) -> bool:
    gate = manifest["campaign"].get("happyPathGate")
    if not isinstance(gate, dict):
        return False
    if gate.get("firstFailScenarioId") is not None:
        return False
    gate["status"] = "red"
    gate["firstFailScenarioId"] = scenario_id
    gate["triggeredAt"] = iso_timestamp()
    gate["updatedAt"] = gate["triggeredAt"]
    return True


def resolve_participant_details(
    manifest: Mapping[str, Any],
    participants: Mapping[str, Any],
) -> dict[str, dict[str, Any] | None]:
    resolved: dict[str, dict[str, Any] | None] = {}
    for role, alias in participants.items():
        matches = matching_devices_by_alias(manifest, str(alias) if alias is not None else None)
        if len(matches) != 1:
            resolved[str(role)] = None
            continue
        device = matches[0]
        resolved[str(role)] = {
            "alias": device.get("alias"),
            "platform": device.get("platform"),
            "controlId": device.get("controlId"),
            "displayName": device.get("displayName"),
            "state": device.get("state"),
            "available": device.get("available"),
        }
    return resolved


def build_app_id(assignment_id: str) -> str:
    return f"{DEFAULT_APP_ID_PREFIX}.{assignment_id.replace('-', '.')}.{compact_timestamp()}"


def resolve_selected_assignment(manifest: Mapping[str, Any]) -> Mapping[str, Any]:
    selection = manifest.get("selection")
    if not isinstance(selection, Mapping):
        raise CampaignError(
            "selection-missing",
            "invalid-environment",
            "Fleet manifest is missing a selection block.",
        )
    if selection.get("status") != "selected":
        raise CampaignError(
            "selection-not-selected",
            "invalid-environment",
            "Fleet manifest does not contain a selected baseline assignment.",
            selectionStatus=selection.get("status"),
        )
    selected_assignment = manifest.get("selectedAssignment")
    if not isinstance(selected_assignment, Mapping):
        raise CampaignError(
            "selected-assignment-missing",
            "invalid-environment",
            "Fleet manifest reported a selected baseline but did not provide selectedAssignment details.",
        )
    selected_assignment_id = str(selected_assignment.get("assignmentId") or "")
    selection_assignment_id = str(selection.get("selectedAssignmentId") or "")
    if selection_assignment_id and selected_assignment_id != selection_assignment_id:
        raise CampaignError(
            "selected-assignment-mismatch",
            "invalid-environment",
            "Fleet manifest selectedAssignmentId does not match the selectedAssignment payload.",
            selectedAssignmentId=selection_assignment_id,
            assignmentId=selected_assignment_id,
        )
    return selected_assignment


def resolve_required_participant(
    manifest: Mapping[str, Any],
    selected_assignment: Mapping[str, Any],
    role: str,
    *,
    expected_platform: str,
) -> dict[str, Any]:
    participants = selected_assignment.get("participants")
    if not isinstance(participants, Mapping):
        raise CampaignError(
            "selected-assignment-participants-missing",
            "invalid-environment",
            "Selected baseline assignment is missing participant aliases.",
            assignmentId=selected_assignment.get("assignmentId"),
        )
    return resolve_unique_device_alias(
        manifest,
        participants.get(role),
        role=role,
        expected_platform=expected_platform,
        code_prefix="selected-assignment",
        message_context="Selected baseline assignment",
        assignment_id=str(selected_assignment.get("assignmentId") or "") or None,
    )


def resolve_unused_available_android_serials(
    manifest: Mapping[str, Any],
    *,
    selected_devices: Sequence[Mapping[str, Any]],
) -> list[str]:
    selected_serials = {
        str(device.get("controlId") or "")
        for device in selected_devices
        if device.get("platform") == "android" and device.get("controlId")
    }
    extra_serials: list[str] = []
    for device in manifest.get("devices", []):
        if not isinstance(device, Mapping):
            continue
        if device.get("platform") != "android" or not device.get("available"):
            continue
        control_id = str(device.get("controlId") or "")
        if not control_id or control_id in selected_serials:
            continue
        extra_serials.append(control_id)
        selected_serials.add(control_id)
    return extra_serials


def resolve_runner_spec(manifest: Mapping[str, Any], *, run_root: Path) -> dict[str, Any]:
    selected_assignment = resolve_selected_assignment(manifest)
    assignment_id = str(selected_assignment.get("assignmentId") or "")
    run_directory = run_root / "baseline" / assignment_id
    app_id = build_app_id(assignment_id)

    if assignment_id == "direct-guided-mixed":
        sender = resolve_required_participant(
            manifest,
            selected_assignment,
            "sender",
            expected_platform="ios",
        )
        passive = resolve_required_participant(
            manifest,
            selected_assignment,
            "passive",
            expected_platform="android",
        )
        selected_devices = [sender, passive]
        runner_script = RUNNER_SCRIPTS[assignment_id]
        runner_command = [
            "python3",
            str(runner_script),
            "--android-serial",
            str(passive["controlId"]),
            "--ios-device",
            str(sender["controlId"]),
            "--scenario",
            "direct-guided",
            "--app-id",
            app_id,
            "--run-dir",
            str(run_directory),
        ]
    elif assignment_id == "direct-guided-android-only":
        sender = resolve_required_participant(
            manifest,
            selected_assignment,
            "sender",
            expected_platform="android",
        )
        passive = resolve_required_participant(
            manifest,
            selected_assignment,
            "passive",
            expected_platform="android",
        )
        selected_devices = [sender, passive]
        runner_script = RUNNER_SCRIPTS[assignment_id]
        runner_command = [
            "python3",
            str(runner_script),
            "--sender-android-serial",
            str(sender["controlId"]),
            "--passive-android-serial",
            str(passive["controlId"]),
            "--app-id",
            app_id,
            "--run-dir",
            str(run_directory),
        ]
    else:
        raise CampaignError(
            "unknown-runner-assignment",
            "invalid-environment",
            "Selected baseline assignment does not map to a known S01 runner.",
            assignmentId=assignment_id,
        )

    for extra_serial in resolve_unused_available_android_serials(
        manifest,
        selected_devices=selected_devices,
    ):
        runner_command.extend(["--extra-force-stop-serial", extra_serial])

    analysis_command = [
        "python3",
        str(ANALYSIS_SCRIPT),
        "--run-dir",
        str(run_directory),
    ]
    return {
        "assignmentId": assignment_id,
        "baseline": selected_assignment.get("baseline"),
        "shape": selected_assignment.get("shape"),
        "participants": dict(selected_assignment.get("participants", {})),
        "runnerScript": display_path(runner_script),
        "analysisScript": display_path(ANALYSIS_SCRIPT),
        "runnerCommand": runner_command,
        "analysisCommand": analysis_command,
        "runDirectory": run_directory,
        "appId": app_id,
        "artifacts": {
            "summary": relative_path(run_directory / "summary.json", run_root=run_root),
            "analysisJson": relative_path(run_directory / "analysis.json", run_root=run_root),
            "analysisMarkdown": relative_path(run_directory / "analysis.md", run_root=run_root),
            "runnerStdout": relative_path(run_directory / "runner.stdout.log", run_root=run_root),
            "runnerStderr": relative_path(run_directory / "runner.stderr.log", run_root=run_root),
            "analysisStdout": relative_path(run_directory / "analysis.stdout.log", run_root=run_root),
            "analysisStderr": relative_path(run_directory / "analysis.stderr.log", run_root=run_root),
        },
    }


def apply_runner_spec(manifest: dict[str, Any], spec: Mapping[str, Any]) -> None:
    direct_scenario = find_campaign_scenario_entry(manifest, "direct-guided")
    if direct_scenario is not None:
        direct_scenario["assignmentId"] = spec.get("assignmentId")
        direct_scenario["assignmentShape"] = spec.get("shape")
        direct_scenario["participants"] = dict(spec.get("participants", {}))
        direct_scenario["runnerScript"] = spec.get("runnerScript")
        direct_scenario["analysisScript"] = spec.get("analysisScript")
        direct_scenario["runnerCommand"] = display_command(spec["runnerCommand"])
        direct_scenario["analysisCommand"] = display_command(spec["analysisCommand"])
        direct_scenario["runDirectory"] = relative_path(
            spec["runDirectory"],
            run_root=Path(manifest["campaign"]["runRoot"]),
        )
        direct_scenario["appId"] = spec.get("appId")
        direct_scenario["startedAt"] = iso_timestamp()
        direct_scenario["status"] = "running"
        direct_scenario["artifacts"] = dict(spec.get("artifacts", {}))

    sync_baseline_execution_from_direct_scenario(manifest)
    manifest["campaign"]["status"] = "running"
    append_campaign_event(
        manifest,
        "baseline-runner-selected",
        assignmentId=spec.get("assignmentId"),
        runnerScript=spec.get("runnerScript"),
        runDirectory=manifest["campaign"]["baselineExecution"].get("runDirectory"),
    )


def write_command_logs(
    run_directory: Path,
    *,
    prefix: str,
    result: reference_fleet.ProbeResult,
) -> None:
    run_directory.mkdir(parents=True, exist_ok=True)
    stdout_path = run_directory / f"{prefix}.stdout.log"
    stderr_path = run_directory / f"{prefix}.stderr.log"
    stdout_path.write_text(result.stdout or "", encoding="utf-8")
    stderr_text = result.stderr or ""
    if result.error:
        if stderr_text and not stderr_text.endswith("\n"):
            stderr_text += "\n"
        stderr_text += result.error
        if not stderr_text.endswith("\n"):
            stderr_text += "\n"
    stderr_path.write_text(stderr_text, encoding="utf-8")


def contains_explicit_invalid_environment_signal(result: reference_fleet.ProbeResult) -> bool:
    output_text = "\n".join(part for part in [result.stdout, result.stderr, result.error or ""] if part).lower()
    return any(marker in output_text for marker in INVALID_ENVIRONMENT_MARKERS)


def classify_nonzero_result(
    result: reference_fleet.ProbeResult,
    *,
    invalid_environment_code: str,
    failure_code: str,
    invalid_environment_message: str,
    failure_message: str,
) -> tuple[str, dict[str, Any]]:
    if result.missing:
        return (
            "invalid-environment",
            reference_fleet.reason(
                invalid_environment_code,
                "invalid-environment",
                invalid_environment_message,
                missing=True,
            ).to_dict(),
        )
    if result.returncode not in (0, None) and contains_explicit_invalid_environment_signal(result):
        return (
            "invalid-environment",
            reference_fleet.reason(
                invalid_environment_code,
                "invalid-environment",
                invalid_environment_message,
                returncode=result.returncode,
            ).to_dict(),
        )
    return (
        "fail",
        reference_fleet.reason(
            failure_code,
            "fail",
            failure_message,
            returncode=result.returncode,
        ).to_dict(),
    )


def scenario_execution_spec(*, run_root: Path, scenario: Mapping[str, Any]) -> dict[str, Any]:
    prefix = scenario_reason_prefix(scenario)
    label = scenario_label(scenario)
    run_directory_value = scenario.get("runDirectory")
    if not isinstance(run_directory_value, str) or not run_directory_value:
        raise CampaignError(
            f"{prefix}-run-directory-missing",
            "fail",
            f"The retained {label} is missing its run directory.",
        )

    artifacts = scenario.get("artifacts")
    if not isinstance(artifacts, Mapping):
        raise CampaignError(
            f"{prefix}-artifacts-missing",
            "fail",
            f"The retained {label} is missing its artifact map.",
        )
    normalized_artifacts: dict[str, str] = {}
    missing_artifact_keys: list[str] = []
    for key in SCENARIO_ARTIFACT_KEYS:
        value = artifacts.get(key)
        if not isinstance(value, str) or not value:
            missing_artifact_keys.append(key)
            continue
        normalized_artifacts[key] = value
    if missing_artifact_keys:
        raise CampaignError(
            f"{prefix}-artifacts-missing",
            "fail",
            f"The retained {label} is missing one or more required artifact links.",
            missingKeys=missing_artifact_keys,
        )

    def normalize_command(command_value: Any, *, command_name: str) -> list[str]:
        if not isinstance(command_value, Sequence) or isinstance(command_value, (str, bytes)):
            raise CampaignError(
                f"{prefix}-{command_name}-missing",
                "fail",
                f"The retained {label} is missing its {command_name.replace('-', ' ')}.",
            )
        normalized = [str(part) for part in command_value]
        if not normalized:
            raise CampaignError(
                f"{prefix}-{command_name}-missing",
                "fail",
                f"The retained {label} is missing its {command_name.replace('-', ' ')}.",
            )
        return normalized

    return {
        "scenarioId": str(scenario.get("scenarioId") or "scenario"),
        "runDirectory": run_root / run_directory_value,
        "runnerCommand": normalize_command(scenario.get("runnerCommand"), command_name="runner-command"),
        "analysisCommand": normalize_command(scenario.get("analysisCommand"), command_name="analysis-command"),
        "artifacts": normalized_artifacts,
    }


def set_scenario_running(manifest: dict[str, Any], scenario: dict[str, Any]) -> None:
    previous_status = scenario.get("status")
    scenario["status"] = "running"
    scenario["startedAt"] = iso_timestamp()
    scenario["finishedAt"] = None
    scenario["childExitCode"] = None
    scenario["analysisExitCode"] = None
    scenario["analysisStatus"] = None
    scenario["timedOut"] = False
    append_scenario_event(
        scenario,
        "scenario-started",
        fromStatus=previous_status,
        status="running",
        runDirectory=scenario.get("runDirectory"),
        runnerCommand=list(scenario.get("runnerCommand") or []),
        analysisCommand=list(scenario.get("analysisCommand") or []),
    )
    append_campaign_event(
        manifest,
        "scenario-started",
        scenarioId=scenario.get("scenarioId"),
        order=scenario.get("order"),
        runDirectory=scenario.get("runDirectory"),
    )
    if scenario.get("scenarioId") == "direct-guided":
        sync_baseline_execution_from_direct_scenario(manifest)


def finalize_scenario_execution(
    manifest: dict[str, Any],
    *,
    scenario: dict[str, Any],
    status: str,
    reasons: Sequence[dict[str, Any]],
    runner_result: reference_fleet.ProbeResult | None,
    analysis_result: reference_fleet.ProbeResult | None,
    analysis_status: str | None,
) -> None:
    previous_status = scenario.get("status")
    scenario["status"] = status
    scenario["finishedAt"] = iso_timestamp()
    scenario["childExitCode"] = runner_result.returncode if runner_result is not None else None
    scenario["analysisExitCode"] = analysis_result.returncode if analysis_result is not None else None
    scenario["analysisStatus"] = analysis_status
    scenario["timedOut"] = bool(runner_result.timed_out if runner_result is not None else False) or bool(
        analysis_result.timed_out if analysis_result is not None else False
    )
    scenario["reasons"] = dedupe_reason_dicts([*scenario.get("reasons", []), *reasons])
    append_scenario_event(
        scenario,
        "scenario-finished",
        fromStatus=previous_status,
        status=status,
        childExitCode=scenario.get("childExitCode"),
        analysisExitCode=scenario.get("analysisExitCode"),
        analysisStatus=analysis_status,
        timedOut=scenario.get("timedOut"),
        reasonCodes=[
            reason.get("code")
            for reason in scenario.get("reasons", [])
            if isinstance(reason, Mapping) and reason.get("code") is not None
        ],
    )
    if scenario.get("scenarioId") == "direct-guided":
        sync_baseline_execution_from_direct_scenario(manifest)
    gate_tripped = False
    if status == "fail":
        gate_tripped = trip_happy_path_gate(
            manifest,
            scenario_id=str(scenario.get("scenarioId") or "scenario"),
        )
    if gate_tripped:
        append_scenario_event(scenario, "scenario-gate-triggered", gateStatus="red")
        append_campaign_event(
            manifest,
            "happy-path-gate-tripped",
            scenarioId=scenario.get("scenarioId"),
            status=status,
        )
    append_campaign_event(
        manifest,
        "scenario-finished",
        scenarioId=scenario.get("scenarioId"),
        status=status,
        childExitCode=scenario.get("childExitCode"),
        analysisExitCode=scenario.get("analysisExitCode"),
        analysisStatus=analysis_status,
    )


def evaluate_scenario_execution(
    *,
    run_root: Path,
    scenario: Mapping[str, Any],
    spec: Mapping[str, Any],
    runner_result: reference_fleet.ProbeResult,
    analysis_result: reference_fleet.ProbeResult | None,
) -> tuple[str, list[dict[str, Any]], str | None]:
    status = "pass"
    reasons: list[dict[str, Any]] = []
    artifacts = spec["artifacts"]
    summary_path = run_root / artifacts["summary"]
    analysis_json_path = run_root / artifacts["analysisJson"]
    analysis_markdown_path = run_root / artifacts["analysisMarkdown"]
    analysis_status: str | None = None
    prefix = scenario_reason_prefix(scenario)
    label = scenario_label(scenario)

    if runner_result.timed_out:
        status = merge_status(status, "fail")
        reasons.append(
            reference_fleet.reason(
                f"{prefix}-runner-timeout",
                "fail",
                f"The {label} runner timed out before completion.",
            ).to_dict()
        )
    elif runner_result.missing or runner_result.returncode not in (0, None):
        runner_status, runner_reason = classify_nonzero_result(
            runner_result,
            invalid_environment_code=f"{prefix}-runner-invalid-environment",
            failure_code=f"{prefix}-runner-failed",
            invalid_environment_message=f"The {label} runner failed because the local environment is not runnable.",
            failure_message=f"The {label} runner failed before the retained evidence could pass.",
        )
        status = merge_status(status, runner_status)
        reasons.append(runner_reason)

    if not summary_path.exists():
        status = merge_status(status, "fail")
        reasons.append(
            reference_fleet.reason(
                f"{prefix}-summary-missing",
                "fail",
                f"The {label} did not retain summary.json.",
                path=artifacts["summary"],
            ).to_dict()
        )
    else:
        summary_payload, summary_error = load_json_object(summary_path)
        if summary_error is not None:
            status = merge_status(status, "fail")
            reasons.append(
                reference_fleet.reason(
                    f"{prefix}-summary-invalid",
                    "fail",
                    f"The retained {label} summary could not be parsed or validated.",
                    error=summary_error,
                ).to_dict()
            )
        else:
            summary_scenario = str(summary_payload.get("scenario") or "")
            if summary_scenario and summary_scenario != str(scenario.get("scenarioId") or ""):
                status = merge_status(status, "fail")
                reasons.append(
                    reference_fleet.reason(
                        f"{prefix}-summary-contradictory",
                        "fail",
                        f"The retained {label} summary does not match the planned scenario id.",
                        summaryScenario=summary_scenario,
                        scenarioId=scenario.get("scenarioId"),
                    ).to_dict()
                )

    analysis_output_text = "\n".join(
        part for part in [analysis_result.stdout, analysis_result.stderr, analysis_result.error or ""] if part
    ).lower() if analysis_result is not None else ""
    explicit_invalid_environment = any(marker in analysis_output_text for marker in INVALID_ENVIRONMENT_MARKERS)

    if analysis_result is None:
        status = merge_status(status, "fail")
        reasons.append(
            reference_fleet.reason(
                f"{prefix}-analysis-missing",
                "fail",
                f"The {label} did not produce retained analysis artifacts.",
                path=artifacts["analysisJson"],
            ).to_dict()
        )
        return status, dedupe_reason_dicts(reasons), analysis_status

    if analysis_result.timed_out:
        status = merge_status(status, "fail")
        reasons.append(
            reference_fleet.reason(
                f"{prefix}-analysis-timeout",
                "fail",
                f"Retained {label} analysis timed out before analysis artifacts were written.",
            ).to_dict()
        )
    elif analysis_result.missing or analysis_result.returncode not in (0, None):
        analysis_status_from_result, analysis_reason = classify_nonzero_result(
            analysis_result,
            invalid_environment_code=f"{prefix}-analysis-invalid-environment",
            failure_code=f"{prefix}-analysis-failed",
            invalid_environment_message=f"Retained {label} analysis could not run because the local environment is incomplete.",
            failure_message=f"Retained {label} analysis failed before analysis artifacts were written.",
        )
        status = merge_status(status, analysis_status_from_result)
        reasons.append(analysis_reason)

    if not analysis_json_path.exists() or not analysis_markdown_path.exists():
        if explicit_invalid_environment:
            status = merge_status(status, "invalid-environment")
            analysis_status = "invalid-environment"
            reasons.append(
                reference_fleet.reason(
                    f"{prefix}-analysis-invalid-environment",
                    "invalid-environment",
                    f"The retained {label} analysis reported an explicit invalid-environment sentinel.",
                ).to_dict()
            )
        else:
            status = merge_status(status, "fail")
            reasons.append(
                reference_fleet.reason(
                    f"{prefix}-analysis-missing",
                    "fail",
                    f"The {label} did not produce retained analysis artifacts.",
                    jsonPath=artifacts["analysisJson"],
                    markdownPath=artifacts["analysisMarkdown"],
                ).to_dict()
            )
        return status, dedupe_reason_dicts(reasons), analysis_status

    analysis_payload, analysis_error = load_json_object(analysis_json_path)
    if analysis_error is not None:
        status = merge_status(status, "fail")
        reasons.append(
            reference_fleet.reason(
                f"{prefix}-analysis-invalid",
                "fail",
                f"The retained {label} analysis JSON could not be parsed or validated.",
                error=analysis_error,
            ).to_dict()
        )
        return status, dedupe_reason_dicts(reasons), analysis_status

    analysis_status = str(analysis_payload.get("status") or "")
    analysis_output_text = "\n".join(
        part for part in [analysis_result.stdout, analysis_result.stderr, analysis_result.error or ""] if part
    ).lower() if analysis_result is not None else ""
    explicit_invalid_environment = any(marker in analysis_output_text for marker in INVALID_ENVIRONMENT_MARKERS)
    if explicit_invalid_environment:
        status = merge_status(status, "invalid-environment")
        analysis_status = "invalid-environment"
        reasons.append(
            reference_fleet.reason(
                f"{prefix}-analysis-invalid-environment",
                "invalid-environment",
                f"The retained {label} analysis reported an explicit invalid-environment sentinel.",
                analysisStatus=analysis_payload.get("status"),
            ).to_dict()
        )
    elif analysis_status != "pass":
        status = merge_status(status, "fail")
        reasons.append(
            reference_fleet.reason(
                f"{prefix}-analysis-not-pass",
                "fail",
                f"The retained {label} analysis did not report a passing result.",
                analysisStatus=analysis_status,
            ).to_dict()
        )

    return status, dedupe_reason_dicts(reasons), analysis_status


def prepare_scenario_run_directory(run_directory: Path) -> None:
    if run_directory.exists():
        if run_directory.is_dir():
            shutil.rmtree(run_directory)
        else:
            run_directory.unlink()
    run_directory.mkdir(parents=True, exist_ok=True)


def should_run_analysis(summary_path: Path) -> bool:
    return summary_path.exists()


def run_campaign(
    *,
    run_root: Path,
    build_manifest: ManifestBuilder = default_manifest_builder,
    process_runner: ProcessRunner = process_subprocess,
    child_timeout_seconds: float = DEFAULT_CHILD_TIMEOUT_SECONDS,
) -> int:
    if run_root.exists():
        shutil.rmtree(run_root)
    run_root.mkdir(parents=True, exist_ok=True)
    try:
        manifest = build_manifest()
        if not isinstance(manifest, dict):
            raise CampaignError(
                "fleet-manifest-invalid",
                "invalid-environment",
                "Fleet discovery did not return a manifest object.",
            )
    except CampaignError as error:
        manifest = discovery_failure_manifest(run_root, error)
        persist_campaign_state(manifest, run_root=run_root)
        return EXIT_INVALID_ENVIRONMENT
    except Exception as error:  # pragma: no cover - defensive for unexpected discovery failures.
        manifest = discovery_failure_manifest(run_root, error)
        persist_campaign_state(manifest, run_root=run_root)
        return EXIT_INVALID_ENVIRONMENT

    initialize_campaign_state(manifest, run_root=run_root)
    scenarios = ordered_campaign_scenarios(manifest)
    if not scenarios:
        append_campaign_event(manifest, "campaign-finished", status="invalid-environment")
        manifest["campaign"]["status"] = "invalid-environment"
        persist_campaign_state(manifest, run_root=run_root)
        return EXIT_INVALID_ENVIRONMENT

    planned_scenarios = [scenario for scenario in scenarios if scenario.get("status") == "planned"]
    if not planned_scenarios:
        final_status = aggregate_campaign_status(scenarios)
        manifest["campaign"]["status"] = final_status
        append_campaign_event(manifest, "campaign-finished", status=final_status)
        persist_campaign_state(manifest, run_root=run_root)
        return exit_code_for_status(final_status)

    manifest["campaign"]["status"] = "running"
    append_campaign_event(
        manifest,
        "campaign-execution-started",
        scenarioIds=[scenario.get("scenarioId") for scenario in planned_scenarios],
    )
    persist_campaign_state(manifest, run_root=run_root)

    for scenario in planned_scenarios:
        try:
            spec = scenario_execution_spec(run_root=run_root, scenario=scenario)
        except CampaignError as error:
            append_campaign_event(
                manifest,
                "scenario-invalid-plan",
                scenarioId=scenario.get("scenarioId"),
                code=error.code,
            )
            finalize_scenario_execution(
                manifest,
                scenario=scenario,
                status=error.kind,
                reasons=[error.to_reason()],
                runner_result=None,
                analysis_result=None,
                analysis_status=None,
            )
            persist_campaign_state(manifest, run_root=run_root)
            continue

        prepare_scenario_run_directory(spec["runDirectory"])
        set_scenario_running(manifest, scenario)
        persist_campaign_state(manifest, run_root=run_root)

        runner_result = invoke_process_runner(
            process_runner,
            spec["runnerCommand"],
            timeout_seconds=child_timeout_seconds,
        )
        write_command_logs(spec["runDirectory"], prefix="runner", result=runner_result)
        append_scenario_event(
            scenario,
            "scenario-runner-finished",
            returncode=runner_result.returncode,
            timedOut=runner_result.timed_out,
            stdoutPath=spec["artifacts"]["runnerStdout"],
            stderrPath=spec["artifacts"]["runnerStderr"],
        )
        append_campaign_event(
            manifest,
            "scenario-runner-finished",
            scenarioId=scenario.get("scenarioId"),
            returncode=runner_result.returncode,
            timedOut=runner_result.timed_out,
        )

        analysis_result: reference_fleet.ProbeResult | None = None
        if should_run_analysis(spec["runDirectory"] / "summary.json"):
            analysis_result = invoke_process_runner(
                process_runner,
                spec["analysisCommand"],
                timeout_seconds=child_timeout_seconds,
            )
            write_command_logs(spec["runDirectory"], prefix="analysis", result=analysis_result)
            append_scenario_event(
                scenario,
                "scenario-analysis-finished",
                returncode=analysis_result.returncode,
                timedOut=analysis_result.timed_out,
                stdoutPath=spec["artifacts"]["analysisStdout"],
                stderrPath=spec["artifacts"]["analysisStderr"],
            )
            append_campaign_event(
                manifest,
                "scenario-analysis-finished",
                scenarioId=scenario.get("scenarioId"),
                returncode=analysis_result.returncode,
                timedOut=analysis_result.timed_out,
            )

        status, reasons, analysis_status = evaluate_scenario_execution(
            run_root=run_root,
            scenario=scenario,
            spec=spec,
            runner_result=runner_result,
            analysis_result=analysis_result,
        )
        finalize_scenario_execution(
            manifest,
            scenario=scenario,
            status=status,
            reasons=reasons,
            runner_result=runner_result,
            analysis_result=analysis_result,
            analysis_status=analysis_status,
        )
        persist_campaign_state(manifest, run_root=run_root)

    final_status = aggregate_campaign_status(ordered_campaign_scenarios(manifest))
    manifest["campaign"]["status"] = final_status
    append_campaign_event(manifest, "campaign-finished", status=final_status)
    persist_campaign_state(manifest, run_root=run_root)
    return exit_code_for_status(final_status)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    return run_campaign(
        run_root=Path(args.run_root) if args.run_root else default_run_root(),
        child_timeout_seconds=args.child_timeout_seconds,
    )


if __name__ == "__main__":
    raise SystemExit(main())
