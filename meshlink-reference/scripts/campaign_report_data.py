#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, Mapping

import reference_fleet

REPORT_DATA_VERSION = 1
DEFAULT_OUTPUT_NAME = "report-data.json"
TERMINAL_VERDICTS = {"pass", "fail", "skipped", "inconclusive", "invalid-environment"}
RETAINED_GATE_THRESHOLDS = {
    "failureCountMaximum": 0,
    "inconclusiveCountMaximum": 0,
    "invalidEnvironmentCountMaximum": 0,
    "passRateMinimum": 1.0,
}

SCRIPTS_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPTS_DIR.parents[1]


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Load retained campaign-plan.json, campaign-state.json, and per-scenario artifacts "
            "to produce a stable report-data.json payload."
        )
    )
    parser.add_argument("--run-root", required=True, help="Retained campaign run root")
    parser.add_argument(
        "--output-json",
        help=f"Optional output path. Defaults to <run-root>/{DEFAULT_OUTPUT_NAME}.",
    )
    parser.add_argument(
        "--print-json",
        action="store_true",
        help="Print the generated report-data JSON to stdout after writing it.",
    )
    return parser.parse_args(argv)


def iso_timestamp() -> str:
    from datetime import datetime, timezone

    return datetime.now(timezone.utc).isoformat()


def display_path(path: Path) -> str:
    try:
        return str(path.relative_to(PROJECT_ROOT))
    except ValueError:
        return str(path)


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


def path_from_run_root(run_root: Path, value: Any) -> Path | None:
    if value is None:
        return None
    candidate = Path(str(value))
    if candidate.is_absolute():
        return candidate
    return run_root / candidate


def relative_path(run_root: Path, value: Any) -> str | None:
    candidate = path_from_run_root(run_root, value)
    if candidate is None:
        return None
    try:
        return str(candidate.relative_to(run_root))
    except ValueError:
        return str(candidate)


def pick_scenario_artifacts(
    plan_scenario: Mapping[str, Any] | None,
    state_scenario: Mapping[str, Any] | None,
) -> dict[str, str]:
    artifacts: Mapping[str, Any] = {}
    if plan_scenario and isinstance(plan_scenario.get("artifacts"), Mapping):
        artifacts = plan_scenario["artifacts"]
    elif state_scenario and isinstance(state_scenario.get("artifacts"), Mapping):
        artifacts = state_scenario["artifacts"]
    return {
        key: str(value)
        for key, value in artifacts.items()
        if value is not None
    }


def scenario_by_id(scenarios: list[dict[str, Any]], scenario_id: str) -> dict[str, Any] | None:
    for scenario in scenarios:
        if scenario.get("scenarioId") == scenario_id:
            return scenario
    return None


def normalize_state_verdict(status: str | None) -> str | None:
    if status is None:
        return None
    normalized = str(status).strip().lower()
    if normalized in {"pass", "passed"}:
        return "pass"
    if normalized in {"fail", "failed"}:
        return "fail"
    if normalized in {"skipped", "skip"}:
        return "skipped"
    if normalized in {"invalid-environment", "invalid_environment"}:
        return "invalid-environment"
    if normalized in {"inconclusive", "unknown", "planned", "running", "partial"}:
        return "inconclusive"
    return None


def scenario_terminal_status(state_scenario: Mapping[str, Any] | None) -> str | None:
    if not state_scenario:
        return None
    verdict = normalize_state_verdict(state_scenario.get("status"))
    if verdict is not None:
        return verdict
    verdict = normalize_state_verdict(state_scenario.get("eligibilityStatus"))
    if verdict is not None:
        return verdict
    verdict = normalize_state_verdict(state_scenario.get("initialStatus"))
    if verdict is not None:
        return verdict
    return None


def inspect_artifact(path: Path) -> dict[str, Any]:
    result: dict[str, Any] = {
        "path": str(path),
        "exists": path.exists(),
    }
    if not path.exists():
        return result
    if path.suffix == ".json":
        payload, error = load_json_object(path)
        if error is not None:
            result["valid"] = False
            result["error"] = error
        else:
            result["valid"] = True
            result["data"] = payload
        return result
    result["valid"] = True
    result["size"] = path.stat().st_size
    return result


def summarize_scenario(
    *,
    run_root: Path,
    plan_scenario: Mapping[str, Any] | None,
    state_scenario: Mapping[str, Any] | None,
) -> dict[str, Any]:
    source_scenario = state_scenario or plan_scenario or {}
    artifacts = pick_scenario_artifacts(plan_scenario, state_scenario)
    summary_path = path_from_run_root(run_root, artifacts.get("summary"))
    analysis_json_path = path_from_run_root(run_root, artifacts.get("analysisJson"))
    analysis_markdown_path = path_from_run_root(run_root, artifacts.get("analysisMarkdown"))

    summary_result = inspect_artifact(summary_path) if summary_path is not None else {"exists": False}
    analysis_json_result = (
        inspect_artifact(analysis_json_path) if analysis_json_path is not None else {"exists": False}
    )
    analysis_markdown_result = (
        inspect_artifact(analysis_markdown_path)
        if analysis_markdown_path is not None
        else {"exists": False}
    )

    summary_payload = summary_result.get("data") if summary_result.get("valid") else None
    analysis_payload = analysis_json_result.get("data") if analysis_json_result.get("valid") else None

    evidence_issues: list[str] = []
    if not summary_result.get("exists"):
        evidence_issues.append("summary-missing")
    elif summary_result.get("valid") is False:
        evidence_issues.append("summary-invalid")

    if not analysis_json_result.get("exists"):
        evidence_issues.append("analysis-json-missing")
    elif analysis_json_result.get("valid") is False:
        evidence_issues.append("analysis-json-invalid")

    if not analysis_markdown_result.get("exists"):
        evidence_issues.append("analysis-markdown-missing")

    state_status = scenario_terminal_status(state_scenario)
    analysis_status = normalize_state_verdict(
        analysis_payload.get("status") if isinstance(analysis_payload, Mapping) else None
    )
    summary_status = normalize_state_verdict(
        summary_payload.get("status") if isinstance(summary_payload, Mapping) else None
    )

    verdict = "inconclusive"
    verdict_reasons: list[str] = []
    failure_reason = summary_payload.get("failureReason") if isinstance(summary_payload, Mapping) else None

    if state_status == "skipped":
        verdict = "skipped"
    elif state_status == "invalid-environment":
        verdict = "invalid-environment"
    elif state_status == "pass":
        verdict = "pass"
    elif state_status in {"pass", "fail"}:
        if state_status == "pass" and evidence_issues:
            verdict = "inconclusive"
            verdict_reasons.append("evidence-incomplete")
        elif analysis_status == "pass" and state_status == "pass":
            verdict = "pass"
        elif analysis_status == "fail":
            verdict = "fail"
        elif analysis_status in {"pass", "fail"}:
            verdict = analysis_status
        else:
            verdict = state_status
            if evidence_issues:
                verdict_reasons.append("evidence-incomplete")
    elif analysis_status == "pass" and not evidence_issues:
        verdict = "pass"
    elif analysis_status == "fail" and not evidence_issues:
        verdict = "fail"
    elif state_status is None and analysis_status is None:
        verdict = "inconclusive"
    else:
        verdict = "inconclusive"

    if "summary-invalid" in evidence_issues:
        verdict = "inconclusive"
        verdict_reasons.append("summary-invalid")
    elif state_status == "fail" and analysis_status is None and failure_reason:
        verdict = "inconclusive"
        verdict_reasons.append("summary-failure-without-analysis")

    if state_status == "pass" and analysis_status == "fail":
        verdict = "inconclusive"
        verdict_reasons.append("state-analysis-contradiction")
    if state_status == "fail" and analysis_status == "pass":
        verdict = "inconclusive"
        verdict_reasons.append("state-analysis-contradiction")
    if evidence_issues and verdict == "pass":
        verdict = "inconclusive"
        verdict_reasons.append("evidence-incomplete")

    if failure_reason:
        verdict_reasons.append(f"summary-failure:{failure_reason}")

    if verdict not in TERMINAL_VERDICTS:
        verdict = "inconclusive"

    details = {
        "scenarioId": source_scenario.get("scenarioId"),
        "order": source_scenario.get("order"),
        "baseline": source_scenario.get("baseline"),
        "assignmentId": source_scenario.get("assignmentId"),
        "assignmentShape": source_scenario.get("assignmentShape"),
        "eligibilityStatus": source_scenario.get("eligibilityStatus"),
        "status": source_scenario.get("status"),
        "initialStatus": source_scenario.get("initialStatus"),
        "verdict": verdict,
        "analysisStatus": analysis_status,
        "summaryStatus": summary_status,
        "completeEvidence": not evidence_issues,
        "evidenceIssues": evidence_issues,
        "artifacts": {
            "summary": relative_path(run_root, artifacts.get("summary")),
            "analysisJson": relative_path(run_root, artifacts.get("analysisJson")),
            "analysisMarkdown": relative_path(run_root, artifacts.get("analysisMarkdown")),
        },
        "evidence": {
            "summary": summary_result,
            "analysisJson": analysis_json_result,
            "analysisMarkdown": analysis_markdown_result,
        },
        "reasons": verdict_reasons,
    }
    if isinstance(summary_payload, Mapping):
        details["summary"] = summary_payload
    if isinstance(analysis_payload, Mapping):
        details["analysis"] = analysis_payload
    return details


def build_report_data(run_root: Path) -> dict[str, Any]:
    plan_path = run_root / "campaign-plan.json"
    state_path = run_root / "campaign-state.json"
    plan_payload, plan_error = load_json_object(plan_path)
    state_payload, state_error = load_json_object(state_path)

    run_reasons: list[str] = []
    if plan_error is not None:
        run_reasons.append(f"campaign-plan:{plan_error}")
    if state_error is not None:
        run_reasons.append(f"campaign-state:{state_error}")

    plan_scenarios = []
    if isinstance(plan_payload, Mapping):
        plan_scenarios = [
            dict(scenario)
            for scenario in plan_payload.get("scenarios", [])
            if isinstance(scenario, Mapping)
        ]
    state_scenarios = []
    if isinstance(state_payload, Mapping):
        state_scenarios = [
            dict(scenario)
            for scenario in state_payload.get("scenarios", [])
            if isinstance(scenario, Mapping)
        ]

    scenario_ids = []
    for scenario in plan_scenarios:
        scenario_id = scenario.get("scenarioId")
        if scenario_id is not None and scenario_id not in scenario_ids:
            scenario_ids.append(scenario_id)
    for scenario in state_scenarios:
        scenario_id = scenario.get("scenarioId")
        if scenario_id is not None and scenario_id not in scenario_ids:
            scenario_ids.append(scenario_id)

    scenarios: list[dict[str, Any]] = []
    gate_first_fail_id = None
    if isinstance(state_payload, Mapping):
        happy_gate = state_payload.get("happyPathGate")
        if isinstance(happy_gate, Mapping):
            gate_first_fail_id = happy_gate.get("firstFailScenarioId")
    gate_has_tripped = gate_first_fail_id is not None
    direct_state_scenario = scenario_by_id(state_scenarios, "direct-guided")
    for scenario_id in scenario_ids:
        plan_scenario = scenario_by_id(plan_scenarios, str(scenario_id))
        state_scenario = scenario_by_id(state_scenarios, str(scenario_id))
        scenario_details = summarize_scenario(
            run_root=run_root,
            plan_scenario=plan_scenario,
            state_scenario=state_scenario,
        )
        if (
            str(scenario_id) == "relay-constrained"
            and scenario_details.get("verdict") == "skipped"
            and isinstance(direct_state_scenario, Mapping)
            and normalize_state_verdict(direct_state_scenario.get("status")) == "pass"
            and any(
                isinstance(reason, Mapping) and reason.get("code") == "relay-constrained-analysis-invalid-environment"
                for reason in (state_scenario.get("reasons", []) if isinstance(state_scenario, Mapping) else [])
            )
        ):
            scenario_details["verdict"] = "invalid-environment"
            scenario_details["status"] = "invalid-environment"
        elif gate_has_tripped and scenario_details.get("status") == "pass" and str(scenario_id) != str(gate_first_fail_id):
            scenario_details["verdict"] = "skipped"
            scenario_details["status"] = "skipped"
        scenarios.append(scenario_details)

    verdict_counts = {
        "pass": 0,
        "fail": 0,
        "skipped": 0,
        "inconclusive": 0,
        "invalid-environment": 0,
    }
    for scenario in scenarios:
        verdict = str(scenario.get("verdict") or "inconclusive")
        if verdict not in verdict_counts:
            verdict = "inconclusive"
        verdict_counts[verdict] += 1

    runnable_count = verdict_counts["pass"] + verdict_counts["fail"] + verdict_counts["inconclusive"]
    terminal_count = verdict_counts["pass"] + verdict_counts["fail"] + verdict_counts["skipped"] + verdict_counts["invalid-environment"]

    if verdict_counts["fail"] > 0 or verdict_counts["invalid-environment"] > 0:
        gate_status = "red"
    elif verdict_counts["inconclusive"] > 0:
        gate_status = "inconclusive"
    else:
        gate_status = "green"

    if plan_error is not None or state_error is not None:
        run_reasons.extend(
            reason for reason in [plan_error, state_error] if reason is not None
        )

    for scenario in scenarios:
        if scenario.get("verdict") != "inconclusive":
            continue
        for issue in scenario.get("evidenceIssues", []):
            run_reasons.append(f"{scenario.get('scenarioId')}:{issue}")

    run_classification = {
        "status": "complete" if not run_reasons else "incomplete",
        "reasons": run_reasons,
    }

    campaign = {
        "status": state_payload.get("status") if isinstance(state_payload, Mapping) else None,
        "planStatus": plan_payload.get("status") if isinstance(plan_payload, Mapping) else None,
        "happyPathGate": dict(state_payload.get("happyPathGate", {})) if isinstance(state_payload, Mapping) else {},
    }

    campaign_provenance_path = None
    if isinstance(plan_payload, Mapping):
        campaign_provenance_path = plan_payload.get("campaignProvenancePath")
    if campaign_provenance_path is None and isinstance(state_payload, Mapping):
        campaign_provenance_path = state_payload.get("campaignProvenancePath")

    report_data = {
        "reportDataVersion": REPORT_DATA_VERSION,
        "generatedAt": iso_timestamp(),
        "runRoot": str(run_root),
        "sourceFiles": {
            "campaignPlan": display_path(plan_path),
            "campaignState": display_path(state_path),
            "campaignProvenance": display_path(run_root / str(campaign_provenance_path)) if campaign_provenance_path else None,
        },
        "campaign": campaign,
        "runClassification": run_classification,
        "verdictCounts": verdict_counts,
        "gateMath": {
            "status": gate_status,
            "thresholds": dict(RETAINED_GATE_THRESHOLDS),
            "totalScenarios": len(scenarios),
            "runnableScenarios": runnable_count,
            "terminalScenarios": terminal_count,
            "passRate": None if runnable_count == 0 else verdict_counts["pass"] / runnable_count,
            "failureRate": None if runnable_count == 0 else verdict_counts["fail"] / runnable_count,
            "inconclusiveRate": None if runnable_count == 0 else verdict_counts["inconclusive"] / runnable_count,
        },
        "scenarios": scenarios,
    }
    return report_data


def write_report_data(path: Path, payload: Mapping[str, Any]) -> Path:
    return reference_fleet.write_manifest(path, payload)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    run_root = Path(args.run_root)
    output_json = Path(args.output_json) if args.output_json else run_root / DEFAULT_OUTPUT_NAME
    report_data = build_report_data(run_root)
    write_report_data(output_json, report_data)
    if args.print_json:
        print(json.dumps(report_data, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
