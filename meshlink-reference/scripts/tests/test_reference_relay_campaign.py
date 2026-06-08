from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from support import SCRIPTS_DIR

if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import run_reference_relay_campaign as relay_campaign  # noqa: E402


class RelayCampaignTests(unittest.TestCase):
    def test_main_rejects_missing_run_dir(self) -> None:
        with self.assertRaises(SystemExit):
            relay_campaign.main(
                [
                    "--ios-device",
                    "00008110-000E196E0E92401E",
                    "--relay-android-serial",
                    "adb-relay",
                    "--passive-android-serial",
                    "adb-passive",
                ]
            )

    def test_main_delegates_to_relay_runner_and_analysis_with_relay_args(self) -> None:
        # Arrange
        run_calls: list[list[str]] = []

        def fake_run(
            command: list[str],
            *,
            check: bool = True,
            capture_output: bool = False,
            text: bool = True,
            timeout: float | None = None,
        ) -> subprocess.CompletedProcess[str]:
            del check, capture_output, text, timeout
            if command[0] == sys.executable and command[1].endswith("run_headless_reference_relay_proof.py"):
                run_dir_index = command.index("--run-dir") + 1
                relay_run_dir = Path(command[run_dir_index])
                relay_run_dir.mkdir(parents=True, exist_ok=True)
                (relay_run_dir / "summary.json").write_text(
                    json.dumps({"scenario": "relay-constrained", "status": "passed"}, indent=2),
                    encoding="utf-8",
                )
                (relay_run_dir / "analysis.json").write_text(
                    json.dumps({"scenario_name": "relay-constrained", "status": "pass"}, indent=2),
                    encoding="utf-8",
                )
                (relay_run_dir / "analysis.md").write_text("# relay\n", encoding="utf-8")
            run_calls.append(list(command))
            if command[0] == sys.executable and command[1].endswith("run_headless_reference_relay_proof.py"):
                return subprocess.CompletedProcess(command, 0, stdout="relay ok\n", stderr="")
            if command[0] == sys.executable and command[1].endswith("analyze_reference_physical_run.py"):
                return subprocess.CompletedProcess(command, 0, stdout="analysis ok\n", stderr="")
            raise AssertionError(f"Unexpected command: {command!r}")

        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory) / "relay-campaign"

            # Act
            with patch.object(relay_campaign.subprocess, "run", side_effect=fake_run):
                exit_code = relay_campaign.main(
                    [
                        "--ios-device",
                        "00008110-000E196E0E92401E",
                        "--relay-android-serial",
                        "adb-relay",
                        "--passive-android-serial",
                        "adb-passive",
                        "--app-id",
                        "demo.meshlink.reference.relay.test",
                        "--run-dir",
                        str(run_dir),
                        "--android-ready-seconds",
                        "9",
                        "--capture-timeout-seconds",
                        "77",
                        "--post-result-idle-seconds",
                        "3",
                        "--skip-android-install",
                        "--skip-ios-build",
                        "--skip-ios-install",
                    ]
                )

            # Assert
            self.assertEqual(exit_code, 0)
            self.assertEqual(len(run_calls), 2)
            relay_command = run_calls[0]
            analysis_command = run_calls[1]
            self.assertEqual(Path(relay_command[0]).name, Path(sys.executable).name)
            self.assertEqual(Path(relay_command[1]).name, "run_headless_reference_relay_proof.py")
            self.assertIn("--ios-device", relay_command)
            self.assertIn("00008110-000E196E0E92401E", relay_command)
            self.assertIn("--relay-android-serial", relay_command)
            self.assertIn("adb-relay", relay_command)
            self.assertIn("--passive-android-serial", relay_command)
            self.assertIn("adb-passive", relay_command)
            self.assertIn("--app-id", relay_command)
            self.assertIn("demo.meshlink.reference.relay.test", relay_command)
            self.assertIn("--run-dir", relay_command)
            self.assertIn(str(run_dir), relay_command)
            self.assertIn("--android-ready-seconds", relay_command)
            self.assertIn("9.0", relay_command)
            self.assertIn("--capture-timeout-seconds", relay_command)
            self.assertIn("77.0", relay_command)
            self.assertIn("--post-result-idle-seconds", relay_command)
            self.assertIn("3.0", relay_command)
            self.assertIn("--skip-android-install", relay_command)
            self.assertIn("--skip-ios-build", relay_command)
            self.assertIn("--skip-ios-install", relay_command)
            self.assertEqual(Path(analysis_command[1]).name, "analyze_reference_physical_run.py")
            self.assertIn("--run-dir", analysis_command)
            self.assertIn(str(run_dir), analysis_command)
            report_data = json.loads((run_dir / "report-data.json").read_text(encoding="utf-8"))
            self.assertEqual(report_data["scenarios"][0]["scenarioId"], "relay-constrained")
            self.assertEqual(report_data["scenarios"][0]["verdict"], "pass")
            html_text = (run_dir / "release-review-report.html").read_text(encoding="utf-8")
            self.assertIn("relay-constrained", html_text)


if __name__ == "__main__":
    unittest.main()
