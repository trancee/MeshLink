#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Mapping, Sequence

import reference_fleet

DEFAULT_APP_ID_PREFIX = "demo.meshlink.reference.release"
DEFAULT_CHILD_TIMEOUT_SECONDS = 30 * 60
EXIT_PASS = 0
EXIT_FAIL = 1
EXIT_SKIPPED = 2
EXIT_INVALID_ENVIRONMENT = 3
INVALID_ENVIRONMENT_HINTS = (
    "adb",
    "build failed",
    "could not find",
    "development_team",
    "devicectl",
    "install failed",
    "not granted",
    "not ready",
    "provision",
    "runtime permissions",
    "xcodebuild",
)

SCRIPTS_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPTS_DIR.parents[1]
RUNNER_SCRIPTS = {
    "direct-guided-mixed": SCRIPTS_DIR / "run_headless_reference_live_proof.py",
    "direct-guided-android-only": SCRIPTS_DIR / "run_headless_reference_android_direct_proof.py",
}
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
            "Discover the available MeshLink reference-app fleet, retain fleet-manifest.json and "
            "campaign-plan.json, and execute the first eligible S01 direct baseline without manual "
            "serial or UDID flags."
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


def initialize_campaign_state(manifest: dict[str, Any], *, run_root: Path) -> None:
    selection = manifest.get("selection") if isinstance(manifest.get("selection"), Mapping) else {}
    selection_status = str(selection.get("status") or "invalid-environment")
    selected_assignment = manifest.get("selectedAssignment")
    baseline_status = "planned" if selection_status == "selected" else selection_status
    manifest["campaign"] = {
        "status": baseline_status,
        "runRoot": str(run_root),
        "fleetManifestPath": "fleet-manifest.json",
        "campaignPlanPath": "campaign-plan.json",
        "baselineExecution": {
            "status": baseline_status,
            "assignmentId": selected_assignment.get("assignmentId") if isinstance(selected_assignment, Mapping) else None,
            "baseline": selected_assignment.get("baseline") if isinstance(selected_assignment, Mapping) else None,
            "shape": selected_assignment.get("shape") if isinstance(selected_assignment, Mapping) else None,
            "participants": dict(selected_assignment.get("participants", {})) if isinstance(selected_assignment, Mapping) else {},
            "runnerScript": None,
            "analysisScript": display_path(ANALYSIS_SCRIPT),
            "runnerCommand": None,
            "analysisCommand": None,
            "runDirectory": None,
            "appId": None,
            "startedAt": None,
            "finishedAt": None,
            "childExitCode": None,
            "analysisExitCode": None,
            "analysisStatus": None,
            "timedOut": False,
            "reasons": list(selection.get("reasons", [])),
            "artifacts": {
                "summary": None,
                "analysisJson": None,
                "analysisMarkdown": None,
                "runnerStdout": None,
                "runnerStderr": None,
                "analysisStdout": None,
                "analysisStderr": None,
            },
        },
    }
    append_campaign_event(manifest, "campaign-initialized", selectionStatus=selection_status)


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


def campaign_paths(run_root: Path) -> tuple[Path, Path]:
    return run_root / "fleet-manifest.json", run_root / "campaign-plan.json"


def persist_campaign_state(manifest: dict[str, Any], *, run_root: Path) -> None:
    manifest_path, campaign_plan_path = campaign_paths(run_root)
    write_json_document(manifest_path, manifest)
    write_json_document(campaign_plan_path, build_campaign_plan(manifest))


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

    selected_baseline = None
    if baseline_execution.get("assignmentId"):
        selected_baseline = {
            "assignmentId": baseline_execution.get("assignmentId"),
            "baseline": baseline_execution.get("baseline"),
            "shape": baseline_execution.get("shape"),
            "status": baseline_execution.get("status"),
            "appId": baseline_execution.get("appId"),
            "runnerScript": baseline_execution.get("runnerScript"),
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
        "planVersion": 1,
        "generatedAt": manifest.get("generatedAt"),
        "status": campaign.get("status"),
        "runRoot": campaign.get("runRoot"),
        "fleetManifestPath": campaign.get("fleetManifestPath"),
        "selection": dict(manifest.get("selection", {})),
        "candidateAssignments": candidate_assignments,
        "selectedBaseline": selected_baseline,
    }


def resolve_participant_details(
    manifest: Mapping[str, Any],
    participants: Mapping[str, Any],
) -> dict[str, dict[str, Any] | None]:
    resolved: dict[str, dict[str, Any] | None] = {}
    for role, alias in participants.items():
        device = reference_fleet.find_device_by_alias(manifest, str(alias) if alias is not None else None)
        if device is None:
            resolved[str(role)] = None
            continue
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
    alias = participants.get(role)
    device = reference_fleet.find_device_by_alias(manifest, str(alias) if alias is not None else None)
    if device is None:
        raise CampaignError(
            "selected-assignment-device-missing",
            "invalid-environment",
            "Selected baseline assignment points at a device alias that is not present in the manifest.",
            assignmentId=selected_assignment.get("assignmentId"),
            role=role,
            alias=alias,
        )
    if device.get("platform") != expected_platform:
        raise CampaignError(
            "selected-assignment-platform-mismatch",
            "invalid-environment",
            "Selected baseline assignment points at a device with the wrong platform for the requested role.",
            assignmentId=selected_assignment.get("assignmentId"),
            role=role,
            alias=alias,
            expectedPlatform=expected_platform,
            actualPlatform=device.get("platform"),
        )
    return device


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
    baseline_execution = manifest["campaign"]["baselineExecution"]
    baseline_execution["assignmentId"] = spec.get("assignmentId")
    baseline_execution["baseline"] = spec.get("baseline")
    baseline_execution["shape"] = spec.get("shape")
    baseline_execution["participants"] = dict(spec.get("participants", {}))
    baseline_execution["runnerScript"] = spec.get("runnerScript")
    baseline_execution["analysisScript"] = spec.get("analysisScript")
    baseline_execution["runnerCommand"] = display_command(spec["runnerCommand"])
    baseline_execution["analysisCommand"] = display_command(spec["analysisCommand"])
    baseline_execution["runDirectory"] = relative_path(spec["runDirectory"], run_root=Path(manifest["campaign"]["runRoot"]))
    baseline_execution["appId"] = spec.get("appId")
    baseline_execution["startedAt"] = iso_timestamp()
    baseline_execution["status"] = "running"
    baseline_execution["artifacts"] = dict(spec.get("artifacts", {}))
    manifest["campaign"]["status"] = "running"
    append_campaign_event(
        manifest,
        "baseline-runner-selected",
        assignmentId=spec.get("assignmentId"),
        runnerScript=spec.get("runnerScript"),
        runDirectory=baseline_execution["runDirectory"],
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


def classify_nonzero_result(
    result: reference_fleet.ProbeResult,
    *,
    invalid_environment_code: str,
    failure_code: str,
    invalid_environment_message: str,
    failure_message: str,
) -> tuple[str, dict[str, Any]]:
    output_text = "\n".join(part for part in [result.stdout, result.stderr, result.error or ""] if part).lower()
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
    if result.returncode not in (0, None) and any(hint in output_text for hint in INVALID_ENVIRONMENT_HINTS):
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


def evaluate_baseline_execution(
    *,
    run_root: Path,
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

    if runner_result.timed_out:
        status = merge_status(status, "fail")
        reasons.append(
            reference_fleet.reason(
                "baseline-runner-timeout",
                "fail",
                "The selected baseline runner timed out before completion.",
            ).to_dict()
        )
    elif runner_result.missing or runner_result.returncode not in (0, None):
        runner_status, runner_reason = classify_nonzero_result(
            runner_result,
            invalid_environment_code="baseline-runner-invalid-environment",
            failure_code="baseline-runner-failed",
            invalid_environment_message="The selected baseline runner failed because the local environment is not runnable.",
            failure_message="The selected baseline runner failed before the retained direct baseline could pass.",
        )
        status = merge_status(status, runner_status)
        reasons.append(runner_reason)

    if not summary_path.exists():
        status = merge_status(status, "fail")
        reasons.append(
            reference_fleet.reason(
                "baseline-summary-missing",
                "fail",
                "The selected baseline run did not retain summary.json.",
                path=artifacts["summary"],
            ).to_dict()
        )

    if analysis_result is None:
        status = merge_status(status, "fail")
        reasons.append(
            reference_fleet.reason(
                "baseline-analysis-missing",
                "fail",
                "The selected baseline run did not produce retained analysis artifacts.",
                path=artifacts["analysisJson"],
            ).to_dict()
        )
        return status, dedupe_reason_dicts(reasons), analysis_status

    if analysis_result.timed_out:
        status = merge_status(status, "fail")
        reasons.append(
            reference_fleet.reason(
                "baseline-analysis-timeout",
                "fail",
                "Retained run analysis timed out before analysis artifacts were written.",
            ).to_dict()
        )
    elif analysis_result.missing or analysis_result.returncode not in (0, None):
        analysis_status_from_result, analysis_reason = classify_nonzero_result(
            analysis_result,
            invalid_environment_code="baseline-analysis-invalid-environment",
            failure_code="baseline-analysis-failed",
            invalid_environment_message="Retained run analysis could not run because the local environment is incomplete.",
            failure_message="Retained run analysis failed before analysis artifacts were written.",
        )
        status = merge_status(status, analysis_status_from_result)
        reasons.append(analysis_reason)

    if not analysis_json_path.exists() or not analysis_markdown_path.exists():
        status = merge_status(status, "fail")
        reasons.append(
            reference_fleet.reason(
                "baseline-analysis-missing",
                "fail",
                "The selected baseline run did not produce retained analysis artifacts.",
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
                "baseline-analysis-invalid",
                "fail",
                "The retained analysis JSON could not be parsed or validated.",
                error=analysis_error,
            ).to_dict()
        )
        return status, dedupe_reason_dicts(reasons), analysis_status

    analysis_status = str(analysis_payload.get("status") or "")
    if analysis_status != "pass":
        status = merge_status(status, "fail")
        reasons.append(
            reference_fleet.reason(
                "baseline-analysis-not-pass",
                "fail",
                "The retained analysis did not report a passing baseline.",
                analysisStatus=analysis_status,
            ).to_dict()
        )

    return status, dedupe_reason_dicts(reasons), analysis_status


def should_run_analysis(run_directory: Path) -> bool:
    return run_directory.exists() and any(run_directory.iterdir())


def run_campaign(
    *,
    run_root: Path,
    build_manifest: ManifestBuilder = default_manifest_builder,
    process_runner: ProcessRunner = process_subprocess,
    child_timeout_seconds: float = DEFAULT_CHILD_TIMEOUT_SECONDS,
) -> int:
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
    persist_campaign_state(manifest, run_root=run_root)

    selection = manifest.get("selection") if isinstance(manifest.get("selection"), Mapping) else {}
    selection_status = str(selection.get("status") or "invalid-environment")
    if selection_status != "selected":
        append_campaign_event(manifest, "campaign-finished", status=selection_status)
        persist_campaign_state(manifest, run_root=run_root)
        return exit_code_for_status(selection_status)

    try:
        spec = resolve_runner_spec(manifest, run_root=run_root)
    except CampaignError as error:
        baseline_execution = manifest["campaign"]["baselineExecution"]
        baseline_execution["status"] = "invalid-environment"
        baseline_execution["finishedAt"] = iso_timestamp()
        baseline_execution["reasons"] = dedupe_reason_dicts(
            [*baseline_execution.get("reasons", []), error.to_reason()]
        )
        manifest["campaign"]["status"] = "invalid-environment"
        append_campaign_event(manifest, "campaign-invalid-manifest", code=error.code)
        persist_campaign_state(manifest, run_root=run_root)
        return EXIT_INVALID_ENVIRONMENT

    apply_runner_spec(manifest, spec)
    persist_campaign_state(manifest, run_root=run_root)

    runner_result = process_runner(spec["runnerCommand"], timeout_seconds=child_timeout_seconds)
    write_command_logs(spec["runDirectory"], prefix="runner", result=runner_result)
    append_campaign_event(
        manifest,
        "baseline-runner-finished",
        assignmentId=spec.get("assignmentId"),
        returncode=runner_result.returncode,
        timedOut=runner_result.timed_out,
    )

    analysis_result: reference_fleet.ProbeResult | None = None
    if should_run_analysis(spec["runDirectory"]):
        analysis_result = process_runner(spec["analysisCommand"], timeout_seconds=child_timeout_seconds)
        write_command_logs(spec["runDirectory"], prefix="analysis", result=analysis_result)
        append_campaign_event(
            manifest,
            "baseline-analysis-finished",
            assignmentId=spec.get("assignmentId"),
            returncode=analysis_result.returncode,
            timedOut=analysis_result.timed_out,
        )

    status, reasons, analysis_status = evaluate_baseline_execution(
        run_root=run_root,
        spec=spec,
        runner_result=runner_result,
        analysis_result=analysis_result,
    )
    baseline_execution = manifest["campaign"]["baselineExecution"]
    baseline_execution["status"] = status
    baseline_execution["finishedAt"] = iso_timestamp()
    baseline_execution["childExitCode"] = runner_result.returncode
    baseline_execution["analysisExitCode"] = analysis_result.returncode if analysis_result is not None else None
    baseline_execution["analysisStatus"] = analysis_status
    baseline_execution["timedOut"] = bool(runner_result.timed_out) or bool(
        analysis_result.timed_out if analysis_result is not None else False
    )
    baseline_execution["reasons"] = dedupe_reason_dicts(
        [*baseline_execution.get("reasons", []), *reasons]
    )
    manifest["campaign"]["status"] = status
    append_campaign_event(manifest, "campaign-finished", status=status)
    persist_campaign_state(manifest, run_root=run_root)
    return exit_code_for_status(status)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    return run_campaign(
        run_root=Path(args.run_root) if args.run_root else default_run_root(),
        child_timeout_seconds=args.child_timeout_seconds,
    )


if __name__ == "__main__":
    raise SystemExit(main())
