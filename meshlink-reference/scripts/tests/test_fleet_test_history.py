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

import fleet_test_history as fleet_history  # noqa: E402


class FleetTestHistoryReportTests(unittest.TestCase):
    def write_json(self, path: Path, payload: object) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")

    def run_cli(self, *args: str) -> int:
        argv = ["fleet_test_history.py", *args]
        with patch.object(sys, "argv", argv):
            return fleet_history.main()

    def test_main_appends_a_history_entry_and_renders_a_concise_html_report(self) -> None:
        # Arrange
        with tempfile.TemporaryDirectory() as temporary_directory:
            temp_dir = Path(temporary_directory)
            history_json = temp_dir / "history.json"
            output_html = temp_dir / "index.html"
            entry_json = temp_dir / "entry.json"
            self.write_json(
                entry_json,
                {
                    "date": "2026-06-02",
                    "title": "M001 release-review campaign",
                    "tested": "Android-only fleet on the release-review campaign path",
                    "campaign_result": "red",
                    "selection": "selected",
                    "gate": "red",
                    "report_gate": "red",
                    "comparison": "first recorded run",
                    "devices": [
                        {"alias": "android-cph2359-8d1fef", "platform": "android", "role": "sender", "available": True},
                        {"alias": "android-e940-2849-00-d19cfd", "platform": "android", "role": "passive", "available": True},
                    ],
                    "results": [
                        {"scenario": "direct-guided", "status": "fail", "eligibility": "runnable"},
                        {"scenario": "relay-constrained", "status": "invalid-environment", "eligibility": "invalid-environment"},
                    ],
                },
            )

            # Act
            exit_code = self.run_cli(
                "--history-json",
                str(history_json),
                "--output-html",
                str(output_html),
                "--append-entry-json",
                str(entry_json),
            )

            # Assert
            self.assertEqual(exit_code, 0)
            self.assertTrue(history_json.exists())
            self.assertTrue(output_html.exists())
            history = json.loads(history_json.read_text(encoding="utf-8"))
            html_text = output_html.read_text(encoding="utf-8")
            self.assertEqual(len(history), 1)
            self.assertIn("Fleet test history", html_text)
            self.assertIn("M001 release-review campaign", html_text)
            self.assertIn("android-cph2359-8d1fef", html_text)
            self.assertIn("android-e940-2849-00-d19cfd", html_text)
            self.assertIn("direct-guided", html_text)
            self.assertIn("relay-constrained", html_text)
            self.assertIn("red", html_text)
            self.assertIn("first recorded run", html_text)
            self.assertIn("rendered from the repo-visible JSON ledger only", html_text)
            self.assertNotIn("https://", html_text)

    def test_render_history_uses_previous_run_as_comparison_context(self) -> None:
        # Arrange
        history = [
            {
                "date": "2026-06-01",
                "title": "Seed run",
                "tested": "Android-only seed fleet",
                "campaign_result": "pass",
                "selection": "selected",
                "gate": "green",
                "report_gate": "green",
                "devices": [
                    {"alias": "android-a", "platform": "android", "role": "sender", "available": True},
                    {"alias": "android-b", "platform": "android", "role": "passive", "available": True},
                ],
                "results": [{"scenario": "direct-guided", "status": "pass", "eligibility": "runnable"}],
            },
            {
                "date": "2026-06-02",
                "title": "Repeat run",
                "tested": "Android-only repeat fleet",
                "campaign_result": "red",
                "selection": "selected",
                "gate": "red",
                "report_gate": "red",
                "devices": [
                    {"alias": "android-a", "platform": "android", "role": "sender", "available": True},
                    {"alias": "android-b", "platform": "android", "role": "passive", "available": True},
                    {"alias": "android-c", "platform": "android", "role": "spare", "available": True},
                ],
                "results": [
                    {"scenario": "direct-guided", "status": "fail", "eligibility": "runnable"},
                    {"scenario": "relay-constrained", "status": "skipped", "eligibility": "skipped"},
                ],
            },
        ]

        # Act
        html_text = fleet_history.render_history(history)

        # Assert
        self.assertIn("Repeat run", html_text)
        self.assertIn("Seed run", html_text)
        self.assertIn("compared with the previous run", html_text)
        self.assertIn("changed the fleet shape from 2 device(s) to 3 device(s)", html_text)
        self.assertIn("android-c", html_text)
        self.assertIn("Result mix", html_text)
        self.assertIn("rendered from the repo-visible JSON ledger only", html_text)


if __name__ == "__main__":
    unittest.main()
