from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

from support import (
    SCRIPTS_DIR,
    FakeCommandRunner,
    adb_devices_output,
    android_model_output,
    devicectl_table,
    probe_response,
    reference_fleet,
    resolved_team_lookup,
    unresolved_team_lookup,
)

if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import run_reference_release_campaign as release_campaign  # noqa: E402


class ReleaseCampaignProcessRunner:
    def __init__(
        self,
        *,
        child_returncode: int = 0,
        child_stdout: str = "",
        child_stderr: str = "",
        child_writes_summary: bool = True,
        analysis_returncode: int = 0,
        analysis_stdout: str = "",
        analysis_stderr: str = "",
        analysis_writes_outputs: bool = True,
    ) -> None:
        self.calls: list[dict[str, object]] = []
        self._child_returncode = child_returncode
        self._child_stdout = child_stdout
        self._child_stderr = child_stderr
        self._child_writes_summary = child_writes_summary
        self._analysis_returncode = analysis_returncode
        self._analysis_stdout = analysis_stdout
        self._analysis_stderr = analysis_stderr
        self._analysis_writes_outputs = analysis_writes_outputs

    def __call__(
        self,
        command: list[str],
        *,
        timeout_seconds: float | None = None,
    ) -> reference_fleet.ProbeResult:
        self.calls.append({"command": list(command), "timeout_seconds": timeout_seconds})
        script_name = Path(command[1]).name
        run_dir = Path(command[command.index("--run-dir") + 1])

        if script_name in {
            "run_headless_reference_live_proof.py",
            "run_headless_reference_android_direct_proof.py",
        }:
            run_dir.mkdir(parents=True, exist_ok=True)
            if self._child_writes_summary:
                if script_name == "run_headless_reference_live_proof.py":
                    summary = {
                        "scenario": "direct-guided",
                        "android_completion": "passive complete",
                        "ios_completion": "sender complete",
                        "export_relative_path": "exports/session-redacted.json",
                    }
                else:
                    summary = {
                        "status": "passed",
                        "scenario": "direct-guided",
                        "senderPlatform": "android",
                        "passivePlatform": "android",
                        "senderCompletion": "sender complete",
                        "passiveCompletion": "passive complete",
                        "exportRelativePath": "exports/session-redacted.json",
                    }
                (run_dir / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
            return reference_fleet.ProbeResult(
                command=list(command),
                returncode=self._child_returncode,
                stdout=self._child_stdout,
                stderr=self._child_stderr,
            )

        if script_name == "analyze_reference_physical_run.py":
            if self._analysis_writes_outputs:
                (run_dir / "analysis.json").write_text(
                    json.dumps({"status": "pass", "scenario_type": "direct"}, indent=2),
                    encoding="utf-8",
                )
                (run_dir / "analysis.md").write_text("# analysis\n", encoding="utf-8")
            return reference_fleet.ProbeResult(
                command=list(command),
                returncode=self._analysis_returncode,
                stdout=self._analysis_stdout,
                stderr=self._analysis_stderr,
            )

        raise AssertionError(f"Unexpected command: {command!r}")


class ReferenceReleaseCampaignTests(unittest.TestCase):
    def build_manifest(
        self,
        *,
        android_rows: tuple[tuple[str, str], ...],
        android_models: dict[str, str],
        ios_rows: tuple[tuple[str, str, str, str], ...] = (),
        development_team_lookup=resolved_team_lookup,
    ) -> dict[str, object]:
        responses: dict[tuple[str, ...], dict[str, object]] = {
            ("adb", "devices"): probe_response(stdout=adb_devices_output(*android_rows)),
            ("xcrun", "devicectl", "list", "devices"): probe_response(stdout=devicectl_table(*ios_rows)),
        }
        for serial, model in android_models.items():
            responses[("adb", "-s", serial, "shell", "getprop", "ro.product.model")] = probe_response(
                stdout=android_model_output(model)
            )
        runner = FakeCommandRunner(responses)
        return reference_fleet.build_fleet_manifest(
            command_runner=runner,
            environment={},
            development_team_lookup=development_team_lookup,
        )

    def load_json(self, path: Path) -> dict[str, object]:
        return json.loads(path.read_text(encoding="utf-8"))

    def extra_force_stop_serials(self, command: list[str]) -> list[str]:
        return [
            command[index + 1]
            for index, token in enumerate(command[:-1])
            if token == "--extra-force-stop-serial"
        ]

    def test_run_campaign_prefers_mixed_runner_and_persists_artifact_links(self) -> None:
        # Arrange
        manifest = self.build_manifest(
            android_rows=(("android-passive", "device"), ("android-sender", "device")),
            android_models={
                "android-passive": "Pixel 8",
                "android-sender": "Galaxy S24",
            },
            ios_rows=(("iPhone 15", "iOS 18.0", "Connected", "00008110-000E196E0E92401E"),),
        )
        process_runner = ReleaseCampaignProcessRunner()

        with tempfile.TemporaryDirectory() as temporary_directory:
            run_root = Path(temporary_directory) / "mixed-campaign"

            # Act
            exit_code = release_campaign.run_campaign(
                run_root=run_root,
                build_manifest=lambda: manifest,
                process_runner=process_runner,
            )

            # Assert
            self.assertEqual(exit_code, release_campaign.EXIT_PASS)
            self.assertEqual(len(process_runner.calls), 2)
            child_command = process_runner.calls[0]["command"]
            selected_passive = reference_fleet.find_device_by_alias(
                manifest,
                manifest["selectedAssignment"]["participants"]["passive"],
            )
            self.assertIsNotNone(selected_passive)
            unused_android_serials = [
                device["controlId"]
                for device in manifest["devices"]
                if device["platform"] == "android"
                and device["available"]
                and device["controlId"] != selected_passive["controlId"]
            ]
            self.assertEqual(Path(child_command[1]).name, "run_headless_reference_live_proof.py")
            self.assertIn("--android-serial", child_command)
            self.assertIn(selected_passive["controlId"], child_command)
            self.assertIn("--ios-device", child_command)
            self.assertIn("00008110-000E196E0E92401E", child_command)
            self.assertEqual(self.extra_force_stop_serials(child_command), unused_android_serials)

            persisted_manifest = self.load_json(run_root / "fleet-manifest.json")
            baseline_execution = persisted_manifest["campaign"]["baselineExecution"]
            self.assertEqual(persisted_manifest["selection"]["status"], "selected")
            self.assertEqual(baseline_execution["status"], "pass")
            self.assertEqual(Path(baseline_execution["runnerScript"]).name, "run_headless_reference_live_proof.py")
            self.assertEqual(
                baseline_execution["artifacts"]["summary"],
                "baseline/direct-guided-mixed/summary.json",
            )
            self.assertEqual(
                baseline_execution["artifacts"]["analysisJson"],
                "baseline/direct-guided-mixed/analysis.json",
            )
            self.assertEqual(
                baseline_execution["artifacts"]["analysisMarkdown"],
                "baseline/direct-guided-mixed/analysis.md",
            )
            self.assertEqual(
                self.extra_force_stop_serials(baseline_execution["runnerCommand"]),
                unused_android_serials,
            )

            campaign_plan = self.load_json(run_root / "campaign-plan.json")
            self.assertEqual(campaign_plan["status"], "pass")
            self.assertEqual(campaign_plan["selectedBaseline"]["shape"], "mixed")
            self.assertEqual(
                campaign_plan["selectedBaseline"]["artifacts"]["summary"],
                "baseline/direct-guided-mixed/summary.json",
            )
            self.assertEqual(
                campaign_plan["selectedBaseline"]["participants"]["sender"]["platform"],
                "ios",
            )
            self.assertEqual(
                campaign_plan["selectedBaseline"]["participants"]["passive"]["controlId"],
                selected_passive["controlId"],
            )
            self.assertEqual(
                campaign_plan["selectedBaseline"]["runnerCommand"],
                baseline_execution["runnerCommand"],
            )

    def test_run_campaign_dispatches_android_only_runner_for_three_android_devices(self) -> None:
        # Arrange
        manifest = self.build_manifest(
            android_rows=(("android-one", "device"), ("android-two", "device"), ("android-three", "device")),
            android_models={
                "android-one": "Pixel 8",
                "android-two": "Pixel Fold",
                "android-three": "Galaxy S24",
            },
        )
        process_runner = ReleaseCampaignProcessRunner()

        with tempfile.TemporaryDirectory() as temporary_directory:
            run_root = Path(temporary_directory) / "android-only-campaign"

            # Act
            exit_code = release_campaign.run_campaign(
                run_root=run_root,
                build_manifest=lambda: manifest,
                process_runner=process_runner,
            )

            # Assert
            self.assertEqual(exit_code, release_campaign.EXIT_PASS)
            child_command = process_runner.calls[0]["command"]
            selected_sender = reference_fleet.find_device_by_alias(
                manifest,
                manifest["selectedAssignment"]["participants"]["sender"],
            )
            selected_passive = reference_fleet.find_device_by_alias(
                manifest,
                manifest["selectedAssignment"]["participants"]["passive"],
            )
            self.assertIsNotNone(selected_sender)
            self.assertIsNotNone(selected_passive)
            unused_android_serials = [
                device["controlId"]
                for device in manifest["devices"]
                if device["platform"] == "android"
                and device["available"]
                and device["controlId"] not in {selected_sender["controlId"], selected_passive["controlId"]}
            ]
            self.assertEqual(Path(child_command[1]).name, "run_headless_reference_android_direct_proof.py")
            self.assertIn("--sender-android-serial", child_command)
            self.assertIn("--passive-android-serial", child_command)
            self.assertIn(selected_sender["controlId"], child_command)
            self.assertIn(selected_passive["controlId"], child_command)
            self.assertEqual(self.extra_force_stop_serials(child_command), unused_android_serials)

            persisted_manifest = self.load_json(run_root / "fleet-manifest.json")
            self.assertEqual(
                persisted_manifest["campaign"]["baselineExecution"]["shape"],
                "android-only",
            )
            self.assertEqual(
                Path(persisted_manifest["campaign"]["baselineExecution"]["runnerScript"]).name,
                "run_headless_reference_android_direct_proof.py",
            )
            self.assertEqual(
                self.extra_force_stop_serials(
                    persisted_manifest["campaign"]["baselineExecution"]["runnerCommand"]
                ),
                unused_android_serials,
            )

    def test_run_campaign_marks_skipped_and_never_dispatches_a_child_runner(self) -> None:
        # Arrange
        manifest = self.build_manifest(
            android_rows=(("android-one", "device"),),
            android_models={"android-one": "Pixel 8"},
        )

        def unexpected_process_runner(command: list[str], *, timeout_seconds: float | None = None):
            del timeout_seconds
            raise AssertionError(f"Child runner should not have been called: {command!r}")

        with tempfile.TemporaryDirectory() as temporary_directory:
            run_root = Path(temporary_directory) / "skipped-campaign"

            # Act
            exit_code = release_campaign.run_campaign(
                run_root=run_root,
                build_manifest=lambda: manifest,
                process_runner=unexpected_process_runner,
            )

            # Assert
            self.assertEqual(exit_code, release_campaign.EXIT_SKIPPED)
            persisted_manifest = self.load_json(run_root / "fleet-manifest.json")
            self.assertEqual(persisted_manifest["selection"]["status"], "skipped")
            self.assertEqual(persisted_manifest["campaign"]["baselineExecution"]["status"], "skipped")
            self.assertEqual(
                persisted_manifest["campaign"]["baselineExecution"]["reasons"][0]["code"],
                "no-runnable-candidate",
            )

    def test_run_campaign_marks_invalid_environment_and_never_dispatches_a_child_runner(self) -> None:
        # Arrange
        manifest = self.build_manifest(
            android_rows=(("android-one", "device"),),
            android_models={"android-one": "Pixel 8"},
            ios_rows=(("iPhone 15", "iOS 18.0", "Connected", "00008110-000E196E0E92401E"),),
            development_team_lookup=unresolved_team_lookup,
        )

        def unexpected_process_runner(command: list[str], *, timeout_seconds: float | None = None):
            del timeout_seconds
            raise AssertionError(f"Child runner should not have been called: {command!r}")

        with tempfile.TemporaryDirectory() as temporary_directory:
            run_root = Path(temporary_directory) / "invalid-campaign"

            # Act
            exit_code = release_campaign.run_campaign(
                run_root=run_root,
                build_manifest=lambda: manifest,
                process_runner=unexpected_process_runner,
            )

            # Assert
            self.assertEqual(exit_code, release_campaign.EXIT_INVALID_ENVIRONMENT)
            persisted_manifest = self.load_json(run_root / "fleet-manifest.json")
            self.assertEqual(persisted_manifest["selection"]["status"], "invalid-environment")
            self.assertEqual(
                persisted_manifest["campaign"]["baselineExecution"]["status"],
                "invalid-environment",
            )
            self.assertIn(
                "ios-development-team-unresolved",
                {reason["code"] for reason in persisted_manifest["campaign"]["baselineExecution"]["reasons"]},
            )

    def test_resolve_runner_spec_rejects_selected_manifest_without_a_known_assignment(self) -> None:
        # Arrange
        manifest = self.build_manifest(
            android_rows=(("android-passive", "device"), ("android-sender", "device")),
            android_models={
                "android-passive": "Pixel 8",
                "android-sender": "Galaxy S24",
            },
            ios_rows=(("iPhone 15", "iOS 18.0", "Connected", "00008110-000E196E0E92401E"),),
        )

        malformed_missing_selection = dict(manifest)
        malformed_missing_selection["selectedAssignment"] = None

        malformed_unknown_assignment = dict(manifest)
        malformed_unknown_assignment["selectedAssignment"] = {
            **manifest["selectedAssignment"],
            "assignmentId": "unknown-baseline",
        }

        # Act / Assert
        with self.assertRaises(release_campaign.CampaignError):
            release_campaign.resolve_runner_spec(malformed_missing_selection, run_root=Path("/tmp/test"))

        with self.assertRaises(release_campaign.CampaignError):
            release_campaign.resolve_runner_spec(malformed_unknown_assignment, run_root=Path("/tmp/test"))

    def test_run_campaign_rejects_missing_analysis_outputs_as_a_passing_baseline(self) -> None:
        # Arrange
        manifest = self.build_manifest(
            android_rows=(("android-one", "device"), ("android-two", "device")),
            android_models={
                "android-one": "Pixel 8",
                "android-two": "Pixel Fold",
            },
        )
        process_runner = ReleaseCampaignProcessRunner(analysis_writes_outputs=False)

        with tempfile.TemporaryDirectory() as temporary_directory:
            run_root = Path(temporary_directory) / "missing-analysis"

            # Act
            exit_code = release_campaign.run_campaign(
                run_root=run_root,
                build_manifest=lambda: manifest,
                process_runner=process_runner,
            )

            # Assert
            self.assertEqual(exit_code, release_campaign.EXIT_FAIL)
            persisted_manifest = self.load_json(run_root / "fleet-manifest.json")
            baseline_execution = persisted_manifest["campaign"]["baselineExecution"]
            self.assertEqual(baseline_execution["status"], "fail")
            self.assertIn(
                "baseline-analysis-missing",
                {reason["code"] for reason in baseline_execution["reasons"]},
            )

    def test_run_campaign_classifies_environmental_child_failures_honestly(self) -> None:
        # Arrange
        manifest = self.build_manifest(
            android_rows=(("android-passive", "device"), ("android-sender", "device")),
            android_models={
                "android-passive": "Pixel 8",
                "android-sender": "Galaxy S24",
            },
            ios_rows=(("iPhone 15", "iOS 18.0", "Connected", "00008110-000E196E0E92401E"),),
        )
        process_runner = ReleaseCampaignProcessRunner(
            child_returncode=7,
            child_stderr="DEVELOPMENT_TEAM is required unless local provisioning exists",
            child_writes_summary=False,
            analysis_writes_outputs=False,
        )

        with tempfile.TemporaryDirectory() as temporary_directory:
            run_root = Path(temporary_directory) / "environment-failure"

            # Act
            exit_code = release_campaign.run_campaign(
                run_root=run_root,
                build_manifest=lambda: manifest,
                process_runner=process_runner,
            )

            # Assert
            self.assertEqual(exit_code, release_campaign.EXIT_INVALID_ENVIRONMENT)
            persisted_manifest = self.load_json(run_root / "fleet-manifest.json")
            baseline_execution = persisted_manifest["campaign"]["baselineExecution"]
            self.assertEqual(baseline_execution["status"], "invalid-environment")
            self.assertEqual(baseline_execution["childExitCode"], 7)
            self.assertIn(
                "baseline-runner-invalid-environment",
                {reason["code"] for reason in baseline_execution["reasons"]},
            )


if __name__ == "__main__":
    unittest.main()
