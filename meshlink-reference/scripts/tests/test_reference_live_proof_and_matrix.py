from __future__ import annotations

import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

from support import SCRIPTS_DIR

if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import run_headless_reference_live_proof as live_proof  # noqa: E402
import run_headless_reference_physical_matrix as physical_matrix  # noqa: E402


class ReferenceLiveProofScriptTests(unittest.TestCase):
    def test_parse_args_accepts_the_new_resilience_scenario_names(self) -> None:
        # Arrange
        argv = [
            "run_headless_reference_live_proof.py",
            "--android-serial",
            "android-serial-123",
            "--ios-device",
            "ios-device-123",
            "--scenario",
            "direct-route-break-recovery",
        ]

        # Act
        with patch.object(sys, "argv", argv):
            parsed_args = live_proof.parse_args()

        # Assert
        self.assertEqual(parsed_args.scenario, "direct-route-break-recovery")
        self.assertIn("direct-restart-recovery", live_proof.DIRECT_PHYSICAL_SCENARIOS)
        self.assertIn("direct-isolation-recovery", live_proof.DIRECT_PHYSICAL_SCENARIOS)
        self.assertIn("direct-route-break-recovery", live_proof.DIRECT_PHYSICAL_SCENARIOS)

    def test_main_writes_startup_timing_section_to_summary(self) -> None:
        # Arrange
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory) / "live-proof"
            argv = [
                "run_headless_reference_live_proof.py",
                "--android-serial",
                "android-serial-123",
                "--ios-device",
                "ios-device-123",
                "--run-dir",
                str(run_dir),
                "--skip-ios-build",
                "--skip-ios-install",
                "--skip-android-install",
                "--skip-android-completion-wait",
                "--android-ready-seconds",
                "0",
                "--capture-timeout-seconds",
                "0.1",
                "--post-result-idle-seconds",
                "0",
            ]

            class FakeProcess:
                def stop(self) -> None:
                    return None

            def fake_start_android_app(*args, **kwargs):
                del args, kwargs
                log_path = run_dir / "android_logcat.log"
                log_path.parent.mkdir(parents=True, exist_ok=True)
                log_path.write_text(
                    "REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE\n",
                    encoding="utf-8",
                )
                return FakeProcess()

            def fake_start_ios_app_via_devicectl(*args, **kwargs):
                del args, kwargs
                (run_dir / "iphone_console.log").parent.mkdir(parents=True, exist_ok=True)
                return FakeProcess()

            with (
                patch.object(sys, "argv", argv),
                patch.object(live_proof, "ensure_android_device_ready"),
                patch.object(live_proof, "verify_android_runtime_permissions"),
                patch.object(live_proof, "install_android_app"),
                patch.object(live_proof, "latest_built_app", return_value=Path("/tmp/fake.app")),
                patch.object(live_proof, "install_ios_app"),
                patch.object(live_proof, "build_ios_app", return_value=Path("/tmp/fake.app")),
                patch.object(live_proof, "start_android_app", side_effect=fake_start_android_app),
                patch.object(live_proof, "start_ios_app_via_devicectl", side_effect=fake_start_ios_app_via_devicectl),
                patch.object(live_proof, "wait_for_android_completion", return_value=("android complete", "exports/session-redacted.json")),
                patch.object(live_proof, "wait_for_ios_sender_result"),
                patch.object(live_proof, "verify_ios_sender_log", return_value="ios complete"),
                patch.object(live_proof, "force_stop_reference_app"),
                patch.object(live_proof, "force_stop_ios_app"),
                patch.object(live_proof.time, "sleep", return_value=None),
            ):
                exit_code = live_proof.main()

            # Assert
            self.assertEqual(exit_code, 0)
            summary = json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))
            self.assertIn("startupTiming", summary)
            self.assertIn("install", summary["startupTiming"])
            self.assertIn("permissions", summary["startupTiming"])
            self.assertIn("launch", summary["startupTiming"])
            self.assertEqual(summary["startupTiming"]["launch"]["postResultIdleSeconds"], 0)

    def test_main_force_stops_android_and_ios_apps_even_when_the_ios_sender_verification_fails(self) -> None:
        # Arrange
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory) / "live-proof-crash"
            argv = [
                "run_headless_reference_live_proof.py",
                "--android-serial",
                "android-serial-123",
                "--ios-device",
                "ios-device-123",
                "--run-dir",
                str(run_dir),
                "--skip-ios-build",
                "--skip-ios-install",
                "--skip-android-install",
                "--android-ready-seconds",
                "0",
                "--capture-timeout-seconds",
                "0.1",
                "--post-result-idle-seconds",
                "0",
            ]

            class FakeProcess:
                def stop(self) -> None:
                    return None

            def fake_start_android_app(*args, **kwargs):
                del args, kwargs
                log_path = run_dir / "android_logcat.log"
                log_path.parent.mkdir(parents=True, exist_ok=True)
                log_path.write_text(
                    "REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE\n",
                    encoding="utf-8",
                )
                return FakeProcess()

            def fake_start_ios_app_via_devicectl(*args, **kwargs):
                del args, kwargs
                (run_dir / "iphone_console.log").parent.mkdir(parents=True, exist_ok=True)
                return FakeProcess()

            with (
                patch.object(sys, "argv", argv),
                patch.object(live_proof, "ensure_android_device_ready"),
                patch.object(live_proof, "verify_android_runtime_permissions"),
                patch.object(live_proof, "install_android_app"),
                patch.object(live_proof, "latest_built_app", return_value=Path("/tmp/fake.app")),
                patch.object(live_proof, "install_ios_app"),
                patch.object(live_proof, "build_ios_app", return_value=Path("/tmp/fake.app")),
                patch.object(live_proof, "start_android_app", side_effect=fake_start_android_app),
                patch.object(live_proof, "start_ios_app_via_devicectl", side_effect=fake_start_ios_app_via_devicectl),
                patch.object(live_proof, "wait_for_ios_sender_result"),
                # Simulates the iPhone sender app crashing/failing before completing the scenario.
                patch.object(live_proof, "verify_ios_sender_log", side_effect=SystemExit("iPhone sender crashed mid-scenario")),
                patch.object(live_proof, "force_stop_reference_app") as mock_force_stop_android,
                patch.object(live_proof, "force_stop_ios_app") as mock_force_stop_ios,
                patch.object(live_proof.time, "sleep", return_value=None),
            ):
                # Act
                with self.assertRaises(SystemExit):
                    live_proof.main()

            # Assert
            mock_force_stop_android.assert_called_once_with("android-serial-123")
            mock_force_stop_ios.assert_called_once_with("ios-device-123")

    def test_install_android_app_disables_play_protect_before_deploy(self) -> None:
        # Arrange
        run_commands: list[list[str]] = []
        install_commands: list[list[str]] = []

        def fake_run(command: list[str], **kwargs):
            del kwargs
            run_commands.append(list(command))
            return __import__("subprocess").CompletedProcess(command, 0, stdout="", stderr="")

        def fake_subprocess_run(command: list[str], **kwargs):
            del kwargs
            install_commands.append(list(command))
            if command[:2] == ["adb", "-s"] and command[3:8] == ["shell", "settings", "put", "global", "package_verifier_user_consent"]:
                return __import__("subprocess").CompletedProcess(command, 1, stdout="", stderr="SecurityException: WRITE_SECURE_SETTINGS denied")
            return __import__("subprocess").CompletedProcess(command, 0, stdout="", stderr="")

        with (
            patch.object(live_proof, "android_apk_path", return_value=None),
            patch.object(live_proof, "launcher_source_fingerprint", return_value="fingerprint"),
            patch.object(live_proof, "android_sdk_int", return_value=33),
            patch.object(live_proof, "run", side_effect=fake_run),
            patch.object(live_proof.subprocess, "run", side_effect=fake_subprocess_run),
            patch.object(live_proof.time, "sleep", return_value=None),
        ):
            # Act
            live_proof.install_android_app("nokia-x20", Path("/tmp/run"))

        # Assert
        self.assertIn(
            [
                "adb",
                "-s",
                "nokia-x20",
                "shell",
                "pm",
                "grant",
                "com.android.shell",
                "android.permission.WRITE_SECURE_SETTINGS",
            ],
            run_commands,
        )
        self.assertIn(
            [
                "adb",
                "-s",
                "nokia-x20",
                "shell",
                "settings",
                "put",
                "global",
                "package_verifier_user_consent",
                "-1",
            ],
            run_commands,
        )
        self.assertLess(
            run_commands.index(
                [
                    "adb",
                    "-s",
                    "nokia-x20",
                    "shell",
                    "pm",
                    "grant",
                    "com.android.shell",
                    "android.permission.WRITE_SECURE_SETTINGS",
                ]
            ),
            run_commands.index(
                [
                    "adb",
                    "-s",
                    "nokia-x20",
                    "shell",
                    "settings",
                    "put",
                    "global",
                    "package_verifier_user_consent",
                    "-1",
                ]
            ),
        )
        self.assertIn(
            ["adb", "-s", "nokia-x20", "shell", "pm", "grant", "ch.trancee.meshlink.reference", "android.permission.BLUETOOTH_SCAN"],
            run_commands,
        )
        self.assertIn(
            ["adb", "-s", "nokia-x20", "shell", "pm", "grant", "ch.trancee.meshlink.reference", "android.permission.BLUETOOTH_CONNECT"],
            run_commands,
        )
        self.assertIn(
            ["adb", "-s", "nokia-x20", "shell", "pm", "grant", "ch.trancee.meshlink.reference", "android.permission.BLUETOOTH_ADVERTISE"],
            run_commands,
        )
        self.assertTrue(install_commands)
        self.assertTrue(any(command[:2] == ["./gradlew", ":meshlink-reference:installDebug"] for command in install_commands))

    def test_install_android_app_skips_secure_settings_grant_when_already_present(self) -> None:
        # Arrange
        run_commands: list[list[str]] = []
        install_commands: list[list[str]] = []

        def fake_run(command: list[str], **kwargs):
            del kwargs
            run_commands.append(list(command))
            if command[:2] == ["adb", "-s"] and command[3:6] == ["shell", "dumpsys", "package"] and command[6] == "com.android.shell":
                return __import__("subprocess").CompletedProcess(
                    command,
                    0,
                    stdout="      android.permission.WRITE_SECURE_SETTINGS: granted=true\n",
                    stderr="",
                )
            return __import__("subprocess").CompletedProcess(command, 0, stdout="", stderr="")

        def fake_subprocess_run(command: list[str], **kwargs):
            del kwargs
            install_commands.append(list(command))
            return __import__("subprocess").CompletedProcess(command, 0, stdout="", stderr="")

        with (
            patch.object(live_proof, "android_apk_path", return_value=None),
            patch.object(live_proof, "launcher_source_fingerprint", return_value="fingerprint"),
            patch.object(live_proof, "android_sdk_int", return_value=33),
            patch.object(live_proof, "run", side_effect=fake_run),
            patch.object(live_proof.subprocess, "run", side_effect=fake_subprocess_run),
            patch.object(live_proof.time, "sleep", return_value=None),
        ):
            # Act
            live_proof.install_android_app("nokia-x20", Path("/tmp/run"))

        # Assert
        self.assertIn(
            ["adb", "-s", "nokia-x20", "shell", "dumpsys", "package", "com.android.shell"],
            run_commands,
        )
        self.assertNotIn(
            [
                "adb",
                "-s",
                "nokia-x20",
                "shell",
                "pm",
                "grant",
                "com.android.shell",
                "android.permission.WRITE_SECURE_SETTINGS",
            ],
            run_commands,
        )
        self.assertIn(
            [
                "adb",
                "-s",
                "nokia-x20",
                "shell",
                "settings",
                "put",
                "global",
                "package_verifier_user_consent",
                "-1",
            ],
            run_commands,
        )
        self.assertTrue(install_commands)
        self.assertTrue(any(command[:2] == ["./gradlew", ":meshlink-reference:installDebug"] for command in install_commands))


class ReferencePhysicalMatrixScriptTests(unittest.TestCase):
    def test_help_lists_the_new_resilience_scenarios(self) -> None:
        # Arrange
        argv = ["run_headless_reference_physical_matrix.py", "--help"]
        help_stream = io.StringIO()

        # Act
        with patch.object(sys, "argv", argv):
            with redirect_stdout(help_stream):
                with self.assertRaises(SystemExit) as exit_context:
                    physical_matrix.parse_args()

        # Assert
        self.assertEqual(exit_context.exception.code, 0)
        help_text = help_stream.getvalue()
        self.assertIn("direct-restart-", help_text)
        self.assertIn("direct-isolation-", help_text)
        self.assertIn("direct-route-", help_text)

    def test_resolve_scenarios_accepts_the_new_names_without_changing_auto_defaults(self) -> None:
        # Arrange
        explicit_args = physical_matrix.argparse.Namespace(
            scenarios="direct-guided,direct-restart-recovery,direct-route-break-recovery"
        )
        auto_args = physical_matrix.argparse.Namespace(
            scenarios="auto",
            passive_android_serial=None,
            relay_android_serial=None,
        )

        # Act
        explicit_scenarios = physical_matrix.resolve_scenarios(explicit_args)
        auto_scenarios = physical_matrix.resolve_scenarios(auto_args)

        # Assert
        self.assertEqual(
            explicit_scenarios,
            ["direct-guided", "direct-restart-recovery", "direct-route-break-recovery"],
        )
        self.assertEqual(
            auto_scenarios,
            [
                "direct-guided",
                "direct-pause-resume",
                "direct-full-export",
                "direct-trust-reset-recovery",
                "direct-large-transfer",
            ],
        )
        self.assertNotIn("direct-restart-recovery", auto_scenarios)
        self.assertNotIn("direct-isolation-recovery", auto_scenarios)
        self.assertNotIn("direct-route-break-recovery", auto_scenarios)
        self.assertTrue(
            {
                "direct-restart-recovery",
                "direct-isolation-recovery",
                "direct-route-break-recovery",
            }.issubset(physical_matrix.AVAILABLE_SCENARIOS)
        )


if __name__ == "__main__":
    unittest.main()
