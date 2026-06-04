from __future__ import annotations

import json
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path
from unittest.mock import patch

from support import SCRIPTS_DIR

if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import campaign_report_data as report_data  # noqa: E402


class CampaignReportDataTests(unittest.TestCase):
    def write_json(self, path: Path, payload: dict[str, object]) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")

    def write_text(self, path: Path, content: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(textwrap.dedent(content).lstrip(), encoding="utf-8")

    def run_cli(self, run_root: Path) -> tuple[int, dict[str, object]]:
        argv = [
            "campaign_report_data.py",
            "--run-root",
            str(run_root),
        ]
        with patch.object(sys, "argv", argv):
            exit_code = report_data.main()
        payload = json.loads((run_root / "report-data.json").read_text(encoding="utf-8"))
        return exit_code, payload

    def seed_scenario_artifacts(
        self,
        run_root: Path,
        *,
        scenario_id: str,
        run_directory: str,
        summary_payload: dict[str, object] | None = None,
        analysis_payload: dict[str, object] | None = None,
        analysis_markdown: str | None = None,
    ) -> dict[str, str]:
        artifacts = {
            "summary": f"{run_directory}/summary.json",
            "analysisJson": f"{run_directory}/analysis.json",
            "analysisMarkdown": f"{run_directory}/analysis.md",
            "runnerStdout": f"{run_directory}/runner.stdout.log",
            "runnerStderr": f"{run_directory}/runner.stderr.log",
            "analysisStdout": f"{run_directory}/analysis.stdout.log",
            "analysisStderr": f"{run_directory}/analysis.stderr.log",
        }
        if summary_payload is not None:
            self.write_json(run_root / artifacts["summary"], summary_payload)
        if analysis_payload is not None:
            self.write_json(run_root / artifacts["analysisJson"], analysis_payload)
        if analysis_markdown is not None:
            self.write_text(run_root / artifacts["analysisMarkdown"], analysis_markdown)
        return artifacts

    def seed_campaign_files(
        self,
        run_root: Path,
        *,
        scenarios: list[dict[str, object]],
        status: str,
        happy_path_gate_status: str = "green",
    ) -> None:
        self.write_json(
            run_root / "campaign-plan.json",
            {
                "planVersion": 2,
                "scenarioCatalogVersion": 1,
                "generatedAt": "2026-05-31T10:00:00+00:00",
                "status": status,
                "runRoot": str(run_root),
                "campaignStatePath": "campaign-state.json",
                "scenarios": scenarios,
            },
        )
        self.write_json(
            run_root / "campaign-state.json",
            {
                "stateVersion": 2,
                "scenarioCatalogVersion": 1,
                "generatedAt": "2026-05-31T10:00:00+00:00",
                "updatedAt": "2026-05-31T10:00:05+00:00",
                "status": status,
                "runRoot": str(run_root),
                "campaignPlanPath": "campaign-plan.json",
                "happyPathGate": {
                    "status": happy_path_gate_status,
                    "firstFailScenarioId": None,
                    "triggeredAt": None,
                    "updatedAt": "2026-05-31T10:00:05+00:00",
                },
                "scenarios": scenarios,
            },
        )

    def test_main_writes_report_data_for_a_live_retained_run(self) -> None:
        # Arrange
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_root = Path(temporary_directory) / "campaign-run"
            run_root.mkdir(parents=True, exist_ok=True)

            direct_artifacts = self.seed_scenario_artifacts(
                run_root,
                scenario_id="direct-guided",
                run_directory="baseline/direct-guided-mixed",
                summary_payload={
                    "scenario": "direct-guided",
                    "status": "passed",
                    "export_relative_path": "exports/session-redacted.json",
                },
                analysis_payload={
                    "scenario_type": "direct",
                    "status": "pass",
                },
                analysis_markdown="# direct guided\n",
            )
            self.seed_scenario_artifacts(
                run_root,
                scenario_id="relay-constrained",
                run_directory="scenarios/02-relay-constrained",
            )
            scenarios = [
                {
                    "order": 1,
                    "scenarioId": "direct-guided",
                    "baseline": "direct-guided",
                    "assignmentId": "direct-guided-mixed",
                    "assignmentShape": "mixed",
                    "initialStatus": "planned",
                    "eligibilityStatus": "runnable",
                    "status": "pass",
                    "runDirectory": "baseline/direct-guided-mixed",
                    "artifacts": direct_artifacts,
                },
                {
                    "order": 2,
                    "scenarioId": "relay-constrained",
                    "baseline": "relay-constrained",
                    "assignmentId": "relay-constrained",
                    "assignmentShape": "mixed",
                    "initialStatus": "skipped",
                    "eligibilityStatus": "skipped",
                    "status": "skipped",
                    "runDirectory": "scenarios/02-relay-constrained",
                    "artifacts": {
                        "summary": "scenarios/02-relay-constrained/summary.json",
                        "analysisJson": "scenarios/02-relay-constrained/analysis.json",
                        "analysisMarkdown": "scenarios/02-relay-constrained/analysis.md",
                        "runnerStdout": "scenarios/02-relay-constrained/runner.stdout.log",
                        "runnerStderr": "scenarios/02-relay-constrained/runner.stderr.log",
                        "analysisStdout": "scenarios/02-relay-constrained/analysis.stdout.log",
                        "analysisStderr": "scenarios/02-relay-constrained/analysis.stderr.log",
                    },
                },
            ]
            self.seed_campaign_files(run_root, scenarios=scenarios, status="pass")

            # Act
            exit_code, payload = self.run_cli(run_root)

            # Assert
            self.assertEqual(exit_code, 0)
            self.assertEqual(payload["reportDataVersion"], 1)
            self.assertEqual(payload["runClassification"]["status"], "complete")
            self.assertEqual(payload["verdictCounts"], {"pass": 1, "fail": 0, "skipped": 1, "inconclusive": 0, "invalid-environment": 0})
            self.assertEqual(payload["gateMath"]["status"], "green")
            self.assertEqual(payload["scenarios"][0]["verdict"], "pass")
            self.assertEqual(payload["scenarios"][0]["analysisStatus"], "pass")
            self.assertEqual(payload["scenarios"][1]["verdict"], "skipped")
            self.assertTrue((run_root / "report-data.json").exists())

    def test_main_marks_partially_completed_runs_incomplete(self) -> None:
        # Arrange
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_root = Path(temporary_directory) / "campaign-run"
            run_root.mkdir(parents=True, exist_ok=True)

            partial_artifacts = self.seed_scenario_artifacts(
                run_root,
                scenario_id="direct-guided",
                run_directory="baseline/direct-guided-android-only",
                summary_payload={
                    "scenario": "direct-guided",
                    "status": "passed",
                },
                analysis_payload=None,
                analysis_markdown=None,
            )
            scenarios = [
                {
                    "order": 1,
                    "scenarioId": "direct-guided",
                    "baseline": "direct-guided",
                    "assignmentId": "direct-guided-android-only",
                    "assignmentShape": "android-only",
                    "initialStatus": "running",
                    "eligibilityStatus": "runnable",
                    "status": "running",
                    "runDirectory": "baseline/direct-guided-android-only",
                    "artifacts": partial_artifacts,
                },
                {
                    "order": 2,
                    "scenarioId": "relay-constrained",
                    "baseline": "relay-constrained",
                    "assignmentId": "relay-constrained",
                    "assignmentShape": "mixed",
                    "initialStatus": "planned",
                    "eligibilityStatus": "runnable",
                    "status": "planned",
                    "runDirectory": "scenarios/02-relay-constrained",
                    "artifacts": {
                        "summary": "scenarios/02-relay-constrained/summary.json",
                        "analysisJson": "scenarios/02-relay-constrained/analysis.json",
                        "analysisMarkdown": "scenarios/02-relay-constrained/analysis.md",
                        "runnerStdout": "scenarios/02-relay-constrained/runner.stdout.log",
                        "runnerStderr": "scenarios/02-relay-constrained/runner.stderr.log",
                        "analysisStdout": "scenarios/02-relay-constrained/analysis.stdout.log",
                        "analysisStderr": "scenarios/02-relay-constrained/analysis.stderr.log",
                    },
                },
            ]
            self.seed_campaign_files(run_root, scenarios=scenarios, status="running")

            # Act
            exit_code, payload = self.run_cli(run_root)

            # Assert
            self.assertEqual(exit_code, 0)
            self.assertEqual(payload["runClassification"]["status"], "incomplete")
            self.assertEqual(payload["verdictCounts"]["inconclusive"], 2)
            self.assertEqual(payload["gateMath"]["status"], "inconclusive")
            self.assertIn("direct-guided:analysis-json-missing", payload["runClassification"]["reasons"])
            self.assertEqual(payload["scenarios"][0]["verdict"], "inconclusive")
            self.assertEqual(payload["scenarios"][1]["verdict"], "inconclusive")

    def test_main_handles_malformed_json_without_guessing_verdicts(self) -> None:
        # Arrange
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_root = Path(temporary_directory) / "campaign-run"
            run_root.mkdir(parents=True, exist_ok=True)

            artifacts = {
                "summary": "baseline/direct-guided-mixed/summary.json",
                "analysisJson": "baseline/direct-guided-mixed/analysis.json",
                "analysisMarkdown": "baseline/direct-guided-mixed/analysis.md",
                "runnerStdout": "baseline/direct-guided-mixed/runner.stdout.log",
                "runnerStderr": "baseline/direct-guided-mixed/runner.stderr.log",
                "analysisStdout": "baseline/direct-guided-mixed/analysis.stdout.log",
                "analysisStderr": "baseline/direct-guided-mixed/analysis.stderr.log",
            }
            self.write_text(run_root / artifacts["summary"], "{" )
            self.write_json(
                run_root / artifacts["analysisJson"],
                {
                    "scenario_type": "direct",
                    "status": "pass",
                },
            )
            self.write_text(run_root / artifacts["analysisMarkdown"], "# direct guided\n")
            scenarios = [
                {
                    "order": 1,
                    "scenarioId": "direct-guided",
                    "baseline": "direct-guided",
                    "assignmentId": "direct-guided-mixed",
                    "assignmentShape": "mixed",
                    "initialStatus": "pass",
                    "eligibilityStatus": "runnable",
                    "status": "pass",
                    "runDirectory": "baseline/direct-guided-mixed",
                    "artifacts": artifacts,
                }
            ]
            self.seed_campaign_files(run_root, scenarios=scenarios, status="pass")

            # Act
            exit_code, payload = self.run_cli(run_root)

            # Assert
            self.assertEqual(exit_code, 0)
            self.assertEqual(payload["runClassification"]["status"], "incomplete")
            self.assertEqual(payload["verdictCounts"]["inconclusive"], 1)
            self.assertIn("direct-guided:summary-invalid", payload["runClassification"]["reasons"])
            self.assertEqual(payload["scenarios"][0]["verdict"], "inconclusive")
            self.assertIn("could not be parsed", payload["scenarios"][0]["evidence"]["summary"]["error"])

    def test_main_projects_thresholds_for_supported_campaign_outcomes(self) -> None:
        # Arrange + Act + Assert
        case_definitions = [
            {
                "name": "pass-and-skipped",
                "build": self._build_pass_and_skipped_case,
                "expected_thresholds": {
                    "failureCountMaximum": 0,
                    "inconclusiveCountMaximum": 0,
                    "invalidEnvironmentCountMaximum": 0,
                    "passRateMinimum": 1.0,
                },
                "assertions": lambda payload: (
                    self.assertEqual(payload["runClassification"]["status"], "complete"),
                    self.assertEqual(payload["verdictCounts"], {"pass": 1, "fail": 0, "skipped": 1, "inconclusive": 0, "invalid-environment": 0}),
                    self.assertEqual(payload["gateMath"]["status"], "green"),
                    self.assertEqual(payload["scenarios"][0]["verdict"], "pass"),
                    self.assertEqual(payload["scenarios"][1]["verdict"], "skipped"),
                ),
            },
            {
                "name": "partial-inconclusive",
                "build": self._build_partial_case,
                "expected_thresholds": {
                    "failureCountMaximum": 0,
                    "inconclusiveCountMaximum": 0,
                    "invalidEnvironmentCountMaximum": 0,
                    "passRateMinimum": 1.0,
                },
                "assertions": lambda payload: (
                    self.assertEqual(payload["runClassification"]["status"], "incomplete"),
                    self.assertEqual(payload["verdictCounts"]["inconclusive"], 2),
                    self.assertEqual(payload["gateMath"]["status"], "inconclusive"),
                    self.assertEqual(payload["scenarios"][0]["verdict"], "inconclusive"),
                    self.assertEqual(payload["scenarios"][1]["verdict"], "inconclusive"),
                ),
            },
            {
                "name": "invalid-environment",
                "build": self._build_invalid_environment_case,
                "expected_thresholds": {
                    "failureCountMaximum": 0,
                    "inconclusiveCountMaximum": 0,
                    "invalidEnvironmentCountMaximum": 0,
                    "passRateMinimum": 1.0,
                },
                "assertions": lambda payload: (
                    self.assertEqual(payload["verdictCounts"]["invalid-environment"], 1),
                    self.assertEqual(payload["scenarios"][0]["verdict"], "invalid-environment"),
                    self.assertEqual(payload["gateMath"]["status"], "red"),
                ),
            },
            {
                "name": "malformed-input",
                "build": self._build_malformed_summary_case,
                "expected_thresholds": {
                    "failureCountMaximum": 0,
                    "inconclusiveCountMaximum": 0,
                    "invalidEnvironmentCountMaximum": 0,
                    "passRateMinimum": 1.0,
                },
                "assertions": lambda payload: (
                    self.assertEqual(payload["runClassification"]["status"], "incomplete"),
                    self.assertEqual(payload["verdictCounts"]["inconclusive"], 1),
                    self.assertEqual(payload["scenarios"][0]["verdict"], "inconclusive"),
                ),
            },
        ]

        for case in case_definitions:
            with self.subTest(case=case["name"]):
                with tempfile.TemporaryDirectory() as temporary_directory:
                    run_root = Path(temporary_directory) / "campaign-run"
                    run_root.mkdir(parents=True, exist_ok=True)
                    case["build"](run_root)
                    exit_code, payload = self.run_cli(run_root)

                    self.assertEqual(exit_code, 0)
                    self.assertEqual(payload["gateMath"]["thresholds"], case["expected_thresholds"])
                    case["assertions"](payload)

    def _build_pass_and_skipped_case(self, run_root: Path) -> None:
        direct_artifacts = self.seed_scenario_artifacts(
            run_root,
            scenario_id="direct-guided",
            run_directory="baseline/direct-guided-mixed",
            summary_payload={
                "scenario": "direct-guided",
                "status": "passed",
                "export_relative_path": "exports/session-redacted.json",
            },
            analysis_payload={
                "scenario_type": "direct",
                "status": "pass",
            },
            analysis_markdown="# direct guided\n",
        )
        self.seed_scenario_artifacts(
            run_root,
            scenario_id="relay-constrained",
            run_directory="scenarios/02-relay-constrained",
        )
        scenarios = [
            {
                "order": 1,
                "scenarioId": "direct-guided",
                "baseline": "direct-guided",
                "assignmentId": "direct-guided-mixed",
                "assignmentShape": "mixed",
                "initialStatus": "planned",
                "eligibilityStatus": "runnable",
                "status": "pass",
                "runDirectory": "baseline/direct-guided-mixed",
                "artifacts": direct_artifacts,
            },
            {
                "order": 2,
                "scenarioId": "relay-constrained",
                "baseline": "relay-constrained",
                "assignmentId": "relay-constrained",
                "assignmentShape": "mixed",
                "initialStatus": "skipped",
                "eligibilityStatus": "skipped",
                "status": "skipped",
                "runDirectory": "scenarios/02-relay-constrained",
                "artifacts": {
                    "summary": "scenarios/02-relay-constrained/summary.json",
                    "analysisJson": "scenarios/02-relay-constrained/analysis.json",
                    "analysisMarkdown": "scenarios/02-relay-constrained/analysis.md",
                    "runnerStdout": "scenarios/02-relay-constrained/runner.stdout.log",
                    "runnerStderr": "scenarios/02-relay-constrained/runner.stderr.log",
                    "analysisStdout": "scenarios/02-relay-constrained/analysis.stdout.log",
                    "analysisStderr": "scenarios/02-relay-constrained/analysis.stderr.log",
                },
            },
        ]
        self.seed_campaign_files(run_root, scenarios=scenarios, status="pass")

    def _build_partial_case(self, run_root: Path) -> None:
        partial_artifacts = self.seed_scenario_artifacts(
            run_root,
            scenario_id="direct-guided",
            run_directory="baseline/direct-guided-android-only",
            summary_payload={
                "scenario": "direct-guided",
                "status": "passed",
            },
            analysis_payload=None,
            analysis_markdown=None,
        )
        scenarios = [
            {
                "order": 1,
                "scenarioId": "direct-guided",
                "baseline": "direct-guided",
                "assignmentId": "direct-guided-android-only",
                "assignmentShape": "android-only",
                "initialStatus": "running",
                "eligibilityStatus": "runnable",
                "status": "running",
                "runDirectory": "baseline/direct-guided-android-only",
                "artifacts": partial_artifacts,
            },
            {
                "order": 2,
                "scenarioId": "relay-constrained",
                "baseline": "relay-constrained",
                "assignmentId": "relay-constrained",
                "assignmentShape": "mixed",
                "initialStatus": "planned",
                "eligibilityStatus": "runnable",
                "status": "planned",
                "runDirectory": "scenarios/02-relay-constrained",
                "artifacts": {
                    "summary": "scenarios/02-relay-constrained/summary.json",
                    "analysisJson": "scenarios/02-relay-constrained/analysis.json",
                    "analysisMarkdown": "scenarios/02-relay-constrained/analysis.md",
                    "runnerStdout": "scenarios/02-relay-constrained/runner.stdout.log",
                    "runnerStderr": "scenarios/02-relay-constrained/runner.stderr.log",
                    "analysisStdout": "scenarios/02-relay-constrained/analysis.stdout.log",
                    "analysisStderr": "scenarios/02-relay-constrained/analysis.stderr.log",
                },
            },
        ]
        self.seed_campaign_files(run_root, scenarios=scenarios, status="running")

    def _build_invalid_environment_case(self, run_root: Path) -> None:
        artifacts = {
            "summary": "baseline/direct-guided-mixed/summary.json",
            "analysisJson": "baseline/direct-guided-mixed/analysis.json",
            "analysisMarkdown": "baseline/direct-guided-mixed/analysis.md",
            "runnerStdout": "baseline/direct-guided-mixed/runner.stdout.log",
            "runnerStderr": "baseline/direct-guided-mixed/runner.stderr.log",
            "analysisStdout": "baseline/direct-guided-mixed/analysis.stdout.log",
            "analysisStderr": "baseline/direct-guided-mixed/analysis.stderr.log",
        }
        scenarios = [
            {
                "order": 1,
                "scenarioId": "direct-guided",
                "baseline": "direct-guided",
                "assignmentId": "direct-guided-mixed",
                "assignmentShape": "mixed",
                "initialStatus": "invalid-environment",
                "eligibilityStatus": "invalid-environment",
                "status": "invalid-environment",
                "runDirectory": "baseline/direct-guided-mixed",
                "artifacts": artifacts,
            }
        ]
        self.seed_campaign_files(run_root, scenarios=scenarios, status="invalid-environment")

    def _build_malformed_summary_case(self, run_root: Path) -> None:
        artifacts = {
            "summary": "baseline/direct-guided-mixed/summary.json",
            "analysisJson": "baseline/direct-guided-mixed/analysis.json",
            "analysisMarkdown": "baseline/direct-guided-mixed/analysis.md",
            "runnerStdout": "baseline/direct-guided-mixed/runner.stdout.log",
            "runnerStderr": "baseline/direct-guided-mixed/runner.stderr.log",
            "analysisStdout": "baseline/direct-guided-mixed/analysis.stdout.log",
            "analysisStderr": "baseline/direct-guided-mixed/analysis.stderr.log",
        }
        self.write_text(run_root / artifacts["summary"], "{")
        self.write_json(
            run_root / artifacts["analysisJson"],
            {
                "scenario_type": "direct",
                "status": "pass",
            },
        )
        self.write_text(run_root / artifacts["analysisMarkdown"], "# direct guided\n")
        scenarios = [
            {
                "order": 1,
                "scenarioId": "direct-guided",
                "baseline": "direct-guided",
                "assignmentId": "direct-guided-mixed",
                "assignmentShape": "mixed",
                "initialStatus": "pass",
                "eligibilityStatus": "runnable",
                "status": "pass",
                "runDirectory": "baseline/direct-guided-mixed",
                "artifacts": artifacts,
            }
        ]
        self.seed_campaign_files(run_root, scenarios=scenarios, status="pass")

    def test_main_marks_missing_analysis_artifacts_as_inconclusive(self) -> None:
        # Arrange
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_root = Path(temporary_directory) / "campaign-run"
            run_root.mkdir(parents=True, exist_ok=True)

            artifacts = {
                "summary": "baseline/direct-guided-mixed/summary.json",
                "analysisJson": "baseline/direct-guided-mixed/analysis.json",
                "analysisMarkdown": "baseline/direct-guided-mixed/analysis.md",
                "runnerStdout": "baseline/direct-guided-mixed/runner.stdout.log",
                "runnerStderr": "baseline/direct-guided-mixed/runner.stderr.log",
                "analysisStdout": "baseline/direct-guided-mixed/analysis.stdout.log",
                "analysisStderr": "baseline/direct-guided-mixed/analysis.stderr.log",
            }
            self.write_json(
                run_root / artifacts["summary"],
                {
                    "scenario": "direct-guided",
                    "status": "passed",
                    "export_relative_path": "exports/session-redacted.json",
                },
            )
            scenarios = [
                {
                    "order": 1,
                    "scenarioId": "direct-guided",
                    "baseline": "direct-guided",
                    "assignmentId": "direct-guided-mixed",
                    "assignmentShape": "mixed",
                    "initialStatus": "pass",
                    "eligibilityStatus": "runnable",
                    "status": "pass",
                    "runDirectory": "baseline/direct-guided-mixed",
                    "artifacts": artifacts,
                }
            ]
            self.seed_campaign_files(run_root, scenarios=scenarios, status="pass")

            # Act
            exit_code, payload = self.run_cli(run_root)

            # Assert
            self.assertEqual(exit_code, 0)
            self.assertEqual(payload["runClassification"]["status"], "incomplete")
            self.assertEqual(payload["verdictCounts"]["inconclusive"], 1)
            self.assertEqual(payload["scenarios"][0]["verdict"], "inconclusive")
            self.assertIn("analysis-json-missing", payload["scenarios"][0]["evidenceIssues"])
            self.assertIn("direct-guided:analysis-markdown-missing", payload["runClassification"]["reasons"])


if __name__ == "__main__":
    unittest.main()
