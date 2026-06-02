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
        scenario_results: dict[str, dict[str, object]] | None = None,
    ) -> None:
        self.calls: list[dict[str, object]] = []
        self._default_behavior = {
            "child_returncode": child_returncode,
            "child_stdout": child_stdout,
            "child_stderr": child_stderr,
            "child_writes_summary": child_writes_summary,
            "child_timed_out": False,
            "child_missing": False,
            "child_error": None,
            "analysis_returncode": analysis_returncode,
            "analysis_stdout": analysis_stdout,
            "analysis_stderr": analysis_stderr,
            "analysis_writes_outputs": analysis_writes_outputs,
            "analysis_timed_out": False,
            "analysis_missing": False,
            "analysis_error": None,
            "analysis_markdown": "# analysis\n",
        }
        self._scenario_results = scenario_results or {}

    def _scenario_id(self, *, script_name: str, run_dir: Path) -> str:
        if script_name == "run_headless_reference_relay_proof.py":
            return "relay-constrained"
        if run_dir.name.endswith("relay-constrained") or "relay-constrained" in run_dir.parts:
            return "relay-constrained"
        return "direct-guided"

    def _behavior_for(self, scenario_id: str) -> dict[str, object]:
        behavior = dict(self._default_behavior)
        behavior.update(self._scenario_results.get(scenario_id, {}))
        return behavior

    def _summary_payload(
        self,
        *,
        scenario_id: str,
        script_name: str,
        behavior: dict[str, object],
    ) -> dict[str, object]:
        payload = behavior.get("summary_payload")
        if isinstance(payload, dict):
            return payload
        if scenario_id == "relay-constrained":
            return {
                "status": "passed",
                "scenario": "relay-constrained",
                "senderPlatform": "ios",
                "relayPlatform": "android",
                "passivePlatform": "android",
                "senderCompletion": "sender complete",
                "relayCompletion": "relay complete",
                "passiveCompletion": "passive complete",
                "exportRelativePath": "exports/session-redacted.json",
            }
        if script_name == "run_headless_reference_live_proof.py":
            return {
                "scenario": "direct-guided",
                "android_completion": "passive complete",
                "ios_completion": "sender complete",
                "export_relative_path": "exports/session-redacted.json",
            }
        return {
            "status": "passed",
            "scenario": "direct-guided",
            "senderPlatform": "android",
            "passivePlatform": "android",
            "senderCompletion": "sender complete",
            "passiveCompletion": "passive complete",
            "exportRelativePath": "exports/session-redacted.json",
        }

    def _analysis_payload(self, *, scenario_id: str, behavior: dict[str, object]) -> dict[str, object]:
        payload = behavior.get("analysis_payload")
        if isinstance(payload, dict):
            return payload
        return {
            "status": "pass",
            "scenario_type": "relay" if scenario_id == "relay-constrained" else "direct",
        }

    def __call__(
        self,
        command: list[str],
        *,
        timeout_seconds: float | None = None,
    ) -> reference_fleet.ProbeResult:
        script_name = Path(command[1]).name
        run_dir = Path(command[command.index("--run-dir") + 1])
        scenario_id = self._scenario_id(script_name=script_name, run_dir=run_dir)
        behavior = self._behavior_for(scenario_id)
        self.calls.append(
            {
                "command": list(command),
                "timeout_seconds": timeout_seconds,
                "scenario_id": scenario_id,
                "script_name": script_name,
            }
        )

        if script_name in {
            "run_headless_reference_live_proof.py",
            "run_headless_reference_android_direct_proof.py",
            "run_headless_reference_relay_proof.py",
        }:
            run_dir.mkdir(parents=True, exist_ok=True)
            if behavior["child_writes_summary"]:
                (run_dir / "summary.json").write_text(
                    json.dumps(
                        self._summary_payload(
                            scenario_id=scenario_id,
                            script_name=script_name,
                            behavior=behavior,
                        ),
                        indent=2,
                    ),
                    encoding="utf-8",
                )
            return reference_fleet.ProbeResult(
                command=list(command),
                returncode=behavior["child_returncode"],
                stdout=str(behavior["child_stdout"]),
                stderr=str(behavior["child_stderr"]),
                timed_out=bool(behavior["child_timed_out"]),
                missing=bool(behavior["child_missing"]),
                error=behavior["child_error"],
            )

        if script_name == "analyze_reference_physical_run.py":
            if behavior["analysis_writes_outputs"]:
                (run_dir / "analysis.json").write_text(
                    json.dumps(
                        self._analysis_payload(
                            scenario_id=scenario_id,
                            behavior=behavior,
                        ),
                        indent=2,
                    ),
                    encoding="utf-8",
                )
                (run_dir / "analysis.md").write_text(
                    str(behavior["analysis_markdown"]),
                    encoding="utf-8",
                )
            return reference_fleet.ProbeResult(
                command=list(command),
                returncode=behavior["analysis_returncode"],
                stdout=str(behavior["analysis_stdout"]),
                stderr=str(behavior["analysis_stderr"]),
                timed_out=bool(behavior["analysis_timed_out"]),
                missing=bool(behavior["analysis_missing"]),
                error=behavior["analysis_error"],
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

    def scenario_by_id(self, payload: dict[str, object], scenario_id: str) -> dict[str, object]:
        return next(
            scenario
            for scenario in payload["scenarios"]
            if scenario["scenarioId"] == scenario_id
        )

    def reason_codes(self, payload: dict[str, object]) -> set[str]:
        return {reason["code"] for reason in payload.get("reasons", [])}

    def extra_force_stop_serials(self, command: list[str]) -> list[str]:
        return [
            command[index + 1]
            for index, token in enumerate(command[:-1])
            if token == "--extra-force-stop-serial"
        ]

    def assert_artifact_links(self, scenario: dict[str, object]) -> None:
        self.assertEqual(
            set(scenario["artifacts"].keys()),
            {
                "summary",
                "analysisJson",
                "analysisMarkdown",
                "runnerStdout",
                "runnerStderr",
                "analysisStdout",
                "analysisStderr",
            },
        )

    def assert_event_sequence_contains(self, scenario: dict[str, object], *events: str) -> None:
        recorded_events = [entry["event"] for entry in scenario["eventHistory"]]
        for event in events:
            self.assertIn(event, recorded_events)

    def test_plan_happy_path_campaign_orders_mixed_direct_then_relay_with_green_initial_gate(self) -> None:
        # Arrange
        manifest = self.build_manifest(
            android_rows=(("android-passive", "device"), ("android-relay", "device")),
            android_models={
                "android-passive": "Pixel 8",
                "android-relay": "Galaxy S24",
            },
            ios_rows=(("iPhone 15", "iOS 18.0", "Connected", "00008110-000E196E0E92401E"),),
        )

        # Act
        planned = release_campaign.plan_happy_path_campaign(
            manifest,
            run_root=Path("/tmp/reference-release-campaign"),
        )

        # Assert
        campaign_plan = planned["plan"]
        campaign_state = planned["state"]
        direct_plan = self.scenario_by_id(campaign_plan, "direct-guided")
        relay_plan = self.scenario_by_id(campaign_plan, "relay-constrained")
        direct_state = self.scenario_by_id(campaign_state, "direct-guided")
        relay_state = self.scenario_by_id(campaign_state, "relay-constrained")

        self.assertEqual(campaign_plan["planVersion"], 2)
        self.assertEqual(campaign_plan["scenarioCatalogVersion"], 1)
        self.assertEqual(campaign_state["stateVersion"], 2)
        self.assertEqual(
            [scenario["scenarioId"] for scenario in campaign_plan["scenarios"]],
            ["direct-guided", "relay-constrained"],
        )

        self.assertEqual(direct_plan["assignmentShape"], "mixed")
        self.assertEqual(direct_plan["initialStatus"], "planned")
        self.assertEqual(direct_plan["eligibilityStatus"], "runnable")
        self.assertEqual(Path(direct_plan["runnerScript"]).name, "run_headless_reference_live_proof.py")
        self.assertEqual(direct_plan["runDirectory"], "baseline/direct-guided-mixed")
        self.assertEqual(direct_plan["participants"]["sender"]["platform"], "ios")
        self.assertEqual(direct_plan["participants"]["passive"]["platform"], "android")
        self.assert_artifact_links(direct_plan)

        self.assertEqual(relay_plan["initialStatus"], "planned")
        self.assertEqual(relay_plan["eligibilityStatus"], "runnable")
        self.assertEqual(Path(relay_plan["runnerScript"]).name, "run_headless_reference_relay_proof.py")
        self.assertEqual(relay_plan["runDirectory"], "scenarios/02-relay-constrained")
        self.assertEqual(relay_plan["participants"]["sender"]["platform"], "ios")
        self.assertEqual(relay_plan["participants"]["relay"]["platform"], "android")
        self.assertEqual(relay_plan["participants"]["passive"]["platform"], "android")
        self.assertIn("--relay-android-serial", relay_plan["runnerCommand"])
        self.assertIn("--passive-android-serial", relay_plan["runnerCommand"])
        self.assert_artifact_links(relay_plan)
        self.assertEqual(campaign_plan["selectedBaseline"]["shape"], "mixed")

        self.assertEqual(campaign_state["happyPathGate"]["status"], "green")
        self.assertIsNone(campaign_state["happyPathGate"]["firstFailScenarioId"])
        self.assertEqual(direct_state["status"], "planned")
        self.assertEqual(relay_state["status"], "planned")
        self.assert_artifact_links(direct_state)
        self.assert_artifact_links(relay_state)
        self.assert_event_sequence_contains(direct_state, "scenario-initialized")
        self.assert_event_sequence_contains(relay_state, "scenario-initialized")

    def test_run_campaign_executes_direct_then_relay_and_persists_artifact_links(self) -> None:
        # Arrange
        manifest = self.build_manifest(
            android_rows=(("android-passive", "device"), ("android-relay", "device")),
            android_models={
                "android-passive": "Pixel 8",
                "android-relay": "Galaxy S24",
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
            self.assertEqual(
                [call["script_name"] for call in process_runner.calls],
                [
                    "run_headless_reference_live_proof.py",
                    "analyze_reference_physical_run.py",
                    "run_headless_reference_relay_proof.py",
                    "analyze_reference_physical_run.py",
                ],
            )
            self.assertEqual(
                [call["scenario_id"] for call in process_runner.calls],
                [
                    "direct-guided",
                    "direct-guided",
                    "relay-constrained",
                    "relay-constrained",
                ],
            )

            direct_command = process_runner.calls[0]["command"]
            relay_command = process_runner.calls[2]["command"]
            selected_passive = reference_fleet.find_device_by_alias(
                manifest,
                manifest["selectedAssignment"]["participants"]["passive"],
            )
            self.assertIsNotNone(selected_passive)
            selected_sender = reference_fleet.find_device_by_alias(
                manifest,
                manifest["selectedAssignment"]["participants"]["sender"],
            )
            self.assertIsNotNone(selected_sender)
            relay_device = next(
                device
                for device in manifest["devices"]
                if device["platform"] == "android" and device["alias"] != selected_passive["alias"]
            )
            self.assertEqual(Path(direct_command[1]).name, "run_headless_reference_live_proof.py")
            self.assertIn(selected_passive["controlId"], direct_command)
            self.assertIn(selected_sender["controlId"], direct_command)
            self.assertEqual(Path(relay_command[1]).name, "run_headless_reference_relay_proof.py")
            self.assertIn(selected_sender["controlId"], relay_command)
            self.assertIn(selected_passive["controlId"], relay_command)
            self.assertIn(relay_device["controlId"], relay_command)

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

            campaign_plan = self.load_json(run_root / "campaign-plan.json")
            direct_plan = self.scenario_by_id(campaign_plan, "direct-guided")
            relay_plan = self.scenario_by_id(campaign_plan, "relay-constrained")
            self.assertEqual(campaign_plan["status"], "pass")
            self.assertEqual(campaign_plan["selectedBaseline"]["shape"], "mixed")
            self.assertEqual(
                campaign_plan["selectedBaseline"]["participants"]["sender"]["controlId"],
                selected_sender["controlId"],
            )
            self.assertEqual(
                campaign_plan["selectedBaseline"]["participants"]["passive"]["controlId"],
                selected_passive["controlId"],
            )
            self.assertEqual(direct_plan["initialStatus"], "planned")
            self.assertEqual(relay_plan["initialStatus"], "planned")

            campaign_state = self.load_json(run_root / "campaign-state.json")
            direct_state = self.scenario_by_id(campaign_state, "direct-guided")
            relay_state = self.scenario_by_id(campaign_state, "relay-constrained")
            self.assertEqual(direct_state["status"], "pass")
            self.assertEqual(relay_state["status"], "pass")
            self.assertEqual(direct_state["childExitCode"], 0)
            self.assertEqual(direct_state["analysisExitCode"], 0)
            self.assertEqual(relay_state["childExitCode"], 0)
            self.assertEqual(relay_state["analysisExitCode"], 0)
            self.assertEqual(campaign_state["happyPathGate"]["status"], "green")
            self.assertIsNone(campaign_state["happyPathGate"]["firstFailScenarioId"])
            self.assert_artifact_links(direct_state)
            self.assert_artifact_links(relay_state)
            self.assert_event_sequence_contains(
                direct_state,
                "scenario-initialized",
                "scenario-started",
                "scenario-runner-finished",
                "scenario-analysis-finished",
                "scenario-finished",
            )
            self.assert_event_sequence_contains(
                relay_state,
                "scenario-initialized",
                "scenario-started",
                "scenario-runner-finished",
                "scenario-analysis-finished",
                "scenario-finished",
            )
            for scenario_state in (direct_state, relay_state):
                for relative_artifact_path in scenario_state["artifacts"].values():
                    self.assertTrue((run_root / relative_artifact_path).exists())

    def test_run_campaign_keeps_gate_red_on_first_direct_failure_but_runs_relay_for_later_evidence(self) -> None:
        # Arrange
        manifest = self.build_manifest(
            android_rows=(("android-passive", "device"), ("android-relay", "device")),
            android_models={
                "android-passive": "Pixel 8",
                "android-relay": "Galaxy S24",
            },
            ios_rows=(("iPhone 15", "iOS 18.0", "Connected", "00008110-000E196E0E92401E"),),
        )
        process_runner = ReleaseCampaignProcessRunner(
            scenario_results={
                "direct-guided": {
                    "analysis_payload": {"status": "fail", "scenario_type": "direct"},
                }
            }
        )

        with tempfile.TemporaryDirectory() as temporary_directory:
            run_root = Path(temporary_directory) / "direct-failure"

            # Act
            exit_code = release_campaign.run_campaign(
                run_root=run_root,
                build_manifest=lambda: manifest,
                process_runner=process_runner,
            )

            # Assert
            self.assertEqual(exit_code, release_campaign.EXIT_FAIL)
            self.assertEqual(len(process_runner.calls), 4)
            campaign_state = self.load_json(run_root / "campaign-state.json")
            direct_state = self.scenario_by_id(campaign_state, "direct-guided")
            relay_state = self.scenario_by_id(campaign_state, "relay-constrained")
            self.assertEqual(direct_state["status"], "fail")
            self.assertEqual(relay_state["status"], "pass")
            self.assertEqual(campaign_state["status"], "fail")
            self.assertEqual(campaign_state["happyPathGate"]["status"], "red")
            self.assertEqual(campaign_state["happyPathGate"]["firstFailScenarioId"], "direct-guided")
            self.assertIn(
                "baseline-analysis-not-pass",
                {reason["code"] for reason in direct_state["reasons"]},
            )

    def test_run_campaign_dispatches_android_only_runner_and_skips_relay_without_resetting_green_gate(self) -> None:
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
            self.assertEqual(
                [call["script_name"] for call in process_runner.calls],
                [
                    "run_headless_reference_android_direct_proof.py",
                    "analyze_reference_physical_run.py",
                ],
            )
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

            campaign_plan = self.load_json(run_root / "campaign-plan.json")
            direct_plan = self.scenario_by_id(campaign_plan, "direct-guided")
            relay_plan = self.scenario_by_id(campaign_plan, "relay-constrained")
            self.assertEqual(direct_plan["assignmentShape"], "android-only")
            self.assertEqual(direct_plan["initialStatus"], "planned")
            self.assertEqual(relay_plan["initialStatus"], "skipped")
            self.assertEqual(relay_plan["eligibilityStatus"], "skipped")
            self.assertIn("relay-ios-sender-required", self.reason_codes(relay_plan))
            self.assertEqual(campaign_plan["selectedBaseline"]["shape"], "android-only")

            campaign_state = self.load_json(run_root / "campaign-state.json")
            direct_state = self.scenario_by_id(campaign_state, "direct-guided")
            relay_state = self.scenario_by_id(campaign_state, "relay-constrained")
            self.assertEqual(direct_state["status"], "pass")
            self.assertEqual(relay_state["status"], "skipped")
            self.assertEqual(campaign_state["happyPathGate"]["status"], "green")
            self.assertIsNone(campaign_state["happyPathGate"]["firstFailScenarioId"])

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
            self.assertIn(
                "no-runnable-candidate",
                {reason["code"] for reason in persisted_manifest["campaign"]["baselineExecution"]["reasons"]},
            )

            campaign_plan = self.load_json(run_root / "campaign-plan.json")
            direct_plan = self.scenario_by_id(campaign_plan, "direct-guided")
            relay_plan = self.scenario_by_id(campaign_plan, "relay-constrained")
            self.assertEqual(direct_plan["assignmentShape"], "android-only")
            self.assertEqual(direct_plan["initialStatus"], "skipped")
            self.assertEqual(relay_plan["initialStatus"], "skipped")
            self.assertEqual(campaign_plan["selectedBaseline"], None)

            campaign_state = self.load_json(run_root / "campaign-state.json")
            self.assertEqual(campaign_state["happyPathGate"]["status"], "green")
            self.assertIsNone(campaign_state["happyPathGate"]["firstFailScenarioId"])

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

            campaign_state = self.load_json(run_root / "campaign-state.json")
            direct_state = self.scenario_by_id(campaign_state, "direct-guided")
            relay_state = self.scenario_by_id(campaign_state, "relay-constrained")
            self.assertEqual(direct_state["status"], "invalid-environment")
            self.assertEqual(relay_state["status"], "invalid-environment")
            self.assertEqual(campaign_state["happyPathGate"]["status"], "green")

    def test_plan_happy_path_campaign_uses_android_only_direct_but_invalidates_relay_when_ios_signing_is_unresolved(self) -> None:
        # Arrange
        manifest = self.build_manifest(
            android_rows=(("android-one", "device"), ("android-two", "device")),
            android_models={
                "android-one": "Pixel 8",
                "android-two": "Galaxy S24",
            },
            ios_rows=(("iPhone 15", "iOS 18.0", "Connected", "00008110-000E196E0E92401E"),),
            development_team_lookup=unresolved_team_lookup,
        )

        # Act
        planned = release_campaign.plan_happy_path_campaign(
            manifest,
            run_root=Path("/tmp/reference-release-campaign"),
        )

        # Assert
        campaign_plan = planned["plan"]
        direct_plan = self.scenario_by_id(campaign_plan, "direct-guided")
        relay_plan = self.scenario_by_id(campaign_plan, "relay-constrained")
        self.assertEqual(direct_plan["assignmentShape"], "android-only")
        self.assertEqual(direct_plan["initialStatus"], "planned")
        self.assertEqual(relay_plan["initialStatus"], "invalid-environment")
        self.assertIn("ios-development-team-unresolved", self.reason_codes(relay_plan))
        self.assertEqual(planned["state"]["happyPathGate"]["status"], "green")

    def test_plan_happy_path_campaign_rejects_malformed_selection_payloads(self) -> None:
        # Arrange
        manifest = self.build_manifest(
            android_rows=(("android-passive", "device"), ("android-relay", "device")),
            android_models={
                "android-passive": "Pixel 8",
                "android-relay": "Galaxy S24",
            },
            ios_rows=(("iPhone 15", "iOS 18.0", "Connected", "00008110-000E196E0E92401E"),),
        )
        duplicate_alias_manifest = json.loads(json.dumps(manifest))
        duplicate_alias = duplicate_alias_manifest["selectedAssignment"]["participants"]["passive"]
        for device in duplicate_alias_manifest["devices"]:
            if device["alias"] != duplicate_alias:
                device["alias"] = duplicate_alias
                break

        cases = [
            (
                "missing-selection",
                {**manifest, "selection": None},
                "selection-missing",
            ),
            (
                "selection-mismatch",
                {
                    **manifest,
                    "selection": {
                        **manifest["selection"],
                        "selectedAssignmentId": "unexpected-assignment",
                    },
                },
                "selected-assignment-mismatch",
            ),
            (
                "duplicate-alias",
                duplicate_alias_manifest,
                "selected-assignment-device-duplicate",
            ),
        ]

        for name, malformed_manifest, expected_code in cases:
            with self.subTest(name=name):
                planned = release_campaign.plan_happy_path_campaign(
                    malformed_manifest,
                    run_root=Path("/tmp/reference-release-campaign"),
                )
                direct_plan = self.scenario_by_id(planned["plan"], "direct-guided")
                relay_plan = self.scenario_by_id(planned["plan"], "relay-constrained")
                self.assertEqual(direct_plan["initialStatus"], "invalid-environment")
                self.assertIn(expected_code, self.reason_codes(direct_plan))
                self.assertEqual(relay_plan["eligibilityStatus"], "invalid-environment")
                self.assertEqual(relay_plan["runnerCommand"], [])

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

    def test_run_campaign_rejects_missing_analysis_outputs_but_keeps_later_relay_evidence(self) -> None:
        # Arrange
        manifest = self.build_manifest(
            android_rows=(("android-passive", "device"), ("android-relay", "device")),
            android_models={
                "android-passive": "Pixel 8",
                "android-relay": "Galaxy S24",
            },
            ios_rows=(("iPhone 15", "iOS 18.0", "Connected", "00008110-000E196E0E92401E"),),
        )
        process_runner = ReleaseCampaignProcessRunner(
            scenario_results={
                "direct-guided": {"analysis_writes_outputs": False},
            }
        )

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

            campaign_state = self.load_json(run_root / "campaign-state.json")
            direct_state = self.scenario_by_id(campaign_state, "direct-guided")
            relay_state = self.scenario_by_id(campaign_state, "relay-constrained")
            self.assertEqual(direct_state["status"], "fail")
            self.assertEqual(relay_state["status"], "pass")
            self.assertEqual(campaign_state["happyPathGate"]["status"], "red")
            self.assertEqual(campaign_state["happyPathGate"]["firstFailScenarioId"], "direct-guided")

    def test_run_campaign_clears_previous_artifacts_before_reusing_the_same_run_root(self) -> None:
        # Arrange
        def make_manifest() -> dict[str, object]:
            return self.build_manifest(
                android_rows=(("android-passive", "device"), ("android-relay", "device")),
                android_models={
                    "android-passive": "Pixel 8",
                    "android-relay": "Galaxy S24",
                },
                ios_rows=(("iPhone 15", "iOS 18.0", "Connected", "00008110-000E196E0E92401E"),),
            )

        with tempfile.TemporaryDirectory() as temporary_directory:
            run_root = Path(temporary_directory) / "reused-run-root"

            first_process_runner = ReleaseCampaignProcessRunner()
            first_exit_code = release_campaign.run_campaign(
                run_root=run_root,
                build_manifest=make_manifest,
                process_runner=first_process_runner,
            )
            self.assertEqual(first_exit_code, release_campaign.EXIT_PASS)
            self.assertEqual(
                [call["scenario_id"] for call in first_process_runner.calls],
                ["direct-guided", "direct-guided", "relay-constrained", "relay-constrained"],
            )

            second_process_runner = ReleaseCampaignProcessRunner(
                scenario_results={
                    "direct-guided": {
                        "child_writes_summary": False,
                        "analysis_writes_outputs": False,
                    },
                }
            )

            # Act
            second_exit_code = release_campaign.run_campaign(
                run_root=run_root,
                build_manifest=make_manifest,
                process_runner=second_process_runner,
            )

            # Assert
            self.assertEqual(second_exit_code, release_campaign.EXIT_FAIL)
            self.assertEqual(
                [call["scenario_id"] for call in second_process_runner.calls],
                ["direct-guided", "relay-constrained", "relay-constrained"],
            )
            self.assertEqual(
                [call["script_name"] for call in second_process_runner.calls],
                [
                    "run_headless_reference_live_proof.py",
                    "run_headless_reference_relay_proof.py",
                    "analyze_reference_physical_run.py",
                ],
            )

            campaign_state = self.load_json(run_root / "campaign-state.json")
            direct_state = self.scenario_by_id(campaign_state, "direct-guided")
            relay_state = self.scenario_by_id(campaign_state, "relay-constrained")
            self.assertEqual(direct_state["status"], "fail")
            self.assertEqual(relay_state["status"], "pass")
            self.assertEqual(campaign_state["status"], "fail")
            self.assertEqual(campaign_state["happyPathGate"]["status"], "red")
            self.assertEqual(campaign_state["happyPathGate"]["firstFailScenarioId"], "direct-guided")
            self.assertIn(
                "baseline-summary-missing",
                {reason["code"] for reason in direct_state["reasons"]},
            )
            self.assertIn(
                "baseline-analysis-missing",
                {reason["code"] for reason in direct_state["reasons"]},
            )

    def test_run_campaign_classifies_environmental_child_failures_honestly(self) -> None:
        # Arrange
        manifest = self.build_manifest(
            android_rows=(("android-passive", "device"), ("android-relay", "device")),
            android_models={
                "android-passive": "Pixel 8",
                "android-relay": "Galaxy S24",
            },
            ios_rows=(("iPhone 15", "iOS 18.0", "Connected", "00008110-000E196E0E92401E"),),
        )
        process_runner = ReleaseCampaignProcessRunner(
            scenario_results={
                "relay-constrained": {
                    "child_returncode": 7,
                    "child_stderr": "DEVELOPMENT_TEAM is required unless local provisioning exists",
                    "child_writes_summary": False,
                    "analysis_writes_outputs": False,
                }
            }
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
            campaign_state = self.load_json(run_root / "campaign-state.json")
            direct_state = self.scenario_by_id(campaign_state, "direct-guided")
            relay_state = self.scenario_by_id(campaign_state, "relay-constrained")
            self.assertEqual(direct_state["status"], "pass")
            self.assertEqual(relay_state["status"], "invalid-environment")
            self.assertEqual(relay_state["childExitCode"], 7)
            self.assertIn(
                "relay-constrained-runner-invalid-environment",
                {reason["code"] for reason in relay_state["reasons"]},
            )
            self.assertEqual(campaign_state["happyPathGate"]["status"], "red")
            self.assertEqual(campaign_state["happyPathGate"]["firstFailScenarioId"], "relay-constrained")

    def test_run_campaign_classifies_environmental_analyzer_failures_honestly(self) -> None:
        # Arrange
        manifest = self.build_manifest(
            android_rows=(("android-passive", "device"), ("android-relay", "device")),
            android_models={
                "android-passive": "Pixel 8",
                "android-relay": "Galaxy S24",
            },
            ios_rows=(("iPhone 15", "iOS 18.0", "Connected", "00008110-000E196E0E92401E"),),
        )
        process_runner = ReleaseCampaignProcessRunner(
            scenario_results={
                "relay-constrained": {
                    "analysis_returncode": 4,
                    "analysis_stderr": "xcodebuild tool not ready",
                    "analysis_writes_outputs": False,
                }
            }
        )

        with tempfile.TemporaryDirectory() as temporary_directory:
            run_root = Path(temporary_directory) / "analysis-environment-failure"

            # Act
            exit_code = release_campaign.run_campaign(
                run_root=run_root,
                build_manifest=lambda: manifest,
                process_runner=process_runner,
            )

            # Assert
            self.assertEqual(exit_code, release_campaign.EXIT_INVALID_ENVIRONMENT)
            campaign_state = self.load_json(run_root / "campaign-state.json")
            direct_state = self.scenario_by_id(campaign_state, "direct-guided")
            relay_state = self.scenario_by_id(campaign_state, "relay-constrained")
            self.assertEqual(direct_state["status"], "pass")
            self.assertEqual(relay_state["status"], "invalid-environment")
            self.assertEqual(relay_state["analysisExitCode"], 4)
            self.assertIn(
                "relay-constrained-analysis-invalid-environment",
                {reason["code"] for reason in relay_state["reasons"]},
            )
            self.assertEqual(campaign_state["happyPathGate"]["status"], "red")
            self.assertEqual(campaign_state["happyPathGate"]["firstFailScenarioId"], "relay-constrained")


if __name__ == "__main__":
    unittest.main()
