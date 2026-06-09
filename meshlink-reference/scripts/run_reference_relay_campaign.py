#!/usr/bin/env python3

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

import json

import render_reference_release_review_report

SCRIPT_DIR = Path(__file__).resolve().parent
RELAY_RUNNER_SCRIPT = SCRIPT_DIR / "run_headless_reference_relay_proof.py"
ANALYSIS_SCRIPT = SCRIPT_DIR / "analyze_reference_physical_run.py"


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Run the retained MeshLink relay-focused reference campaign by delegating to the existing "
            "relay proof runner and analyzer."
        )
    )
    parser.add_argument("--ios-device", required=True, help="Physical iPhone UDID for the sender")
    parser.add_argument(
        "--relay-android-serial",
        required=True,
        help="ADB serial of the Android relay device",
    )
    parser.add_argument(
        "--passive-android-serial",
        required=True,
        help="ADB serial of the Android passive recipient",
    )
    parser.add_argument(
        "--app-id",
        help="Shared MeshLink app ID. Defaults to the relay runner's timestamped prefix.",
    )
    parser.add_argument(
        "--run-dir",
        required=True,
        help="Directory for retained logs and artifacts. Required so the analyzer can run against the same scenario directory.",
    )
    parser.add_argument(
        "--android-ready-seconds",
        type=float,
        default=None,
        help="How long to wait after launching the Android peers before starting the iPhone sender.",
    )
    parser.add_argument(
        "--capture-timeout-seconds",
        type=float,
        default=None,
        help="Hard timeout for sender, relay, and passive completion capture.",
    )
    parser.add_argument(
        "--post-result-idle-seconds",
        type=float,
        default=None,
        help="How long to keep the iPhone console open after the sender proof marker.",
    )
    parser.add_argument("--skip-android-install", action="store_true")
    parser.add_argument("--skip-ios-build", action="store_true")
    parser.add_argument("--skip-ios-install", action="store_true")
    return parser.parse_args(argv)


def relay_runner_command(args: argparse.Namespace) -> list[str]:
    command = [
        sys.executable,
        str(RELAY_RUNNER_SCRIPT),
        "--ios-device",
        args.ios_device,
        "--relay-android-serial",
        args.relay_android_serial,
        "--passive-android-serial",
        args.passive_android_serial,
    ]
    if args.app_id:
        command.extend(["--app-id", args.app_id])
    if args.run_dir:
        command.extend(["--run-dir", args.run_dir])
    if args.android_ready_seconds is not None:
        command.extend(["--android-ready-seconds", str(args.android_ready_seconds)])
    if args.capture_timeout_seconds is not None:
        command.extend(["--capture-timeout-seconds", str(args.capture_timeout_seconds)])
    if args.post_result_idle_seconds is not None:
        command.extend(["--post-result-idle-seconds", str(args.post_result_idle_seconds)])
    if args.skip_android_install:
        command.append("--skip-android-install")
    if args.skip_ios_build:
        command.append("--skip-ios-build")
    if args.skip_ios_install:
        command.append("--skip-ios-install")
    return command


def analysis_command(run_dir: str) -> list[str]:
    return [sys.executable, str(ANALYSIS_SCRIPT), "--run-dir", run_dir]


def build_relay_report_data(run_dir: Path) -> dict[str, object]:
    summary_path = run_dir / "summary.json"
    analysis_json_path = run_dir / "analysis.json"
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    analysis = json.loads(analysis_json_path.read_text(encoding="utf-8"))
    status = str(analysis.get("status") or summary.get("status") or "inconclusive")
    verdict = "pass" if status == "pass" else "fail"
    return {
        "reportDataVersion": 1,
        "generatedAt": "relay-campaign",
        "runRoot": str(run_dir),
        "sourceFiles": {
            "campaignPlan": None,
            "campaignState": None,
            "campaignProvenance": None,
        },
        "campaign": {
            "status": verdict,
            "planStatus": verdict,
            "happyPathGate": {"status": "green" if verdict == "pass" else "red"},
        },
        "runClassification": {"status": "complete", "reasons": []},
        "verdictCounts": {"pass": 1 if verdict == "pass" else 0, "fail": 1 if verdict == "fail" else 0, "skipped": 0, "inconclusive": 0, "invalid-environment": 0},
        "gateMath": {"status": "green" if verdict == "pass" else "red", "thresholds": {"failureCountMaximum": 0, "inconclusiveCountMaximum": 0, "invalidEnvironmentCountMaximum": 0, "passRateMinimum": 1.0}, "totalScenarios": 1, "runnableScenarios": 1, "terminalScenarios": 1, "passRate": 1.0 if verdict == "pass" else 0.0, "failureRate": 0.0 if verdict == "pass" else 1.0, "inconclusiveRate": 0.0},
        "scenarios": [
            {
                "scenarioId": "relay-constrained",
                "order": 1,
                "baseline": "relay-constrained",
                "assignmentId": "relay-constrained",
                "assignmentShape": "mixed",
                "eligibilityStatus": "runnable",
                "status": verdict,
                "initialStatus": "planned" if verdict == "pass" else "fail",
                "verdict": verdict,
                "analysisStatus": status,
                "summaryStatus": summary.get("status"),
                "completeEvidence": verdict == "pass",
                "evidenceIssues": [] if verdict == "pass" else ["relay-failed"],
                "artifacts": {
                    "summary": "summary.json",
                    "analysisJson": "analysis.json",
                    "analysisMarkdown": "analysis.md",
                },
                "evidence": {"summary": {"path": str(summary_path), "exists": True, "valid": True, "data": summary}, "analysisJson": {"path": str(analysis_json_path), "exists": True, "valid": True, "data": analysis}, "analysisMarkdown": {"path": str(run_dir / "analysis.md"), "exists": True, "valid": True, "size": (run_dir / "analysis.md").stat().st_size if (run_dir / "analysis.md").exists() else 0}},
                "reasons": [],
            }
        ],
    }


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    runner_command = relay_runner_command(args)
    print("==> Running relay proof campaign:", " ".join(runner_command))
    runner_result = subprocess.run(runner_command, check=False)
    if runner_result.returncode != 0:
        return runner_result.returncode

    analyzer_result = subprocess.run(analysis_command(args.run_dir), check=False)
    if analyzer_result.returncode != 0:
        return analyzer_result.returncode

    run_root = Path(args.run_dir).parent
    report_data = build_relay_report_data(Path(args.run_dir))
    report_data_path = Path(args.run_dir) / "report-data.json"
    report_data_path.write_text(json.dumps(report_data, indent=2), encoding="utf-8")
    html_path = render_reference_release_review_report.render_release_review_report(
        Path(args.run_dir),
        report_data_path=report_data_path,
        output_html_path=Path(args.run_dir) / "release-review-report.html",
    )
    print("==> Relay campaign report:", html_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
