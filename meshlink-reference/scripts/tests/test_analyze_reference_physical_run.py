from __future__ import annotations

import json
import shutil
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path
from unittest.mock import patch

from support import SCRIPTS_DIR

if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import analyze_reference_physical_run as retained_analysis  # noqa: E402


FIXTURES_DIR = Path(__file__).with_name("fixtures")
ANDROID_DIRECT_FIXTURE_DIR = FIXTURES_DIR / "android_direct_run"


class AnalyzeReferencePhysicalRunTests(unittest.TestCase):
    def run_cli(self, run_dir: Path, *extra_args: str) -> tuple[int, dict[str, object], str]:
        argv = [
            "analyze_reference_physical_run.py",
            "--run-dir",
            str(run_dir),
            *extra_args,
        ]
        with patch.object(sys, "argv", argv):
            exit_code = retained_analysis.main()
        analysis = json.loads((run_dir / "analysis.json").read_text(encoding="utf-8"))
        markdown = (run_dir / "analysis.md").read_text(encoding="utf-8")
        return exit_code, analysis, markdown

    def copy_fixture(self, fixture_dir: Path, destination: Path) -> None:
        shutil.copytree(fixture_dir, destination, dirs_exist_ok=True)

    def write_text(self, path: Path, content: str) -> None:
        path.write_text(textwrap.dedent(content).lstrip(), encoding="utf-8")

    def seed_mixed_direct_run(self, run_dir: Path) -> None:
        self.write_text(
            run_dir / "summary.json",
            """
            {
              "scenario": "direct-guided",
              "android_completion": "05-31 10:00:01.600 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION proof.complete role=passive inboundCount=1 largestInboundBytes=32 export=exports/session-redacted.json",
              "ios_completion": "2026-05-31 10:00:02.000 REFERENCE_AUTOMATION proof.complete role=sender outcome=SendResult.Sent peer=peer-123 delivery=DELIVERY_SUCCEEDED @ delivery.send {routeIsDirect=true}",
              "export_relative_path": "exports/session-redacted.json"
            }
            """,
        )
        self.write_text(
            run_dir / "iphone_console.log",
            """
            2026-05-31 10:00:00.000 REFERENCE_AUTOMATION started mode=LIVE_PROOF role=SENDER scenario=direct-guided
            2026-05-31 10:00:00.300 REFERENCE_AUTOMATION peer.discovered role=SENDER peer=peer-123
            2026-05-31 10:00:00.500 REFERENCE_AUTOMATION send.requested role=sender phase=primary payload=guided-hello bytes=32
            2026-05-31 10:00:02.000 REFERENCE_AUTOMATION proof.complete role=sender outcome=SendResult.Sent peer=peer-123 delivery=DELIVERY_SUCCEEDED @ delivery.send {routeIsDirect=true}
            retaining peer because GATT side link is still active
            GATT side link is still active
            """,
        )
        self.write_text(
            run_dir / "android_logcat.log",
            """
            05-31 10:00:00.000 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION started mode=LIVE_PROOF role=PASSIVE scenario=direct-guided
            05-31 10:00:00.200 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION peer.discovered role=PASSIVE peer=peer-123
            05-31 10:00:01.400 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION session.end.requested role=passive
            05-31 10:00:01.500 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION export.requested role=passive policy=redacted-preview
            05-31 10:00:01.600 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION proof.complete role=passive inboundCount=1 largestInboundBytes=32 export=exports/session-redacted.json
            """,
        )
        self.write_text(run_dir / "android_history.json", '{"historyStatus": "RETAINED"}\n')
        self.write_text(
            run_dir / "android_export.json",
            '{"defaultMode": "redacted-preview", "fullPayloadIncluded": false, "operatorOptInRecorded": false}\n',
        )

    def seed_relay_run(self, run_dir: Path) -> None:
        self.write_text(
            run_dir / "summary.json",
            """
            {
              "scenario": "relay-constrained",
              "export_relative_path": "exports/session-redacted.json"
            }
            """,
        )
        self.write_text(
            run_dir / "iphone_console.log",
            """
            2026-05-31 10:00:00.000 REFERENCE_AUTOMATION bootstrap.requested role=sender peer=relay-peer targetPeerId=auto
            2026-05-31 10:00:02.000 REFERENCE_AUTOMATION proof.complete role=sender outcome=SendResult.Sent peer=passive-peer delivery=DELIVERY_SUCCEEDED @ delivery.send {routeIsDirect=false}
            ROUTE_RETRACTED @ routing.routeAvailable {peerId=passive-peer,routeAvailable=false}
            """,
        )
        self.write_text(
            run_dir / "passive_logcat.log",
            """
            05-31 10:00:00.000 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION started mode=LIVE_PROOF role=PASSIVE scenario=relay-constrained
            05-31 10:00:00.200 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION peer.discovered role=PASSIVE peer=relay-peer
            05-31 10:00:01.600 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION proof.complete role=passive inboundCount=1 export=exports/session-redacted.json
            ROUTE_EXPIRED @ routing.routeAvailable {peerId=sender-peer,routeAvailable=false}
            """,
        )
        self.write_text(
            run_dir / "relay_logcat.log",
            """
            05-31 10:00:00.000 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION started mode=LIVE_PROOF role=RELAY scenario=relay-constrained
            promoted temporary peer bt-1234 to canonical peer passive-peer
            forward.message.queued peer=passive-peer
            forward.message.delivered peer=passive-peer
            """,
        )

    def test_main_writes_analysis_for_android_sender_direct_run(self) -> None:
        # Arrange
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory) / "android-direct"
            self.copy_fixture(ANDROID_DIRECT_FIXTURE_DIR, run_dir)

            # Act
            exit_code, analysis, markdown = self.run_cli(run_dir)

            # Assert
            self.assertEqual(exit_code, 0)
            self.assertEqual(analysis["scenario_type"], "direct")
            self.assertEqual(analysis["status"], "pass")
            self.assertEqual(analysis["artifacts"]["export_relative_path"], "exports/session-redacted.json")
            self.assertEqual(analysis["artifacts"]["sender_log_artifact"], "sender_logcat.log")
            self.assertEqual(analysis["artifacts"]["passive_log_artifact"], "passive_logcat.log")
            self.assertEqual(analysis["artifacts"]["sender_platform"], "android")
            self.assertTrue(analysis["invariants"]["sender_proof_complete"])
            self.assertTrue(analysis["invariants"]["passive_proof_complete"])
            self.assertIn("sender_logcat.log", markdown)
            self.assertTrue((run_dir / "analysis.json").exists())
            self.assertTrue((run_dir / "analysis.md").exists())

    def test_main_keeps_mixed_direct_runs_green(self) -> None:
        # Arrange
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory) / "mixed-direct"
            run_dir.mkdir(parents=True, exist_ok=True)
            self.seed_mixed_direct_run(run_dir)

            # Act
            exit_code, analysis, markdown = self.run_cli(run_dir)

            # Assert
            self.assertEqual(exit_code, 0)
            self.assertEqual(analysis["scenario_type"], "direct")
            self.assertEqual(analysis["status"], "pass")
            self.assertEqual(analysis["artifacts"]["sender_platform"], "ios")
            self.assertEqual(analysis["artifacts"]["sender_launch_mode"], "devicectl")
            self.assertEqual(analysis["artifacts"]["passive_log_artifact"], "android_logcat.log")
            self.assertTrue(analysis["invariants"]["ios_gatt_side_link_retained_after_l2cap_close"])
            self.assertIn("direct-guided", markdown)

    def test_main_auto_detects_relay_runs_from_android_role_logs(self) -> None:
        # Arrange
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory) / "relay-run"
            run_dir.mkdir(parents=True, exist_ok=True)
            self.seed_relay_run(run_dir)

            # Act
            exit_code, analysis, markdown = self.run_cli(run_dir)

            # Assert
            self.assertEqual(exit_code, 0)
            self.assertEqual(analysis["scenario_type"], "relay")
            self.assertEqual(analysis["status"], "pass")
            self.assertEqual(analysis["artifacts"]["relay_log_label"], "relay")
            self.assertIn("routeIsDirect=false", markdown)

    def test_main_fails_when_android_sender_log_is_missing(self) -> None:
        # Arrange
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory) / "missing-sender-log"
            self.copy_fixture(ANDROID_DIRECT_FIXTURE_DIR, run_dir)
            (run_dir / "sender_logcat.log").unlink()

            # Act
            exit_code, analysis, markdown = self.run_cli(run_dir)

            # Assert
            self.assertEqual(exit_code, 0)
            self.assertEqual(analysis["status"], "fail")
            self.assertFalse(analysis["invariants"]["sender_log_captured"])
            self.assertIn("sender_logcat.log", "\n".join(analysis["recommendations"]))
            self.assertIn("sender_logcat.log", markdown)

    def test_main_falls_back_to_logs_when_summary_json_is_malformed(self) -> None:
        # Arrange
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory) / "malformed-summary"
            self.copy_fixture(ANDROID_DIRECT_FIXTURE_DIR, run_dir)
            (run_dir / "summary.json").write_text("{not valid json", encoding="utf-8")

            # Act
            exit_code, analysis, markdown = self.run_cli(run_dir)

            # Assert
            self.assertEqual(exit_code, 0)
            self.assertEqual(analysis["status"], "pass")
            self.assertFalse(analysis["artifacts"]["analysis_based_on_summary_json"])
            self.assertEqual(analysis["artifacts"]["export_relative_path"], "exports/session-redacted.json")
            self.assertIn("summary.json", "\n".join(analysis["recommendations"]))
            self.assertIn("summary.json", markdown)

    def test_main_fails_when_summary_sender_platform_contradicts_android_logs(self) -> None:
        # Arrange
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory) / "contradictory-summary"
            self.copy_fixture(ANDROID_DIRECT_FIXTURE_DIR, run_dir)
            summary = json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))
            summary["senderPlatform"] = "ios"
            (run_dir / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")

            # Act
            exit_code, analysis, markdown = self.run_cli(run_dir)

            # Assert
            self.assertEqual(exit_code, 0)
            self.assertEqual(analysis["status"], "fail")
            self.assertFalse(analysis["invariants"]["summary_role_metadata_consistent"])
            self.assertIn("senderPlatform=ios", "\n".join(analysis["recommendations"]))
            self.assertIn("senderPlatform=ios", markdown)

    def test_main_fails_when_sender_reports_failed_or_routed_direct_run(self) -> None:
        # Arrange
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory) / "sender-failed"
            self.copy_fixture(ANDROID_DIRECT_FIXTURE_DIR, run_dir)
            (run_dir / "sender_logcat.log").write_text(
                textwrap.dedent(
                    """
                    05-31 10:00:01.000 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION started mode=LIVE_PROOF role=SENDER scenario=direct-guided
                    05-31 10:00:01.100 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION peer.discovered role=SENDER peer=passive-peer
                    05-31 10:00:01.200 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION send.requested role=sender phase=primary payload=guided-hello bytes=32
                    05-31 10:00:01.300 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION proof.failed role=sender reason=noSession
                    05-31 10:00:01.400 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION proof.complete role=sender deliveries=1 routeIsDirect=false
                    """
                ).lstrip(),
                encoding="utf-8",
            )

            # Act
            exit_code, analysis, markdown = self.run_cli(run_dir)

            # Assert
            self.assertEqual(exit_code, 0)
            self.assertEqual(analysis["status"], "fail")
            self.assertTrue(analysis["invariants"]["sender_proof_failed"])
            self.assertTrue(analysis["invariants"]["unexpected_routed_delivery"])
            self.assertIn("routeIsDirect=false", "\n".join(analysis["recommendations"]))
            self.assertIn("proof.failed", markdown)

    def test_main_fails_when_passive_never_completes(self) -> None:
        # Arrange
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory) / "passive-missing"
            self.copy_fixture(ANDROID_DIRECT_FIXTURE_DIR, run_dir)
            (run_dir / "passive_logcat.log").write_text(
                textwrap.dedent(
                    """
                    05-31 10:00:00.000 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION started mode=LIVE_PROOF role=PASSIVE scenario=direct-guided
                    05-31 10:00:00.100 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION peer.discovered role=PASSIVE peer=sender-peer
                    05-31 10:00:01.500 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION export.requested role=passive policy=redacted-preview
                    """
                ).lstrip(),
                encoding="utf-8",
            )

            # Act
            exit_code, analysis, markdown = self.run_cli(run_dir)

            # Assert
            self.assertEqual(exit_code, 0)
            self.assertEqual(analysis["status"], "fail")
            self.assertFalse(analysis["invariants"]["passive_proof_complete"])
            self.assertIn("never retained/exported the session", "\n".join(analysis["recommendations"]))
            self.assertIn("passive_proof_complete", markdown)


if __name__ == "__main__":
    unittest.main()
