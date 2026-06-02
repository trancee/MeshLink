from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from support import SCRIPTS_DIR

if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import render_reference_release_review_report as release_review_report  # noqa: E402


class ReleaseReviewReportTests(unittest.TestCase):
    def write_json(self, path: Path, payload: dict[str, object]) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")

    def build_report_payload(self, run_root: Path) -> dict[str, object]:
        scenario_paths = {
            "pass": "baseline/direct-guided-mixed",
            "fail": "baseline/direct-guided-android-only",
            "skipped": "scenarios/02-relay-constrained",
            "inconclusive": "scenarios/03-direct-route-break",
            "invalid-environment": "scenarios/04-relay-invalid-environment",
        }
        scenarios = []
        for index, verdict in enumerate(
            ["pass", "fail", "skipped", "inconclusive", "invalid-environment"],
            start=1,
        ):
            run_directory = scenario_paths[verdict]
            artifacts = {
                "summary": f"{run_directory}/summary.json",
                "analysisJson": f"{run_directory}/analysis.json",
                "analysisMarkdown": f"{run_directory}/analysis.md",
            }
            scenarios.append(
                {
                    "order": index,
                    "scenarioId": f"scenario-{verdict}",
                    "baseline": f"baseline-{verdict}",
                    "assignmentId": f"assignment-{verdict}",
                    "assignmentShape": verdict,
                    "eligibilityStatus": verdict,
                    "status": verdict,
                    "initialStatus": "planned",
                    "verdict": verdict,
                    "analysisStatus": "pass" if verdict == "pass" else ("fail" if verdict == "fail" else None),
                    "summaryStatus": "passed" if verdict == "pass" else None,
                    "completeEvidence": verdict in {"pass", "fail"},
                    "evidenceIssues": [] if verdict in {"pass", "fail"} else [f"{verdict}-missing"],
                    "artifacts": artifacts,
                    "evidence": {
                        "summary": {
                            "exists": True,
                            "valid": True,
                            "path": str(run_root / artifacts["summary"]),
                        },
                        "analysisJson": {
                            "exists": True,
                            "valid": True,
                            "path": str(run_root / artifacts["analysisJson"]),
                        },
                        "analysisMarkdown": {
                            "exists": True,
                            "valid": True,
                            "path": str(run_root / artifacts["analysisMarkdown"]),
                        },
                    },
                    "summary": {
                        "scenario": f"scenario-{verdict}",
                        "status": verdict,
                    },
                    "analysis": {
                        "scenario_type": "direct" if verdict != "skipped" else "relay",
                        "status": verdict if verdict in {"pass", "fail"} else "inconclusive",
                    },
                    "reasons": [f"reason-{verdict}"],
                }
            )

        return {
            "reportDataVersion": 1,
            "generatedAt": "2026-05-31T10:00:00+00:00",
            "runRoot": str(run_root),
            "sourceFiles": {
                "campaignPlan": "campaign-plan.json",
                "campaignState": "campaign-state.json",
            },
            "campaign": {
                "status": "pass",
                "planStatus": "pass",
                "happyPathGate": {
                    "status": "red",
                    "firstFailScenarioId": "scenario-fail",
                },
            },
            "runClassification": {
                "status": "complete",
                "reasons": ["scenario-inconclusive:analysis-status-unknown"],
            },
            "verdictCounts": {
                "pass": 1,
                "fail": 1,
                "skipped": 1,
                "inconclusive": 1,
                "invalid-environment": 1,
            },
            "gateMath": {
                "status": "red",
                "totalScenarios": 5,
                "runnableScenarios": 3,
                "terminalScenarios": 5,
                "passRate": 1 / 3,
                "failureRate": 1 / 3,
                "inconclusiveRate": 1 / 3,
            },
            "scenarios": scenarios,
        }

    def run_renderer(self, run_root: Path) -> Path:
        argv = ["render_reference_release_review_report.py", "--run-root", str(run_root)]
        with patch.object(sys, "argv", argv):
            exit_code = release_review_report.main()
        self.assertEqual(exit_code, 0)
        return run_root / "release-review-report.html"

    def test_main_renders_a_self_contained_offline_report_from_fixture_data(self) -> None:
        # Arrange
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_root = Path(temporary_directory) / "campaign-run"
            run_root.mkdir(parents=True, exist_ok=True)
            payload = self.build_report_payload(run_root)
            self.write_json(run_root / "report-data.json", payload)

            # Act
            output_path = self.run_renderer(run_root)
            html_text = output_path.read_text(encoding="utf-8")

            # Assert
            self.assertTrue(output_path.exists())
            self.assertIn("<html lang=\"en\">", html_text)
            self.assertIn("<style>", html_text)
            self.assertIn("<script>", html_text)
            self.assertIn("Rendered from retained report-data.json only", html_text)
            self.assertNotIn("https://", html_text)
            self.assertNotIn('src="http', html_text)

    def test_render_report_surfaces_verdict_counts_gate_math_summaries_and_retained_links(self) -> None:
        # Arrange
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_root = Path(temporary_directory) / "campaign-run"
            run_root.mkdir(parents=True, exist_ok=True)
            payload = self.build_report_payload(run_root)
            self.write_json(run_root / "report-data.json", payload)

            # Act
            html_text = release_review_report.render_report(payload)

            # Assert
            self.assertIn("Verdict counts", html_text)
            self.assertIn("<div class=\"metric-value\">1</div>", html_text)
            self.assertIn("Pass rate", html_text)
            self.assertIn("33.3%", html_text)
            self.assertIn("Scenario summaries", html_text)
            self.assertIn("scenario-pass", html_text)
            self.assertIn("scenario-fail", html_text)
            self.assertIn("scenario-skipped", html_text)
            self.assertIn("scenario-inconclusive", html_text)
            self.assertIn("scenario-invalid-environment", html_text)
            self.assertIn("baseline/direct-guided-mixed/summary.json", html_text)
            self.assertIn("baseline/direct-guided-android-only/analysis.json", html_text)
            self.assertIn("scenarios/02-relay-constrained/analysis.md", html_text)
            self.assertIn("Retained evidence", html_text)
            self.assertIn("Evidence issues", html_text)
            self.assertIn("First fail scenario", html_text)
            self.assertIn("scenario-fail", html_text)
            self.assertIn("analysis-status-unknown", html_text)


if __name__ == "__main__":
    unittest.main()
