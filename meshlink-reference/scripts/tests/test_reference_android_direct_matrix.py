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

import run_headless_reference_android_direct_matrix as android_direct_matrix  # noqa: E402


class AndroidDirectMatrixScriptTests(unittest.TestCase):
    def test_parse_args_defaults_to_resume_disabled(self) -> None:
        # Arrange / Act
        args = android_direct_matrix.parse_args([])

        # Assert
        self.assertFalse(args.resume)
        self.assertEqual(args.android_ready_seconds, android_direct_matrix.DEFAULT_ANDROID_READY_SECONDS)
        self.assertEqual(args.capture_timeout_seconds, android_direct_matrix.DEFAULT_CAPTURE_TIMEOUT_SECONDS)

    def test_run_pair_reports_timeout_without_blocking_following_pairs(self) -> None:
        # Arrange
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory) / "pair"

            with patch.object(
                android_direct_matrix.subprocess,
                "run",
                side_effect=subprocess.TimeoutExpired(cmd=["python"], timeout=1.5, output="install boom", stderr="stderr tail"),
            ):
                # Act
                result = android_direct_matrix.run_pair(
                    sender="1f1dad34",
                    passive="2ASVB21B09005117",
                    app_id="demo.meshlink.reference.android-direct.a065_nam_lx9",
                    run_dir=run_dir,
                    target_peer_id=None,
                    capture_timeout=30.0,
                    android_ready_seconds=6.0,
                    pair_timeout_seconds=1.5,
                    skip_install=False,
                )

            # Assert
            self.assertEqual(result["status"], "failed")
            self.assertEqual(result["failureStage"], "preflight")
            self.assertTrue(result["timedOut"])
            self.assertEqual(result["exitCode"], 124)
            self.assertEqual(result["timings"]["pairTimeoutSeconds"], 1.5)
            self.assertIn("install boom", result["stdoutTail"])

    def test_main_writes_progress_and_skips_completed_pairs_on_resume(self) -> None:
        # Arrange
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_root = Path(temporary_directory) / "matrix"
            progress_path = run_root / "progress.json"
            completed_row = {
                "label": "a065_nam_lx9",
                "sender": "1f1dad34",
                "passive": "2ASVB21B09005117",
                "senderModel": "A065",
                "passiveModel": "NAM-LX9",
                "appId": "demo.meshlink.reference.android-direct.a065_nam_lx9",
                "targetPeerId": "peer-123",
                "initial": {"status": "failed", "failureStage": "capture", "failureReason": "route"},
                "final": {"status": "passed", "failureStage": None, "failureReason": None},
                "initialRunDir": str(run_root / "01_a065_nam_lx9_initial"),
                "finalRunDir": str(run_root / "01_a065_nam_lx9_final"),
            }
            run_root.mkdir(parents=True, exist_ok=True)
            progress_path.write_text(json.dumps([completed_row], indent=2), encoding="utf-8")

            run_calls: list[dict[str, str | None]] = []
            peer_reads: list[str] = []

            def fake_adb_devices() -> list[str]:
                return ["1f1dad34", "2ASVB21B09005117"]

            def fake_run_pair(**kwargs):
                run_calls.append(
                    {
                        "sender": kwargs["sender"],
                        "passive": kwargs["passive"],
                        "skip_install": str(kwargs["skip_install"]),
                        "target_peer_id": kwargs["target_peer_id"],
                    }
                )
                if kwargs["skip_install"]:
                    return {
                        "status": "passed",
                        "failureStage": None,
                        "failureReason": None,
                        "routeStage": "route-discovered",
                        "routeEvidence": "evidence",
                        "senderRouteStage": "route-discovered",
                        "passiveRouteStage": "route-discovered",
                        "timings": {"totalSeconds": 1.0},
                        "htmlReportPath": "summary.html",
                        "stdoutTail": "",
                        "stderrTail": "",
                        "elapsedSeconds": 1.0,
                        "exitCode": 0,
                    }
                return {
                    "status": "failed",
                    "failureStage": "capture",
                    "failureReason": "route",
                    "routeStage": "peer-discovered",
                    "routeEvidence": "evidence",
                    "senderRouteStage": "peer-discovered",
                    "passiveRouteStage": "peer-discovered",
                    "timings": {"totalSeconds": 1.0},
                    "htmlReportPath": "summary.html",
                    "stdoutTail": "",
                    "stderrTail": "",
                    "elapsedSeconds": 1.0,
                    "exitCode": 1,
                }

            def fake_read_passive_peer_id(serial: str, app_id: str, retries: int = 60, delay_s: float = 1.0) -> str:
                del retries, delay_s
                peer_reads.append(f"{serial}:{app_id}")
                return "peer-123"

            # Act
            with patch.object(android_direct_matrix, "adb_devices", side_effect=fake_adb_devices), patch.object(
                android_direct_matrix, "adb_device_api_level", side_effect=lambda serial: {"1f1dad34": 36, "2ASVB21B09005117": 36}.get(serial)
            ), patch.object(android_direct_matrix, "run_pair", side_effect=fake_run_pair), patch.object(
                android_direct_matrix, "read_passive_peer_id", side_effect=fake_read_passive_peer_id
            ):
                exit_code = android_direct_matrix.main(["--run-root", str(run_root), "--resume"])

            # Assert
            self.assertEqual(exit_code, 0)
            rows = json.loads((run_root / "matrix-results.json").read_text(encoding="utf-8"))
            self.assertEqual(len(rows), 2)
            self.assertEqual(rows[0]["label"], "a065_nam_lx9")
            self.assertEqual(rows[0]["final"]["status"], "passed")
            self.assertEqual(rows[1]["label"], "nam_lx9_a065")
            self.assertEqual(rows[1]["initial"]["status"], "failed")
            self.assertEqual(rows[1]["final"]["status"], "skipped")
            self.assertEqual(peer_reads, [])
            self.assertEqual(
                run_calls,
                [
                    {"sender": "2ASVB21B09005117", "passive": "1f1dad34", "skip_install": "False", "target_peer_id": None},
                ],
            )

    def test_main_writes_fleet_inventory_and_pair_report_with_fail_fast(self) -> None:
        # Arrange
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_root = Path(temporary_directory) / "matrix"
            run_root.mkdir(parents=True, exist_ok=True)
            calls: list[tuple[str, str, bool]] = []

            def fake_adb_devices() -> list[str]:
                return ["1f1dad34", "2ASVB21B09005117", "42004386e43c8589"]

            def fake_run_pair(**kwargs):
                calls.append((kwargs["sender"], kwargs["passive"], kwargs["skip_install"]))
                if kwargs["skip_install"]:
                    return {
                        "status": "failed",
                        "failureStage": "capture",
                        "failureReason": "route stalled",
                        "senderCompletion": "sender complete",
                        "passiveCompletion": None,
                        "routeStage": "capture-stalled",
                        "routeEvidence": "sender_logcat.log: route stalled",
                        "senderRouteStage": "capture-stalled",
                        "passiveRouteStage": None,
                        "startupTiming": {"sender": {"startupWaitSeconds": 3.0}, "passive": {"startupWaitSeconds": 4.0}},
                        "timings": {"totalSeconds": 12.5, "transportMode": "meshlink"},
                        "htmlReportPath": "summary.html",
                        "exportRelativePath": "android_export.json",
                        "evidence": {
                            "senderLogcat": "sender_logcat.log",
                            "passiveLogcat": "passive_logcat.log",
                            "senderStart": "sender_start.txt",
                            "passiveStart": "passive_start.txt",
                            "androidHistory": "android_history.json",
                            "androidExport": "android_export.json",
                        },
                        "captured": {
                            "senderLogcat": True,
                            "passiveLogcat": False,
                            "senderStart": True,
                            "passiveStart": True,
                            "androidHistory": True,
                            "androidExport": False,
                        },
                        "summaryPath": str(run_root / "01_a065_nam_lx9_initial" / "summary.json"),
                        "runDir": str(run_root / "01_a065_nam_lx9_initial"),
                        "stdoutTail": "",
                        "stderrTail": "",
                        "elapsedSeconds": 12.5,
                        "exitCode": 1,
                    }
                return {
                    "status": "passed",
                    "failureStage": None,
                    "failureReason": None,
                    "senderCompletion": "sender complete",
                    "passiveCompletion": "passive complete",
                    "routeStage": "route-discovered",
                    "routeEvidence": "passive_logcat.log: route discovered",
                    "senderRouteStage": "route-discovered",
                    "passiveRouteStage": "route-discovered",
                    "startupTiming": {"sender": {"startupWaitSeconds": 2.0}, "passive": {"startupWaitSeconds": 2.5}},
                    "timings": {"totalSeconds": 4.2, "transportMode": "meshlink"},
                    "htmlReportPath": "summary.html",
                    "exportRelativePath": "android_export.json",
                    "evidence": {
                        "senderLogcat": "sender_logcat.log",
                        "passiveLogcat": "passive_logcat.log",
                        "senderStart": "sender_start.txt",
                        "passiveStart": "passive_start.txt",
                        "androidHistory": "android_history.json",
                        "androidExport": "android_export.json",
                    },
                    "captured": {
                        "senderLogcat": True,
                        "passiveLogcat": True,
                        "senderStart": True,
                        "passiveStart": True,
                        "androidHistory": True,
                        "androidExport": True,
                    },
                    "summaryPath": str(run_root / "01_a065_nam_lx9_final" / "summary.json"),
                    "runDir": str(run_root / "01_a065_nam_lx9_final"),
                    "stdoutTail": "",
                    "stderrTail": "",
                    "elapsedSeconds": 4.2,
                    "exitCode": 0,
                }

            def fake_read_passive_peer_id(serial: str, app_id: str, retries: int = 60, delay_s: float = 1.0) -> str:
                del serial, app_id, retries, delay_s
                return "peer-123"

            # Act
            with patch.object(android_direct_matrix, "adb_devices", side_effect=fake_adb_devices), patch.object(
                android_direct_matrix,
                "adb_device_api_level",
                side_effect=lambda serial: {"1f1dad34": 36, "2ASVB21B09005117": 36, "42004386e43c8589": 36}.get(serial),
            ), patch.object(android_direct_matrix, "run_pair", side_effect=fake_run_pair), patch.object(
                android_direct_matrix, "read_passive_peer_id", side_effect=fake_read_passive_peer_id
            ):
                android_direct_matrix.main(["--run-root", str(run_root)])

            # Assert
            fleet_json = json.loads((run_root / "fleet.json").read_text(encoding="utf-8"))
            self.assertTrue(fleet_json["failFast"])
            self.assertEqual(fleet_json["deviceCount"], 3)
            self.assertEqual([device["serial"] for device in fleet_json["devices"]], ["1f1dad34", "2ASVB21B09005117", "42004386e43c8589"])
            report_text = (run_root / "01_a065_nam_lx9_report.md").read_text(encoding="utf-8")
            self.assertTrue((run_root / "01_a065_nam_lx9_report.md").exists())
            self.assertIn("sequenceDiagram", report_text)
            self.assertIn("fail-fast stop after final failure", report_text)
            self.assertIn("sender_logcat.log", report_text)
            self.assertIn("passive_logcat.log", report_text)
            self.assertIn("summary.json", report_text)
            self.assertIn("Startup timing", report_text)
            self.assertIn("Captured evidence map", report_text)
            results = json.loads((run_root / "matrix-results.json").read_text(encoding="utf-8"))
            self.assertEqual(len(results), 1)
            self.assertEqual(results[0]["final"]["status"], "failed")
            state = json.loads((run_root / "state.json").read_text(encoding="utf-8"))
            self.assertTrue(state["stoppedEarly"])
            self.assertIn("failed during", state["stopReason"])
            self.assertEqual(calls, [("1f1dad34", "2ASVB21B09005117", False), ("1f1dad34", "2ASVB21B09005117", True)])

    def test_main_runs_all_available_directed_pairs_when_not_resuming(self) -> None:
        # Arrange
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_root = Path(temporary_directory) / "matrix"
            run_root.mkdir(parents=True, exist_ok=True)
            calls: list[tuple[str, str, bool, str]] = []

            def fake_adb_devices() -> list[str]:
                return ["1f1dad34", "2ASVB21B09005117"]

            def fake_run_pair(**kwargs):
                calls.append((kwargs["sender"], kwargs["passive"], kwargs["skip_install"], kwargs["passive_benchmark_transport"]))
                return {
                    "status": "passed",
                    "failureStage": None,
                    "failureReason": None,
                    "routeStage": "route-discovered",
                    "routeEvidence": "evidence",
                    "senderRouteStage": "route-discovered",
                    "passiveRouteStage": "route-discovered",
                    "timings": {"totalSeconds": 1.0},
                    "htmlReportPath": "summary.html",
                    "stdoutTail": "",
                    "stderrTail": "",
                    "elapsedSeconds": 1.0,
                    "exitCode": 0,
                }

            def fake_read_passive_peer_id(serial: str, app_id: str, retries: int = 60, delay_s: float = 1.0) -> str:
                del serial, app_id, retries, delay_s
                return "peer-123"

            # Act
            with patch.object(android_direct_matrix, "adb_devices", side_effect=fake_adb_devices), patch.object(
                android_direct_matrix, "adb_device_api_level", side_effect=lambda serial: {"1f1dad34": 36, "2ASVB21B09005117": 36}.get(serial)
            ), patch.object(android_direct_matrix, "run_pair", side_effect=fake_run_pair), patch.object(
                android_direct_matrix, "read_passive_peer_id", side_effect=fake_read_passive_peer_id
            ):
                android_direct_matrix.main(["--run-root", str(run_root), "--sender-passive-limit", "1"])

            # Assert
            results = json.loads((run_root / "matrix-results.json").read_text(encoding="utf-8"))
            self.assertEqual(len(results), 1)
            self.assertEqual(calls, [("1f1dad34", "2ASVB21B09005117", False, "meshlink"), ("1f1dad34", "2ASVB21B09005117", True, "meshlink")])

    def test_main_filters_pairs_below_api_floor(self) -> None:
        # Arrange
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_root = Path(temporary_directory) / "matrix"
            run_root.mkdir(parents=True, exist_ok=True)
            calls: list[tuple[str, str, bool, str]] = []

            def fake_adb_devices() -> list[str]:
                return ["1f1dad34", "2ASVB21B09005117", "42004386e43c8589"]

            def fake_api_level(serial: str) -> int | None:
                return {
                    "1f1dad34": 36,
                    "2ASVB21B09005117": 36,
                    "42004386e43c8589": 28,
                }.get(serial)

            def fake_run_pair(**kwargs):
                calls.append((kwargs["sender"], kwargs["passive"], kwargs["skip_install"], kwargs["passive_benchmark_transport"]))
                return {
                    "status": "passed",
                    "failureStage": None,
                    "failureReason": None,
                    "routeStage": "route-discovered",
                    "routeEvidence": "evidence",
                    "senderRouteStage": "route-discovered",
                    "passiveRouteStage": "route-discovered",
                    "timings": {"totalSeconds": 1.0},
                    "htmlReportPath": "summary.html",
                    "stdoutTail": "",
                    "stderrTail": "",
                    "elapsedSeconds": 1.0,
                    "exitCode": 0,
                }

            def fake_read_passive_peer_id(serial: str, app_id: str, retries: int = 60, delay_s: float = 1.0) -> str:
                del serial, app_id, retries, delay_s
                return "peer-123"

            # Act
            with patch.object(android_direct_matrix, "adb_devices", side_effect=fake_adb_devices), patch.object(
                android_direct_matrix, "adb_device_api_level", side_effect=fake_api_level
            ), patch.object(android_direct_matrix, "run_pair", side_effect=fake_run_pair), patch.object(
                android_direct_matrix, "read_passive_peer_id", side_effect=fake_read_passive_peer_id
            ):
                android_direct_matrix.main([
                    "--run-root",
                    str(run_root),
                    "--sender-passive-limit",
                    "2",
                    "--min-android-api-level",
                    "33",
                ])

            # Assert
            results = json.loads((run_root / "matrix-results.json").read_text(encoding="utf-8"))
            self.assertEqual(len(results), 2)
            self.assertEqual(
                calls,
                [
                    ("1f1dad34", "2ASVB21B09005117", False, "meshlink"),
                    ("1f1dad34", "2ASVB21B09005117", True, "meshlink"),
                    ("1f1dad34", "42004386e43c8589", False, "gatt"),
                    ("1f1dad34", "42004386e43c8589", True, "gatt"),
                ],
            )


if __name__ == "__main__":
    unittest.main()
