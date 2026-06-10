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

import campaign_report_data  # noqa: E402

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

    def _scenario_id(self, *, run_dir: Path) -> str:
        if run_dir.name.endswith("relay-constrained") or "relay-constrained" in run_dir.parts:
            return "relay-constrained"
        return "direct-guided"

    def _behavior_for(self, scenario_id: str) -> dict[str, object]:
        behavior = dict(self._default_behavior)
        behavior.update(self._scenario_results.get(scenario_id, {}))
        return behavior

    def _summary_payload(self, *, scenario_id: str, behavior: dict[str, object]) -> dict[str, object]:
        payload = behavior.get("summary_payload")
        if isinstance(payload, dict):
            return payload
        return {
            "status": "passed" if scenario_id == "direct-guided" else "passed",
            "scenario": scenario_id,
            "senderPlatform": "ios" if scenario_id == "direct-guided" else "ios",
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
            "scenario_type": "direct",
        }

    def __call__(self, command: list[str], *, timeout_seconds: float | None = None) -> reference_fleet.ProbeResult:
        del timeout_seconds
        script_name = Path(command[1]).name
        run_dir = Path(command[command.index("--run-dir") + 1])
        scenario_id = self._scenario_id(run_dir=run_dir)
        behavior = self._behavior_for(scenario_id)
        self.calls.append(
            {
                "command": list(command),
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
                        self._summary_payload(scenario_id=scenario_id, behavior=behavior),
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
                    json.dumps(self._analysis_payload(scenario_id=scenario_id, behavior=behavior), indent=2),
                    encoding="utf-8",
                )
                (run_dir / "analysis.md").write_text(str(behavior["analysis_markdown"]), encoding="utf-8")
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
        return reference_fleet.build_fleet_manifest(
            command_runner=FakeCommandRunner(responses),
            environment={},
            development_team_lookup=development_team_lookup,
        )

    def load_json(self, path: Path) -> dict[str, object]:
        return json.loads(path.read_text(encoding="utf-8"))

    def load_report_data(self, run_root: Path) -> dict[str, object]:
        return self.load_json(run_root / "report-data.json")

    def scenario_by_id(self, payload: dict[str, object], scenario_id: str) -> dict[str, object]:
        return next(scenario for scenario in payload["scenarios"] if scenario["scenarioId"] == scenario_id)

    def reason_codes(self, payload: dict[str, object]) -> set[str]:
        return {reason["code"] for reason in payload.get("reasons", [])}

    def extra_force_stop_serials(self, command: list[str]) -> list[str]:
        return [command[index + 1] for index, token in enumerate(command[:-1]) if token == "--extra-force-stop-serial"]

    def assert_artifact_links(self, scenario: dict[str, object]) -> None:
        self.assertEqual(
            set(scenario["artifacts"].keys()),
            {"summary", "analysisJson", "analysisMarkdown", "runnerStdout", "runnerStderr", "analysisStdout", "analysisStderr"},
        )

    def assert_event_sequence_contains(self, scenario: dict[str, object], *events: str) -> None:
        recorded_events = [entry["event"] for entry in scenario["eventHistory"]]
        for event in events:
            self.assertIn(event, recorded_events)

    def assert_retained_gate_policy_artifacts(
        self,
        run_root: Path,
        *,
        expected_gate_status: str,
        expected_verdicts: tuple[str, ...] = (),
        unexpected_verdicts: tuple[str, ...] = (),
    ) -> dict[str, object]:
        report_data = self.load_report_data(run_root)
        self.assertEqual(report_data["gateMath"]["thresholds"], campaign_report_data.RETAINED_GATE_THRESHOLDS)
        self.assertEqual(report_data["gateMath"]["status"], expected_gate_status)
        report_verdicts = [str(scenario["verdict"]) for scenario in report_data["scenarios"]]
        for verdict in expected_verdicts:
            self.assertIn(verdict, report_verdicts)
        report_html = (run_root / "release-review-report.html").read_text(encoding="utf-8")
        self.assertIn("Gate policy", report_html)
        self.assertIn("Retained policy thresholds come directly from report-data.json", report_html)
        self.assertIn("Failure count max", report_html)
        self.assertIn("Inconclusive count max", report_html)
        self.assertIn("Invalid env count max", report_html)
        self.assertIn("Pass rate min", report_html)

        for verdict in expected_verdicts:
            self.assertIn(f'metric-card metric-card--{verdict}', report_html)
            self.assertIn(f'scenario-card scenario-card--{verdict}', report_html)
            self.assertIn(f'data-status="{verdict}"', report_html)

        for verdict in unexpected_verdicts:
            self.assertNotIn(f'scenario-card scenario-card--{verdict}', report_html)
            self.assertNotIn(f'verdict verdict-{verdict}', report_html)

        return report_data

    def test_plan_happy_path_campaign_orders_direct_only_with_green_initial_gate(self) -> None:
        manifest = self.build_manifest(
            android_rows=(("android-passive", "device"),),
            android_models={"android-passive": "Pixel 8"},
            ios_rows=(("iPhone 15", "iOS 18.0", "Connected", "00008110-000E196E0E92401E"),),
        )

        planned = release_campaign.plan_happy_path_campaign(manifest, run_root=Path("/tmp/reference-release-campaign"))
        campaign_plan = planned["plan"]
        campaign_state = planned["state"]
        direct_plan = self.scenario_by_id(campaign_plan, "direct-guided")
        direct_state = self.scenario_by_id(campaign_state, "direct-guided")

        self.assertEqual(campaign_plan["planVersion"], 2)
        self.assertEqual(campaign_state["stateVersion"], 2)
        self.assertEqual([scenario["scenarioId"] for scenario in campaign_plan["scenarios"]], ["direct-guided", "relay-constrained"])
        self.assertEqual(direct_plan["assignmentShape"], "mixed")
        self.assertEqual(direct_plan["initialStatus"], "planned")
        self.assertEqual(direct_plan["eligibilityStatus"], "runnable")
        self.assertEqual(Path(direct_plan["runnerScript"]).name, "run_headless_reference_live_proof.py")
        self.assertEqual(campaign_plan["selectedBaseline"]["shape"], "mixed")
        self.assertEqual(campaign_state["happyPathGate"]["status"], "green")
        self.assertIsNone(campaign_state["happyPathGate"]["firstFailScenarioId"])
        self.assertEqual(direct_state["status"], "planned")
        self.assert_artifact_links(direct_plan)
        self.assert_artifact_links(direct_state)
        self.assert_event_sequence_contains(direct_state, "scenario-initialized")

    def test_campaign_selection_taxonomy_uses_retained_status_vocabulary(self) -> None:
        # Arrange
        manifest = {
            "selectedAssignment": {
                "assignmentId": "direct-guided-android-only",
                "shape": "android-only",
                "status": "runnable",
                "reasons": [
                    {"code": "selected-android-only-fallback", "kind": "info", "message": "Selected the Android-only direct-guided fallback because the mixed iOS sender path is not yet supported in live-proof runs."}
                ],
            },
            "candidateAssignments": [
                {
                    "assignmentId": "direct-guided-mixed",
                    "shape": "mixed",
                    "status": "runnable",
                    "reasons": [{"code": "mixed-direct-guided-runnable", "kind": "info", "message": "Selected the mixed direct-guided fleet."}],
                },
                {
                    "assignmentId": "direct-guided-android-only",
                    "shape": "android-only",
                    "status": "runnable",
                    "reasons": [{"code": "selected-android-only-fallback", "kind": "info", "message": "Selected the Android-only direct-guided fallback because the mixed iOS sender path is not yet supported in live-proof runs."}],
                },
            ],
        }

        # Act
        selected_assignment = manifest["selectedAssignment"]
        candidate_statuses = [candidate["status"] for candidate in manifest["candidateAssignments"]]

        # Assert
        self.assertEqual(selected_assignment["shape"], "android-only")
        self.assertEqual(selected_assignment["reasons"][0]["code"], "selected-android-only-fallback")
        self.assertIn("runnable", candidate_statuses)
        self.assertEqual(candidate_statuses, ["runnable", "runnable"])
        self.assertTrue(all(reason["kind"] == "info" for reason in selected_assignment["reasons"]))
        self.assertEqual(selected_assignment["reasons"][0]["code"], "selected-android-only-fallback")
        self.assertEqual(
            selected_assignment["reasons"][0]["message"],
            "Selected the Android-only direct-guided fallback because the mixed iOS sender path is not yet supported in live-proof runs.",
        )

    def test_choose_direct_guided_projection_candidate_prefers_mixed_when_ios_is_available(self) -> None:
        # Arrange
        manifest = self.build_manifest(
            android_rows=(("android-passive", "device"), ("android-sender", "device")),
            android_models={"android-passive": "Pixel 8", "android-sender": "Pixel 7"},
            ios_rows=(("iPhone 15", "iOS 18.0", "Connected", "00008110-000E196E0E92401E"),),
        )
        manifest["selection"] = {"status": "selected", "selectedAssignmentId": "direct-guided-mixed"}
        manifest["selectedAssignment"] = {
            "assignmentId": "direct-guided-mixed",
            "baseline": "direct-guided",
            "shape": "mixed",
            "status": "runnable",
            "reasons": [
                {
                    "code": "mixed-direct-guided-runnable",
                    "kind": "info",
                    "message": "Selected the mixed direct-guided fleet.",
                }
            ],
        }

        # Act
        selected_candidate = release_campaign.choose_direct_guided_projection_candidate(manifest)

        # Assert
        self.assertIsNotNone(selected_candidate)
        self.assertEqual(selected_candidate["assignmentId"], "direct-guided-mixed")
        self.assertEqual(selected_candidate["shape"], "mixed")
        self.assertEqual(selected_candidate["status"], "runnable")
        self.assertEqual(selected_candidate["reasons"][0]["code"], "mixed-direct-guided-runnable")

    def test_choose_direct_guided_projection_candidate_falls_back_to_android_only_without_ios(self) -> None:
        # Arrange
        manifest = self.build_manifest(
            android_rows=(("android-passive", "device"), ("android-sender", "device")),
            android_models={"android-passive": "Pixel 8", "android-sender": "Pixel 7"},
            ios_rows=(),
        )

        # Act
        selected_candidate = release_campaign.choose_direct_guided_projection_candidate(manifest)

        # Assert
        self.assertIsNotNone(selected_candidate)
        self.assertEqual(selected_candidate["assignmentId"], "direct-guided-android-only")
        self.assertEqual(selected_candidate["shape"], "android-only")
        self.assertEqual(selected_candidate["status"], "runnable")
        self.assertEqual(selected_candidate["reasons"][0]["code"], "android-only-direct-guided-runnable")

    def test_campaign_status_taxonomy_retains_selected_skipped_and_invalid_environment(self) -> None:
        # Arrange
        scenarios = [
            {"status": "selected"},
            {"status": "skipped"},
            {"status": "invalid-environment"},
        ]

        # Act
        aggregate_status = release_campaign.aggregate_campaign_status(scenarios)
        initial_status = release_campaign.build_initial_campaign_status(scenarios)

        # Assert
        self.assertEqual(aggregate_status, "invalid-environment")
        self.assertEqual(initial_status, "invalid-environment")

    def test_campaign_persists_standalone_provenance_before_dispatch(self) -> None:
        # Arrange
        manifest = self.build_manifest(
            android_rows=(("android-passive", "device"), ("android-sender", "device")),
            android_models={"android-passive": "Pixel 8", "android-sender": "Pixel 7"},
            ios_rows=(("iPhone 15", "iOS 18.0", "Connected", "00008110-000E196E0E92401E"),),
        )
        run_root = Path("/tmp/reference-release-campaign")

        # Act
        planned = release_campaign.plan_happy_path_campaign(manifest, run_root=run_root)
        provenance_input = dict(manifest)
        provenance_input["campaign"] = planned["campaign"]
        provenance = release_campaign.build_campaign_provenance_document(provenance_input)

        # Assert
        self.assertEqual(planned["campaign"]["campaignProvenancePath"], "campaign-provenance.json")
        self.assertEqual(provenance["provenanceVersion"], 1)
        self.assertEqual(provenance["fleetManifestPath"], "fleet-manifest.json")
        self.assertEqual(provenance["selection"]["status"], "selected")
        self.assertEqual(provenance["selectedAssignment"]["assignmentId"], "direct-guided-android-only")
        self.assertGreaterEqual(len(provenance["candidateAssignments"]), 2)
        self.assertTrue(provenance["campaignStatus"] in {"planned", "pass", "fail", "skipped", "invalid-environment"})

    def test_run_campaign_keeps_runtime_failures_as_fail_even_when_their_stderr_looks_environmental(self) -> None:
        # Arrange
        manifest = self.build_manifest(
            android_rows=(("android-passive", "device"), ("android-sender", "device")),
            android_models={"android-passive": "Pixel 8", "android-sender": "Pixel 7"},
            ios_rows=(),
        )
        process_runner = ReleaseCampaignProcessRunner(child_returncode=1, child_stderr="device offline")

        with tempfile.TemporaryDirectory() as temporary_directory:
            run_root = Path(temporary_directory) / "runtime-failure-campaign"

            # Act
            exit_code = release_campaign.run_campaign(
                run_root=run_root,
                build_manifest=lambda: manifest,
                process_runner=process_runner,
            )

            # Assert
            self.assertEqual(exit_code, release_campaign.EXIT_FAIL)
            self.assertEqual(self.load_json(run_root / "campaign-state.json")["status"], "fail")
            self.assertEqual(self.load_json(run_root / "report-data.json")["campaign"]["status"], "fail")
            self.assertTrue((run_root / "campaign-provenance.json").exists())

    def test_resolve_unused_android_serials_force_stops_non_selected_devices(self) -> None:
        # Arrange
        manifest = self.build_manifest(
            android_rows=(("android-passive", "device"), ("android-relay", "device"), ("android-spare", "device")),
            android_models={"android-passive": "Pixel 8", "android-relay": "Pixel Fold", "android-spare": "Galaxy S24"},
            ios_rows=(("iPhone 15", "iOS 18.0", "Connected", "00008110-000E196E0E92401E"),),
        )
        selected_assignment = manifest["selectedAssignment"]
        sender = reference_fleet.find_device_by_alias(manifest, selected_assignment["participants"]["sender"])
        passive = reference_fleet.find_device_by_alias(manifest, selected_assignment["participants"]["passive"])

        # Act
        extras = release_campaign.resolve_unused_available_android_serials(
            manifest,
            selected_devices=[sender, passive],
        )

        # Assert
        selected_serials = {sender["controlId"], passive["controlId"]}
        all_android_serials = [device["controlId"] for device in manifest["devices"] if device["platform"] == "android" and device["available"]]
        self.assertEqual(extras, [serial for serial in all_android_serials if serial not in selected_serials])
        self.assertEqual(len(extras), 1)
        self.assertTrue(all(serial not in selected_serials for serial in extras))
        for serial in extras:
            self.assertIn(serial, all_android_serials)

    def test_run_campaign_executes_direct_only_with_extra_devices_and_persists_artifact_links(self) -> None:
        manifest = self.build_manifest(
            android_rows=(("android-passive", "device"), ("android-relay", "device"), ("android-spare", "device")),
            android_models={"android-passive": "Pixel 8", "android-relay": "Pixel Fold", "android-spare": "Galaxy S24"},
            ios_rows=(("iPhone 15", "iOS 18.0", "Connected", "00008110-000E196E0E92401E"),),
        )
        process_runner = ReleaseCampaignProcessRunner()

        with tempfile.TemporaryDirectory() as temporary_directory:
            run_root = Path(temporary_directory) / "direct-campaign"
            exit_code = release_campaign.run_campaign(run_root=run_root, build_manifest=lambda: manifest, process_runner=process_runner)

            self.assertEqual(exit_code, release_campaign.EXIT_PASS)
            self.assertEqual([call["script_name"] for call in process_runner.calls], ["run_headless_reference_android_direct_proof.py", "analyze_reference_physical_run.py", "run_headless_reference_relay_proof.py", "analyze_reference_physical_run.py"])
            self.assertEqual([call["scenario_id"] for call in process_runner.calls], ["direct-guided", "direct-guided", "relay-constrained", "relay-constrained"])

            direct_command = process_runner.calls[0]["command"]
            selected_passive = reference_fleet.find_device_by_alias(manifest, manifest["selectedAssignment"]["participants"]["passive"])
            selected_sender = reference_fleet.find_device_by_alias(manifest, manifest["selectedAssignment"]["participants"]["sender"])
            direct_participants = self.scenario_by_id(self.load_json(run_root / "campaign-plan.json"), "direct-guided")["participants"]
            self.assertIsNotNone(selected_passive)
            self.assertIsNotNone(selected_sender)
            self.assertEqual(Path(direct_command[1]).name, "run_headless_reference_android_direct_proof.py")
            self.assertIn(selected_passive["controlId"], direct_command)
            self.assertIn(selected_sender["controlId"], direct_command)
            self.assertEqual(direct_participants["sender"]["alias"], manifest["selectedAssignment"]["participants"]["sender"])
            self.assertEqual(direct_participants["passive"]["alias"], manifest["selectedAssignment"]["participants"]["passive"])
            self.assertEqual(self.extra_force_stop_serials(direct_command), [extra for extra in release_campaign.resolve_unused_available_android_serials(manifest, selected_devices=[selected_sender, selected_passive])])

            persisted_manifest = self.load_json(run_root / "fleet-manifest.json")
            self.assertEqual(persisted_manifest["selection"]["status"], "selected")
            self.assertEqual(persisted_manifest["campaign"]["baselineExecution"]["status"], "pass")
            campaign_plan = self.load_json(run_root / "campaign-plan.json")
            direct_plan = self.scenario_by_id(campaign_plan, "direct-guided")
            self.assertEqual(campaign_plan["status"], "pass")
            self.assertEqual(direct_plan["initialStatus"], "planned")
            campaign_state = self.load_json(run_root / "campaign-state.json")
            direct_state = self.scenario_by_id(campaign_state, "direct-guided")
            self.assertEqual(direct_state["status"], "pass")
            self.assertEqual(campaign_state["happyPathGate"]["status"], "green")
            report_data = self.assert_retained_gate_policy_artifacts(run_root, expected_gate_status="green", expected_verdicts=("pass",))
            self.assertEqual(report_data["verdictCounts"], {"pass": 2, "fail": 0, "skipped": 0, "inconclusive": 0, "invalid-environment": 0})
            self.assertEqual([scenario["verdict"] for scenario in report_data["scenarios"]], ["pass", "pass"])
            self.assertTrue((run_root / "report-data.json").exists())
            self.assertTrue((run_root / "release-review-report.html").exists())
            self.assert_artifact_links(direct_state)
            self.assert_event_sequence_contains(direct_state, "scenario-initialized", "scenario-started", "scenario-runner-finished", "scenario-analysis-finished", "scenario-finished")

            report_data = self.assert_retained_gate_policy_artifacts(
                run_root,
                expected_gate_status="green",
                expected_verdicts=("pass", "pass"),
            )
            self.assertEqual(
                report_data["verdictCounts"],
                {"pass": 2, "fail": 0, "skipped": 0, "inconclusive": 0, "invalid-environment": 0},
            )
            self.assertEqual(
                [scenario["verdict"] for scenario in report_data["scenarios"]],
                ["pass", "pass"],
            )
            self.assertEqual(
                report_data["scenarios"][0]["artifacts"]["summary"],
                "baseline/direct-guided-android-only/summary.json",
            )
            self.assertEqual(
                report_data["scenarios"][0]["artifacts"]["analysisJson"],
                "baseline/direct-guided-android-only/analysis.json",
            )
            self.assertEqual(
                report_data["scenarios"][0]["artifacts"]["analysisMarkdown"],
                "baseline/direct-guided-android-only/analysis.md",
            )
            self.assertEqual(
                report_data["scenarios"][1]["artifacts"]["summary"],
                "scenarios/02-relay-constrained/summary.json",
            )
            self.assertEqual(
                report_data["scenarios"][1]["artifacts"]["analysisJson"],
                "scenarios/02-relay-constrained/analysis.json",
            )
            self.assertEqual(
                report_data["scenarios"][1]["artifacts"]["analysisMarkdown"],
                "scenarios/02-relay-constrained/analysis.md",
            )

            report_html = (run_root / "release-review-report.html").read_text(encoding="utf-8")
            self.assertIn("baseline/direct-guided-android-only/summary.json", report_html)
            self.assertIn("baseline/direct-guided-android-only/analysis.md", report_html)
            self.assertIn("scenarios/02-relay-constrained/analysis.json", report_html)

    def test_run_campaign_marks_skipped_and_never_dispatches_a_child_runner(self) -> None:
        manifest = self.build_manifest(android_rows=(("android-one", "device"),), android_models={"android-one": "Pixel 8"})

        def unexpected_process_runner(command: list[str], *, timeout_seconds: float | None = None):
            del timeout_seconds
            raise AssertionError(f"Child runner should not have been called: {command!r}")

        with tempfile.TemporaryDirectory() as temporary_directory:
            run_root = Path(temporary_directory) / "skipped-campaign"
            exit_code = release_campaign.run_campaign(run_root=run_root, build_manifest=lambda: manifest, process_runner=unexpected_process_runner)

            self.assertEqual(exit_code, release_campaign.EXIT_SKIPPED)
            persisted_manifest = self.load_json(run_root / "fleet-manifest.json")
            self.assertEqual(persisted_manifest["selection"]["status"], "skipped")
            self.assertEqual(persisted_manifest["campaign"]["baselineExecution"]["status"], "skipped")
            campaign_plan = self.load_json(run_root / "campaign-plan.json")
            direct_plan = self.scenario_by_id(campaign_plan, "direct-guided")
            self.assertEqual(direct_plan["initialStatus"], "skipped")
            self.assertEqual(campaign_plan["selectedBaseline"], None)
            campaign_state = self.load_json(run_root / "campaign-state.json")
            self.assertEqual(campaign_state["happyPathGate"]["status"], "green")
            report_data = self.assert_retained_gate_policy_artifacts(run_root, expected_gate_status="green", expected_verdicts=("skipped",))
            self.assertEqual(report_data["verdictCounts"], {"pass": 0, "fail": 0, "skipped": 2, "inconclusive": 0, "invalid-environment": 0})
            self.assertEqual([scenario["verdict"] for scenario in report_data["scenarios"]], ["skipped", "skipped"])

    def test_run_campaign_marks_invalid_environment_and_never_dispatches_a_child_runner(self) -> None:
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
            exit_code = release_campaign.run_campaign(run_root=run_root, build_manifest=lambda: manifest, process_runner=unexpected_process_runner)

            self.assertEqual(exit_code, release_campaign.EXIT_INVALID_ENVIRONMENT)
            persisted_manifest = self.load_json(run_root / "fleet-manifest.json")
            self.assertEqual(persisted_manifest["selection"]["status"], "invalid-environment")
            self.assertEqual(persisted_manifest["campaign"]["baselineExecution"]["status"], "invalid-environment")
            self.assertIn("ios-development-team-unresolved", {reason["code"] for reason in persisted_manifest["campaign"]["baselineExecution"]["reasons"]})
            campaign_state = self.load_json(run_root / "campaign-state.json")
            direct_state = self.scenario_by_id(campaign_state, "direct-guided")
            self.assertEqual(direct_state["status"], "invalid-environment")
            self.assertEqual(campaign_state["happyPathGate"]["status"], "green")
            report_data = self.assert_retained_gate_policy_artifacts(run_root, expected_gate_status="red", expected_verdicts=("invalid-environment",))
            self.assertEqual(report_data["verdictCounts"], {"pass": 0, "fail": 0, "skipped": 0, "inconclusive": 0, "invalid-environment": 2})
            self.assertEqual([scenario["verdict"] for scenario in report_data["scenarios"]], ["invalid-environment", "invalid-environment"])

    def test_run_campaign_rejects_missing_analysis_outputs_honestly(self) -> None:
        manifest = self.build_manifest(
            android_rows=(("android-passive", "device"),),
            android_models={"android-passive": "Pixel 8"},
            ios_rows=(("iPhone 15", "iOS 18.0", "Connected", "00008110-000E196E0E92401E"),),
        )
        process_runner = ReleaseCampaignProcessRunner(
            scenario_results={"direct-guided": {"analysis_writes_outputs": False}},
        )

        with tempfile.TemporaryDirectory() as temporary_directory:
            run_root = Path(temporary_directory) / "missing-analysis"
            exit_code = release_campaign.run_campaign(run_root=run_root, build_manifest=lambda: manifest, process_runner=process_runner)

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
            self.assertEqual(relay_state["status"], "skipped")
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

        stale_campaign_plan = {
            "planVersion": 999,
            "scenarioCatalogVersion": 999,
            "generatedAt": "1999-01-01T00:00:00+00:00",
            "status": "stale",
            "runRoot": "stale-run-root",
            "campaignStatePath": "stale-campaign-state.json",
            "selectedBaseline": {
                "assignmentId": "stale-assignment",
                "status": "stale",
                "artifacts": {
                    "summary": "stale/summary.json",
                },
            },
            "scenarios": [
                {
                    "scenarioId": "stale-scenario",
                    "status": "stale",
                },
            ],
        }
        stale_campaign_state = {
            "stateVersion": 999,
            "scenarioCatalogVersion": 999,
            "generatedAt": "1999-01-01T00:00:00+00:00",
            "updatedAt": "1999-01-01T00:00:01+00:00",
            "status": "stale",
            "runRoot": "stale-run-root",
            "campaignPlanPath": "stale-campaign-plan.json",
            "happyPathGate": {
                "status": "green",
                "firstFailScenarioId": None,
                "triggeredAt": None,
                "updatedAt": "1999-01-01T00:00:01+00:00",
            },
            "scenarios": [
                {
                    "scenarioId": "stale-scenario",
                    "status": "stale",
                },
            ],
        }
        stale_report_data = {
            "reportDataVersion": 999,
            "generatedAt": "1999-01-01T00:00:00+00:00",
            "runRoot": "stale-run-root",
            "sourceFiles": {
                "campaignPlan": "stale-campaign-plan.json",
                "campaignState": "stale-campaign-state.json",
            },
            "campaign": {
                "status": "stale",
                "planStatus": "stale",
                "happyPathGate": {
                    "status": "green",
                    "firstFailScenarioId": None,
                },
            },
            "runClassification": {
                "status": "complete",
                "reasons": ["stale"],
            },
            "verdictCounts": {
                "pass": 0,
                "fail": 0,
                "skipped": 0,
                "inconclusive": 0,
                "invalid-environment": 0,
            },
            "gateMath": {
                "status": "green",
                "thresholds": {
                    "failureCountMaximum": 99,
                    "inconclusiveCountMaximum": 99,
                    "invalidEnvironmentCountMaximum": 99,
                    "passRateMinimum": 0.0,
                },
                "totalScenarios": 0,
                "runnableScenarios": 0,
                "terminalScenarios": 0,
                "passRate": 1.0,
                "failureRate": 0.0,
                "inconclusiveRate": 0.0,
            },
            "scenarios": [
                {
                    "scenarioId": "stale-scenario",
                    "verdict": "pass",
                    "evidenceIssues": [],
                },
            ],
        }
        stale_html_marker = "STALE REVIEW REPORT"

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

            # Pre-seed stale retained root artifacts so the rerun must replace them semantically.
            (run_root / "campaign-plan.json").write_text(
                json.dumps(stale_campaign_plan, indent=2) + "\n",
                encoding="utf-8",
            )
            (run_root / "campaign-state.json").write_text(
                json.dumps(stale_campaign_state, indent=2) + "\n",
                encoding="utf-8",
            )
            (run_root / "report-data.json").write_text(
                json.dumps(stale_report_data, indent=2) + "\n",
                encoding="utf-8",
            )
            (run_root / "release-review-report.html").write_text(
                stale_html_marker,
                encoding="utf-8",
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
                    "run_headless_reference_android_direct_proof.py",
                    "run_headless_reference_relay_proof.py",
                    "analyze_reference_physical_run.py",
                ],
            )

            campaign_plan = self.load_json(run_root / "campaign-plan.json")
            campaign_state = self.load_json(run_root / "campaign-state.json")
            report_data = self.load_json(run_root / "report-data.json")
            report_html = (run_root / "release-review-report.html").read_text(encoding="utf-8")
            direct_plan = self.scenario_by_id(campaign_plan, "direct-guided")
            relay_plan = self.scenario_by_id(campaign_plan, "relay-constrained")
            direct_state = self.scenario_by_id(campaign_state, "direct-guided")
            relay_state = self.scenario_by_id(campaign_state, "relay-constrained")
            direct_report = self.scenario_by_id(report_data, "direct-guided")
            relay_report = self.scenario_by_id(report_data, "relay-constrained")

            self.assertNotEqual(campaign_plan["generatedAt"], stale_campaign_plan["generatedAt"])
            self.assertNotEqual(campaign_state["generatedAt"], stale_campaign_state["generatedAt"])
            self.assertNotEqual(report_data["generatedAt"], stale_report_data["generatedAt"])
            self.assertNotIn(stale_html_marker, report_html)
            self.assertEqual(campaign_plan["status"], "fail")
            self.assertEqual(campaign_plan["selectedBaseline"]["status"], "fail")
            self.assertEqual(direct_plan["initialStatus"], "planned")
            self.assertEqual(relay_plan["initialStatus"], "planned")
            self.assertEqual(direct_state["status"], "fail")
            self.assertEqual(campaign_state["happyPathGate"]["status"], "red")
            self.assertEqual(campaign_state["happyPathGate"]["firstFailScenarioId"], "direct-guided")
            self.assertIn("baseline-analysis-missing", {reason["code"] for reason in direct_state["reasons"]})
            self.assertEqual(report_data["verdictCounts"], {"pass": 0, "fail": 1, "skipped": 1, "inconclusive": 0, "invalid-environment": 0})
            self.assertEqual(report_data["gateMath"]["status"], "red")
            self.assertEqual([scenario["verdict"] for scenario in report_data["scenarios"]], ["fail", "skipped"])
            self.assertIn("summary-missing", direct_report["evidenceIssues"])
            self.assertIn("analysis-json-missing", direct_report["evidenceIssues"])
            self.assertIn("analysis-markdown-missing", direct_report["evidenceIssues"])
            self.assertEqual(relay_report["verdict"], "skipped")
            self.assertEqual(relay_report["analysisStatus"], "pass")
            self.assertEqual(relay_report["completeEvidence"], True)
            self.assertIn(
                "baseline-summary-missing",
                {reason["code"] for reason in direct_state["reasons"]},
            )
            self.assertIn(
                "baseline-analysis-missing",
                {reason["code"] for reason in direct_state["reasons"]},
            )
            self.assertEqual(
                self.assert_retained_gate_policy_artifacts(
                    run_root,
                    expected_gate_status="red",
                    expected_verdicts=("fail", "skipped"),
                )["gateMath"]["status"],
                "red",
            )

    def test_run_campaign_classifies_environmental_child_failures_honestly(self) -> None:
        manifest = self.build_manifest(
            android_rows=(("android-passive", "device"),),
            android_models={"android-passive": "Pixel 8"},
            ios_rows=(("iPhone 15", "iOS 18.0", "Connected", "00008110-000E196E0E92401E"),),
        )
        process_runner = ReleaseCampaignProcessRunner(
            scenario_results={
                "direct-guided": {
                    "child_returncode": 7,
                    "child_stderr": "DEVELOPMENT_TEAM is required unless local provisioning exists",
                    "analysis_payload": {"status": "fail", "scenario_type": "relay"},
                }
            }
        )

        with tempfile.TemporaryDirectory() as temporary_directory:
            run_root = Path(temporary_directory) / "environment-failure"
            exit_code = release_campaign.run_campaign(run_root=run_root, build_manifest=lambda: manifest, process_runner=process_runner)

            self.assertEqual(exit_code, release_campaign.EXIT_FAIL)
            campaign_state = self.load_json(run_root / "campaign-state.json")
            direct_state = self.scenario_by_id(campaign_state, "direct-guided")
            self.assertEqual(direct_state["status"], "fail")
            self.assertEqual(direct_state["childExitCode"], 7)
            self.assertIn("baseline-runner-failed", {reason["code"] for reason in direct_state["reasons"]})
            self.assertEqual(campaign_state["happyPathGate"]["status"], "red")
            self.assertEqual(campaign_state["happyPathGate"]["firstFailScenarioId"], "direct-guided")
            report_data = self.assert_retained_gate_policy_artifacts(
                run_root,
                expected_gate_status="red",
                expected_verdicts=("fail", "skipped"),
                unexpected_verdicts=("invalid-environment",),
            )
            self.assertEqual(
                report_data["verdictCounts"],
                {"pass": 0, "fail": 1, "skipped": 1, "inconclusive": 0, "invalid-environment": 0},
            )
            self.assertEqual([scenario["verdict"] for scenario in report_data["scenarios"]], ["fail", "skipped"])

    def test_run_campaign_classifies_environmental_analyzer_failures_honestly(self) -> None:
        manifest = self.build_manifest(
            android_rows=(("android-passive", "device"),),
            android_models={"android-passive": "Pixel 8"},
            ios_rows=(("iPhone 15", "iOS 18.0", "Connected", "00008110-000E196E0E92401E"),),
        )
        process_runner = ReleaseCampaignProcessRunner(
            scenario_results={
                "direct-guided": {
                    "analysis_returncode": 4,
                    "analysis_stderr": "xcodebuild tool not ready",
                    "analysis_payload": {"status": "fail", "scenario_type": "relay"},
                }
            }
        )

        with tempfile.TemporaryDirectory() as temporary_directory:
            run_root = Path(temporary_directory) / "analysis-environment-failure"
            exit_code = release_campaign.run_campaign(run_root=run_root, build_manifest=lambda: manifest, process_runner=process_runner)

            self.assertEqual(exit_code, release_campaign.EXIT_FAIL)
            campaign_state = self.load_json(run_root / "campaign-state.json")
            direct_state = self.scenario_by_id(campaign_state, "direct-guided")
            self.assertEqual(direct_state["status"], "fail")
            self.assertEqual(direct_state["analysisExitCode"], 4)
            self.assertIn("baseline-analysis-failed", {reason["code"] for reason in direct_state["reasons"]})
            self.assertEqual(campaign_state["happyPathGate"]["status"], "red")
            self.assertEqual(campaign_state["happyPathGate"]["firstFailScenarioId"], "direct-guided")
            report_data = self.assert_retained_gate_policy_artifacts(run_root, expected_gate_status="red", expected_verdicts=("fail", "skipped"))
            self.assertEqual(report_data["verdictCounts"], {"pass": 0, "fail": 1, "skipped": 1, "inconclusive": 0, "invalid-environment": 0})
            self.assertEqual([scenario["verdict"] for scenario in report_data["scenarios"]], ["fail", "skipped"])
            self.assertEqual(campaign_state["happyPathGate"]["firstFailScenarioId"], "direct-guided")
            report_data = self.assert_retained_gate_policy_artifacts(
                run_root,
                expected_gate_status="red",
                expected_verdicts=("fail", "skipped"),
                unexpected_verdicts=("invalid-environment",),
            )
            self.assertEqual(
                report_data["verdictCounts"],
                {"pass": 0, "fail": 1, "skipped": 1, "inconclusive": 0, "invalid-environment": 0},
            )
            self.assertEqual([scenario["verdict"] for scenario in report_data["scenarios"]], ["fail", "skipped"])
            self.assertTrue((run_root / "report-data.json").exists())

    def test_run_campaign_recognizes_explicit_invalid_environment_sentinels(self) -> None:
        manifest = self.build_manifest(
            android_rows=(("android-passive", "device"),),
            android_models={"android-passive": "Pixel 8"},
            ios_rows=(("iPhone 15", "iOS 18.0", "Connected", "00008110-000E196E0E92401E"),),
        )
        process_runner = ReleaseCampaignProcessRunner(
            scenario_results={
                "direct-guided": {
                    "analysis_returncode": 4,
                    "analysis_stderr": "environment-sentinel=invalid",
                    "analysis_writes_outputs": False,
                }
            }
        )

        with tempfile.TemporaryDirectory() as temporary_directory:
            run_root = Path(temporary_directory) / "analysis-explicit-environment-sentinel"
            exit_code = release_campaign.run_campaign(run_root=run_root, build_manifest=lambda: manifest, process_runner=process_runner)

            self.assertEqual(exit_code, release_campaign.EXIT_INVALID_ENVIRONMENT)
            campaign_state = self.load_json(run_root / "campaign-state.json")
            direct_state = self.scenario_by_id(campaign_state, "direct-guided")
            self.assertEqual(direct_state["status"], "invalid-environment")
            self.assertIn("baseline-analysis-invalid-environment", {reason["code"] for reason in direct_state["reasons"]})
            report_data = self.assert_retained_gate_policy_artifacts(run_root, expected_gate_status="red", expected_verdicts=("invalid-environment",))
            self.assertEqual(report_data["verdictCounts"], {"pass": 0, "fail": 0, "skipped": 1, "inconclusive": 0, "invalid-environment": 1})
            self.assertEqual([scenario["verdict"] for scenario in report_data["scenarios"]], ["invalid-environment", "skipped"])
        sentinel_cases = (
            ("analysis_stdout", "environment-sentinel=invalid from stdout"),
            ("analysis_stderr", "environment-sentinel=invalid from stderr"),
            ("analysis_error", "environment-sentinel=invalid from error"),
        )

        for field_name, sentinel_value in sentinel_cases:
            with self.subTest(field_name=field_name):
                process_runner = ReleaseCampaignProcessRunner(
                    scenario_results={
                        "relay-constrained": {
                            "analysis_returncode": 4,
                            field_name: sentinel_value,
                        }
                    }
                )

                with tempfile.TemporaryDirectory() as temporary_directory:
                    run_root = Path(temporary_directory) / f"analysis-explicit-environment-sentinel-{field_name}"

                    # Act
                    exit_code = release_campaign.run_campaign(
                        run_root=run_root,
                        build_manifest=lambda: manifest,
                        process_runner=process_runner,
                    )

                    # Assert
                    self.assertEqual(exit_code, release_campaign.EXIT_PASS)
                    campaign_state = self.load_json(run_root / "campaign-state.json")
                    relay_state = self.scenario_by_id(campaign_state, "relay-constrained")
                    report_data = self.load_json(run_root / "report-data.json")
                    self.assertEqual(relay_state["status"], "skipped")
                    self.assertIn(
                        "relay-constrained-analysis-invalid-environment",
                        {reason["code"] for reason in relay_state["reasons"]},
                    )
                    report_data = self.assert_retained_gate_policy_artifacts(
                        run_root,
                        expected_gate_status="red",
                        expected_verdicts=("pass", "invalid-environment"),
                        unexpected_verdicts=("fail",),
                    )
                    self.assertEqual(
                        report_data["verdictCounts"],
                        {"pass": 1, "fail": 0, "skipped": 0, "inconclusive": 0, "invalid-environment": 1},
                    )
                    self.assertEqual(report_data["gateMath"]["status"], "red")
                    self.assertEqual([scenario["verdict"] for scenario in report_data["scenarios"]], ["pass", "invalid-environment"])
                    self.assertTrue((run_root / "report-data.json").exists())


if __name__ == "__main__":
    unittest.main()
