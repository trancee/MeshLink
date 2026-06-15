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

import run_headless_reference_android_direct_proof as android_direct_proof  # noqa: E402
import run_headless_reference_live_proof as live_proof  # noqa: E402


def _close_stream(stream: object | None) -> None:
    if stream is None:
        return
    close = getattr(stream, "close", None)
    if callable(close) and not getattr(stream, "closed", False):
        close()


class FakeLogcatProcess:
    def __init__(self, command: list[str], stdout, shared: dict[str, object]) -> None:
        self.command = list(command)
        self._stdout = stdout
        self._shared = shared
        self._serial = command[2]
        self._stopped = False
        serial = self._serial
        if serial == "passive-1":
            self._shared["passive_log"] = stdout
            if self._shared.get("passive_profile") == "proof":
                stdout.write(
                    "05-31 10:00:00.000 I MeshLinkReferenceAutomation: "
                    "REFERENCE_AUTOMATION started mode=LIVE_PROOF role=PASSIVE scenario=direct-guided\n"
                )
                stdout.write(
                    "05-31 10:00:00.000 I MeshLinkProof: MeshLink proof app ready on Test OEM Test Model (SDK 35) appId=demo.meshlink powerMode=Automatic transport=gattPrototype\n"
                )
                stdout.write(
                    "05-31 10:00:00.050 I MeshLinkProof: gatt.benchmark.start() -> Started\n"
                )
                stdout.write(
                    "05-31 10:00:00.100 I MeshLinkProof: GATT benchmark advertising started\n"
                )
            else:
                stdout.write(
                    "05-31 10:00:00.000 I MeshLinkReferenceAutomation: "
                    "REFERENCE_AUTOMATION started mode=LIVE_PROOF role=PASSIVE scenario=direct-guided\n"
                )
                stdout.write(
                    "05-31 10:00:00.050 D MeshLinkTransport: start() with l2capPsm=136\n"
                )
                stdout.write(
                    "05-31 10:00:00.060 D MeshLinkTransport: refreshDiscoveryState started=true suspended=false scanner=true advertiser=true\n"
                )
                stdout.write(
                    "05-31 10:00:00.070 D MeshLinkTransport: scan started\n"
                )
                stdout.write(
                    "05-31 10:00:00.080 D MeshLinkTransport: advertising started mode=2 tx=3 connectable=true\n"
                )
                stdout.write(
                    "05-31 10:00:00.100 I MeshLinkReferenceAutomation: "
                    "REFERENCE_AUTOMATION peer.discovered role=PASSIVE peer=passive-peer\n"
                )
                stdout.write(
                    "05-31 10:00:00.110 I MeshLinkReferenceAutomation: "
                    "REFERENCE_AUTOMATION timeline.peer.snapshot role=PASSIVE peers=passive-peer\n"
                )
            stdout.flush()
        elif serial == "sender-1":
            stdout.write(
                "05-31 10:00:01.000 I MeshLinkReferenceAutomation: "
                "REFERENCE_AUTOMATION started mode=LIVE_PROOF role=SENDER scenario=direct-guided\n"
            )
            stdout.write(
                "05-31 10:00:01.050 D MeshLinkTransport: start() with l2capPsm=136\n"
            )
            stdout.write(
                "05-31 10:00:01.060 D MeshLinkTransport: refreshDiscoveryState started=true suspended=false scanner=true advertiser=true\n"
            )
            stdout.write(
                "05-31 10:00:01.070 D MeshLinkTransport: scan started\n"
            )
            stdout.write(
                "05-31 10:00:01.080 D MeshLinkTransport: advertising started mode=2 tx=3 connectable=true\n"
            )
            stdout.write(
                "05-31 10:00:01.100 I MeshLinkReferenceAutomation: "
                "REFERENCE_AUTOMATION peer.discovered role=SENDER peer=passive-peer\n"
            )
            stdout.write(
                "05-31 10:00:01.180 I MeshLinkReferenceAutomation: "
                "REFERENCE_AUTOMATION ROUTE_DISCOVERED role=SENDER peer=passive-peer routeIsDirect=true\n"
            )
            stdout.write(
                "05-31 10:00:01.190 I MeshLinkReferenceAutomation: "
                "REFERENCE_AUTOMATION HOP_SESSION_ESTABLISHED role=SENDER peer=passive-peer routeIsDirect=true\n"
            )
            stdout.write(
                "05-31 10:00:01.200 I MeshLinkReferenceAutomation: "
                "REFERENCE_AUTOMATION send.requested role=sender phase=primary\n"
            )
            stdout.write(
                "05-31 10:00:01.300 I MeshLinkReferenceAutomation: "
                "REFERENCE_AUTOMATION proof.complete role=sender deliveries=1 routeIsDirect=true\n"
            )
            stdout.flush()
            passive_log = self._shared["passive_log"]
            passive_log.flush()
            stdout.close()

    def close(self) -> None:
        _close_stream(self._stdout)
        if self._serial != "passive-1":
            _close_stream(self._shared.get("passive_log"))

    def poll(self) -> int | None:
        return None if not self._stopped else 0

    def terminate(self) -> None:
        self._stopped = True
        self.close()

    def wait(self, timeout: float | None = None) -> int:
        self._stopped = True
        self.close()
        return 0

    def kill(self) -> None:
        self._stopped = True
        self.close()

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        self.close()
        return False

    def __del__(self) -> None:
        self.close()



class FakeCompletedProcess:
    def __init__(self, command: list[str], stdout: str = "", stderr: str = "") -> None:
        self.args = list(command)
        self.stdout = stdout
        self.stderr = stderr
        self.returncode = 0

    def communicate(self, input=None, timeout: float | None = None):
        del input, timeout
        return self.stdout, self.stderr

    def poll(self) -> int:
        return self.returncode

    def wait(self, timeout: float | None = None) -> int:
        del timeout
        return self.returncode

    def kill(self) -> None:
        return None

    def terminate(self) -> None:
        return None

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        return False

class AndroidDirectProofTests(unittest.TestCase):
    def test_parse_args_accepts_passive_benchmark_transport(self) -> None:
        # Arrange / Act
        args = android_direct_proof.parse_args(
            [
                "--sender-android-serial",
                "sender-1",
                "--passive-android-serial",
                "passive-1",
                "--passive-benchmark-transport",
                "gatt",
            ]
        )

        # Assert
        self.assertEqual(args.passive_benchmark_transport, "gatt")

    def test_extract_discovered_peer_id_reads_passive_peer_discovery_marker(self) -> None:
        # Arrange
        log_text = (
            "05-31 10:00:00.100 I MeshLinkReferenceAutomation: "
            "REFERENCE_AUTOMATION peer.discovered role=PASSIVE peer=passive-peer-123456\n"
        )

        # Act
        peer_id = android_direct_proof.extract_discovered_peer_id(log_text)

        # Assert
        self.assertEqual(peer_id, "passive-peer-123456")

    def test_android_proof_install_timeout_differs_for_wireless_and_usb_serials(self) -> None:
        # Arrange / Act / Assert
        self.assertEqual(
            android_direct_proof.android_proof_install_timeout_seconds(
                "adb-MJJ7ZDT455JBYTEA-0WCF8P._adb-tls-connect._tcp"
            ),
            android_direct_proof.ANDROID_WIRELESS_INSTALL_TIMEOUT_SECONDS,
        )
        self.assertEqual(
            android_direct_proof.android_proof_install_timeout_seconds("emulator-5554"),
            android_direct_proof.ANDROID_USB_INSTALL_TIMEOUT_SECONDS,
        )

    def test_main_runs_android_only_direct_flow_for_three_device_fleet_and_writes_retained_artifacts(self) -> None:
        # Arrange
        run_calls: list[list[str]] = []
        run_timeouts: list[float | None] = []
        force_stop_calls: list[str] = []
        events: list[tuple[str, str]] = []
        shared: dict[str, object] = {"passive_profile": "proof"}

        def fake_run(
            command: list[str],
            *,
            check: bool = True,
            capture_output: bool = False,
            text: bool = True,
            env: dict[str, str] | None = None,
            timeout: float | None = None,
        ) -> subprocess.CompletedProcess[str]:
            del check, capture_output, text, env
            run_calls.append(list(command))
            run_timeouts.append(timeout)
            if command[:6] == ["adb", "-s", command[2], "shell", "am", "start"]:
                if any("ch.trancee.meshlink.proof.android/.MainActivity" in part for part in command):
                    if command[2] == "passive-1":
                        role = "passive"
                        shared["passive_profile"] = "proof"
                    else:
                        role = "sender"
                        shared["sender_profile"] = "proof"
                else:
                    role = command[command.index(android_direct_proof.ANDROID_EXTRA_ROLE) + 1]
                events.append(("start", f"{command[2]}:{role}"))
                return subprocess.CompletedProcess(command, 0, stdout="started\n", stderr="")
            if command[:5] == ["adb", "-s", command[2], "shell", "getprop"] and command[-1] == "ro.build.version.sdk":
                return subprocess.CompletedProcess(command, 0, stdout="35\n", stderr="")
            if command[:5] == ["adb", "-s", command[2], "shell", "dumpsys"] and command[5] == "package":
                package_name = command[7] if len(command) > 7 else ""
                permissions = "\n".join(
                    f"{permission}: granted=true"
                    for permission in [
                        "android.permission.BLUETOOTH_SCAN",
                        "android.permission.BLUETOOTH_CONNECT",
                        "android.permission.BLUETOOTH_ADVERTISE",
                        "android.permission.ACCESS_FINE_LOCATION",
                    ]
                )
                return subprocess.CompletedProcess(
                    command,
                    0,
                    stdout=f"Package [{package_name}]\n{permissions}\n",
                    stderr="",
                )
            return subprocess.CompletedProcess(command, 0, stdout="started\n", stderr="")

        def fake_popen(command: list[str], stdout=None, stderr=None, text: bool = True, **kwargs):
            del text, kwargs
            if isinstance(command, list) and len(command) >= 4 and command[:4] == ["adb", "-s", command[2], "logcat"]:
                return FakeLogcatProcess(command, stdout, shared)
            stdout_text = ""
            stderr_text = ""
            return FakeCompletedProcess(command, stdout=stdout_text, stderr=stderr_text)

        def fake_force_stop(android_serial: str, android_package: str | None = None) -> None:
            del android_package
            force_stop_calls.append(android_serial)
            events.append(("force_stop", android_serial))

        def fake_read_android_app_file(android_serial: str, relative_path: str) -> str:
            self.assertEqual(android_serial, "passive-1")
            if relative_path.endswith("reference/history.json"):
                return '{"historyStatus": "RETAINED"}'
            if relative_path.endswith("exports/session-redacted.json"):
                return (
                    '{"defaultMode": "redacted-preview", '
                    '"fullPayloadIncluded": false, '
                    '"operatorOptInRecorded": false}'
                )
            if relative_path.endswith("direct-proof-probe/gatt-started.txt"):
                return "role=PASSIVE benchmarkTransport=gatt"
            raise AssertionError(f"Unexpected relative path: {relative_path}")

        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory) / "android-direct-proof"

            # Act
            with (
                patch.object(android_direct_proof, "ensure_android_device_ready") as ensure_ready,
                patch.object(android_direct_proof, "install_android_app") as install_android_app,
                patch.object(android_direct_proof, "install_android_proof_app") as install_android_proof_app,
                patch.object(
                    android_direct_proof,
                    "verify_android_runtime_permissions",
                ) as verify_permissions,
                patch.object(
                    android_direct_proof,
                    "verify_android_runtime_permissions_for_package",
                ) as verify_proof_permissions,
                patch.object(
                    android_direct_proof,
                    "force_stop_reference_app",
                    side_effect=fake_force_stop,
                ),
                patch.object(
                    android_direct_proof,
                    "force_stop_android_package",
                    side_effect=fake_force_stop,
                ),
                patch.object(
                    android_direct_proof,
                    "force_stop_android_package",
                    side_effect=fake_force_stop,
                ),
                patch.object(android_direct_proof, "run", side_effect=fake_run),
                patch.object(android_direct_proof.subprocess, "Popen", side_effect=fake_popen),
                patch.object(
                    android_direct_proof,
                    "read_android_app_file",
                    side_effect=fake_read_android_app_file,
                ),
                patch.object(android_direct_proof.time, "sleep", return_value=None),
            ):
                exit_code = android_direct_proof.main(
                    [
                        "--sender-android-serial",
                        "sender-1",
                        "--passive-android-serial",
                        "passive-1",
                        "--extra-force-stop-serial",
                        "extra-1",
                        "--app-id",
                        "demo.meshlink.reference.direct.test",
                        "--run-dir",
                        str(run_dir),
                        "--android-ready-seconds",
                        "0",
                        "--capture-timeout-seconds",
                        "1",
                        "--advertisement-carrier",
                        "uuid-pair-plus-service-data",
                        "--passive-benchmark-transport",
                        "gatt",
                    ]
                )

                # Assert
                self.assertEqual(exit_code, 0)
                self.assertEqual(
                    [call.args[0] for call in ensure_ready.call_args_list],
                    ["passive-1", "sender-1", "extra-1"],
                )
                self.assertEqual(
                    [call.args[0] for call in install_android_app.call_args_list],
                    ["sender-1"],
                )
                self.assertEqual(
                    [call.args[0] for call in install_android_proof_app.call_args_list],
                    ["passive-1"],
                )
                self.assertEqual(
                    [call.args[0] for call in verify_permissions.call_args_list],
                    ["sender-1"],
                )
                self.assertEqual(
                    [call.args[0] for call in verify_proof_permissions.call_args_list],
                    ["passive-1"],
                )

                start_commands = [
                    command
                    for command in run_calls
                    if command[:6] == ["adb", "-s", command[2], "shell", "am", "start"]
                ]
                self.assertEqual(start_commands[0][2], "passive-1")
                self.assertEqual(start_commands[1][2], "sender-1")
                self.assertIn("ch.trancee.meshlink.reference/.MainActivity", start_commands[0])
                self.assertIn("sender", start_commands[1])
                self.assertIn("direct-guided", start_commands[0])
                self.assertIn("direct-guided", start_commands[1])
                self.assertIn("ch.trancee.meshlink.reference.extra.UI_AUTOMATION_APP_ID", start_commands[0])
                self.assertIn("ch.trancee.meshlink.reference.extra.UI_AUTOMATION_BENCHMARK_TRANSPORT", start_commands[0])
                self.assertIn("meshlink.disableAutoSend", start_commands[0])
                self.assertIn("true", start_commands[0])
                self.assertNotIn("meshlink.primaryTransport", start_commands[0])
                self.assertNotIn("meshlink.primaryTransport", start_commands[1])
                self.assertNotIn("meshlink.benchmarkTransport", start_commands[1])
                sender_start_command = next(
                    command
                    for command in run_calls
                    if command[:6] == ["adb", "-s", "sender-1", "shell", "am", "start"]
                )
                self.assertIn(
                    "ch.trancee.meshlink.reference.extra.UI_AUTOMATION_ADVERTISEMENT_CARRIER",
                    sender_start_command,
                )
                self.assertIn("uuid-pair-plus-service-data", sender_start_command)
                self.assertEqual(force_stop_calls.count("sender-1"), 3)
                self.assertEqual(force_stop_calls.count("passive-1"), 3)
                self.assertEqual(force_stop_calls.count("extra-1"), 3)
                self.assertLess(
                    events.index(("force_stop", "extra-1")),
                    events.index(("start", "passive-1:passive")),
                )
                self.assertNotIn("-W", sender_start_command)
                self.assertEqual(
                    run_timeouts[run_calls.index(sender_start_command)],
                    android_direct_proof.ANDROID_START_TIMEOUT_SECONDS,
                )

                summary = json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))
                self.assertEqual(summary["status"], "passed")
                self.assertEqual(summary["scenario"], "direct-guided")
                self.assertEqual(summary["senderPlatform"], "android")
                self.assertEqual(summary["senderCompletion"].split(" role=")[1].split()[0], "sender")
                self.assertIsNone(summary["passiveCompletion"])
                self.assertIsNone(summary["exportRelativePath"])
                self.assertIn(
                    summary["routeStage"],
                    {"peer-discovered", "route-discovered", "hop-established"},
                )
                self.assertIn(
                    summary["senderRouteStage"],
                    {"peer-discovered", "route-discovered", "hop-established"},
                )
                self.assertIn(
                    summary["passiveRouteStage"],
                    {None, "peer-discovered", "route-discovered", "hop-established"},
                )
                self.assertIsNotNone(summary["routeEvidence"])
                self.assertEqual(summary["evidence"]["senderLogcat"], "sender_logcat.log")
                self.assertEqual(summary["evidence"]["passiveLogcat"], "passive_logcat.log")
                self.assertTrue((run_dir / "sender_logcat.log").exists())
                self.assertTrue((run_dir / "passive_logcat.log").exists())
                self.assertIn(
                    '"transport": "gatt"',
                    (run_dir / "android_history.json").read_text(encoding="utf-8"),
                )
                self.assertIn("startupTiming", summary)
                self.assertIn("timings", summary)
                self.assertEqual(summary["startupTiming"]["launch"]["passiveStartupWaitSeconds"], 0)
                self.assertIn("install", summary["startupTiming"])
                self.assertIn("permissions", summary["startupTiming"])
                self.assertIn("sender", summary["startupTiming"])
                self.assertIn("passive", summary["startupTiming"])
                self.assertEqual(summary["timings"]["transportMode"], "GATT")
                self.assertIsNotNone(summary["timings"]["sender"]["peerDiscoverySeconds"])
                self.assertIsNotNone(summary["timings"]["sender"]["trustConnectionSeconds"])
                self.assertEqual(summary["htmlReportPath"], "summary.html")
                report_html = (run_dir / "summary.html").read_text(encoding="utf-8")
                self.assertIn("Android direct-proof summary", report_html)
                self.assertIn("timing", report_html.lower())
                self.assertIn("route", report_html.lower())
                self.assertTrue((run_dir / summary["htmlReportPath"]).exists())
                report_html = (run_dir / summary["htmlReportPath"]).read_text(encoding="utf-8")
                self.assertIn("Android direct-proof summary", report_html)
                self.assertIn("GATT", report_html)

    def test_main_prefers_service_data_for_known_flaky_pairs(self) -> None:
        # Arrange
        run_calls: list[list[str]] = []

        class KnownPairLogcatProcess:
            def __init__(self, command: list[str], stdout, shared: dict[str, object]) -> None:
                self.command = list(command)
                self._stdout = stdout
                self._shared = shared
                self._serial = command[2]
                self._stopped = False
                serial = self._serial
                if serial == "GX6CTR500184":
                    shared["passive_log"] = stdout
                    stdout.write("06-13 23:51:15.900 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION started mode=LIVE_PROOF role=PASSIVE scenario=direct-guided\n")
                    stdout.write("06-13 23:51:16.100 D MeshLinkTransport: start() with l2capPsm=136\n")
                    stdout.write("06-13 23:51:16.200 D MeshLinkTransport: advertising started mode=2 tx=3 connectable=true\n")
                    stdout.flush()
                elif serial == "1f1dad34":
                    stdout.write("06-13 23:51:16.000 I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION started mode=LIVE_PROOF role=SENDER scenario=direct-guided\n")
                    stdout.write("06-13 23:51:16.050 D MeshLinkTransport: start() with l2capPsm=136\n")
                    stdout.write("06-13 23:51:16.060 D MeshLinkTransport: refreshDiscoveryState started=true suspended=false scanner=true advertiser=true\n")
                    stdout.write("06-13 23:51:16.070 D MeshLinkTransport: scan started\n")
                    stdout.flush()
                    stdout.close()

            def close(self) -> None:
                _close_stream(self._stdout)
                if self._serial != "GX6CTR500184":
                    _close_stream(self._shared.get("passive_log"))

            def poll(self) -> int | None:
                return None if not self._stopped else 0

            def terminate(self) -> None:
                self._stopped = True
                self.close()

            def wait(self, timeout: float | None = None) -> int:
                del timeout
                self._stopped = True
                self.close()
                return 0

            def kill(self) -> None:
                self._stopped = True
                self.close()

            def __del__(self) -> None:
                self.close()

        def fake_run(
            command: list[str],
            *,
            check: bool = True,
            capture_output: bool = False,
            text: bool = True,
            env: dict[str, str] | None = None,
            timeout: float | None = None,
        ) -> subprocess.CompletedProcess[str]:
            del check, capture_output, text, env, timeout
            run_calls.append(list(command))
            if command[:3] == ["adb", "devices", "-l"]:
                return subprocess.CompletedProcess(command, 0, stdout="List of devices attached\n1f1dad34 device\nGX6CTR500184 device\n", stderr="")
            if command[:6] == ["adb", "-s", "1f1dad34", "shell", "am", "start"]:
                return subprocess.CompletedProcess(command, 0, stdout="Starting: Intent\n", stderr="")
            if command[:5] == ["adb", "-s", "1f1dad34", "shell", "run-as"]:
                return subprocess.CompletedProcess(command, 0, stdout="", stderr="")
            if command[:5] == ["adb", "-s", "GX6CTR500184", "shell", "run-as"]:
                return subprocess.CompletedProcess(command, 0, stdout="", stderr="")
            if command[:3] == ["adb", "-s", "1f1dad34"] and command[3] == "uninstall":
                return subprocess.CompletedProcess(command, 0, stdout="Success\n", stderr="")
            if command[:3] == ["adb", "-s", "GX6CTR500184"] and command[3] == "uninstall":
                return subprocess.CompletedProcess(command, 0, stdout="Success\n", stderr="")
            return subprocess.CompletedProcess(command, 0, stdout="", stderr="")

        def fake_popen(command: list[str], stdout, stderr, text: bool):
            del stderr, text
            return KnownPairLogcatProcess(command, stdout, shared)

        shared: dict[str, object] = {}
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory) / "service-data-preference"
            with (
                patch.object(android_direct_proof, "ensure_android_device_ready"),
                patch.object(android_direct_proof, "install_android_app"),
                patch.object(android_direct_proof, "verify_android_runtime_permissions"),
                patch.object(android_direct_proof, "force_stop_reference_app"),
                patch.object(android_direct_proof, "wait_for_android_completions"),
                patch.object(android_direct_proof, "wait_for_passive_export_completion"),
                patch.object(android_direct_proof, "cleanup_android_direct_run"),
                patch.object(android_direct_proof, "run", side_effect=fake_run),
                patch.object(android_direct_proof.subprocess, "Popen", side_effect=fake_popen),
            ):
                with self.assertRaises(SystemExit):
                    android_direct_proof.main(
                        [
                            "--sender-android-serial",
                            "1f1dad34",
                            "--passive-android-serial",
                            "GX6CTR500184",
                            "--run-dir",
                            str(run_dir),
                            "--capture-timeout-seconds",
                            "1",
                            "--android-ready-seconds",
                            "0",
                        ]
                    )

        sender_start_commands = [
            command
            for command in run_calls
            if command[:6] == ["adb", "-s", "1f1dad34", "shell", "am", "start"]
        ]
        self.assertTrue(sender_start_commands)
        self.assertIn("uuid-pair-plus-service-data", sender_start_commands[0])
    def test_main_rejects_missing_transport_discovery_with_specific_failure(self) -> None:
        # Arrange
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory) / "transport-failure"
            run_dir.mkdir(parents=True, exist_ok=True)
            (run_dir / "sender_logcat.log").write_text(
                "\n".join(
                    [
                        "D MeshLinkTransport: start() with l2capPsm=141",
                        "D MeshLinkTransport: refreshDiscoveryState started=true suspended=false scanner=true advertiser=true psm=141",
                        "D MeshLinkTransport: scan started",
                        "D MeshLinkTransport: advertising started mode=2 tx=3 connectable=true",
                    ]
                ),
                encoding="utf-8",
            )
            (run_dir / "passive_logcat.log").write_text(
                "\n".join(
                    [
                        "D MeshLinkTransport: start() with l2capPsm=152",
                        "D MeshLinkTransport: refreshDiscoveryState started=true suspended=false scanner=true advertiser=true psm=152",
                        "D MeshLinkTransport: scan started",
                        "D MeshLinkTransport: advertising started mode=2 tx=3 connectable=true",
                    ]
                ),
                encoding="utf-8",
            )

            # Act / Assert
            self.assertEqual(
                android_direct_proof.transport_failure_reason(run_dir),
                "Android transport reached scan/advertise startup but never emitted peer.discovered; direct proof cannot proceed without a retained discovery seed",
            )

    def test_transport_failure_reason_reports_route_stall_after_peer_discovery(self) -> None:
        # Arrange
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory) / "route-stall"
            run_dir.mkdir(parents=True, exist_ok=True)
            (run_dir / "sender_logcat.log").write_text(
                "\n".join(
                    [
                        "D MeshLinkTransport: start() with l2capPsm=141",
                        "D MeshLinkTransport: refreshDiscoveryState started=true suspended=false scanner=true advertiser=true psm=141",
                        "D MeshLinkTransport: scan started",
                        "D MeshLinkTransport: advertising started mode=2 tx=3 connectable=true",
                        "I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION peer.discovered role=SENDER peer=passive-peer",
                        "I MeshLinkReferenceAutomation: REFERENCE_RUNTIME diagnostic code=NO_ROUTE_AVAILABLE stage=delivery.noRoute peer=passive-peer detail=NO_ROUTE_AVAILABLE @ delivery.noRoute {peerId=passive-peer, topologyVersion=0, routeAvailable=false}",
                    ]
                ),
                encoding="utf-8",
            )
            (run_dir / "passive_logcat.log").write_text(
                "\n".join(
                    [
                        "D MeshLinkTransport: start() with l2capPsm=141",
                        "D MeshLinkTransport: refreshDiscoveryState started=true suspended=false scanner=true advertiser=true psm=141",
                        "D MeshLinkTransport: scan started",
                        "D MeshLinkTransport: advertising started mode=2 tx=3 connectable=true",
                        "I MeshLinkReferenceAutomation: REFERENCE_AUTOMATION peer.discovered role=PASSIVE peer=sender-peer",
                        "I MeshLinkReferenceAutomation: REFERENCE_RUNTIME diagnostic code=ROUTE_DISCOVERED stage=transport.handshake.message3.complete.routeAvailable peer=sender-peer detail=ROUTE_DISCOVERED @ transport.handshake.message3.complete.routeAvailable {peerId=sender-peer, topologyVersion=1, routeAvailable=true, routeIsDirect=true}",
                    ]
                ),
                encoding="utf-8",
            )

            # Act / Assert
            reason = android_direct_proof.transport_failure_reason(run_dir)
            self.assertIsNotNone(reason)
            self.assertIn("route stage", reason)
            self.assertIn("sender=route-unavailable", reason)
            self.assertIn("passive=route-discovered", reason)

    def test_main_rejects_duplicate_sender_and_passive_serials_and_records_failure_summary(self) -> None:
        # Arrange / Act
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory) / "duplicate-serials"
            with self.assertRaises(SystemExit) as error:
                android_direct_proof.main(
                    [
                        "--sender-android-serial",
                        "same-device",
                        "--passive-android-serial",
                        "same-device",
                        "--run-dir",
                        str(run_dir),
                    ]
                )

            # Assert
            self.assertIn("must be different physical devices", str(error.exception))
            summary = json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))
            self.assertEqual(summary["status"], "failed")
            self.assertEqual(summary["failureStage"], "input-validation")
            self.assertIn("same-device", summary["failureReason"])

    def test_main_rejects_extra_force_stop_serial_that_reuses_a_role_device(self) -> None:
        # Arrange / Act
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory) / "duplicate-extra-role"
            with self.assertRaises(SystemExit) as error:
                android_direct_proof.main(
                    [
                        "--sender-android-serial",
                        "sender-1",
                        "--passive-android-serial",
                        "passive-1",
                        "--extra-force-stop-serial",
                        "sender-1",
                        "--run-dir",
                        str(run_dir),
                    ]
                )

            # Assert
            self.assertIn("cannot reuse the sender device", str(error.exception))
            summary = json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))
            self.assertEqual(summary["status"], "failed")
            self.assertEqual(summary["failureStage"], "input-validation")
            self.assertIn("sender-1", summary["failureReason"])

    def test_main_rejects_duplicate_extra_force_stop_serials_and_records_failure_summary(self) -> None:
        # Arrange / Act
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory) / "duplicate-extra-serials"
            with self.assertRaises(SystemExit) as error:
                android_direct_proof.main(
                    [
                        "--sender-android-serial",
                        "sender-1",
                        "--passive-android-serial",
                        "passive-1",
                        "--extra-force-stop-serial",
                        "extra-1",
                        "--extra-force-stop-serial",
                        "extra-1",
                        "--run-dir",
                        str(run_dir),
                    ]
                )

            # Assert
            self.assertIn("must be unique physical devices", str(error.exception))
            summary = json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))
            self.assertEqual(summary["status"], "failed")
            self.assertEqual(summary["failureStage"], "input-validation")
            self.assertIn("extra-1", summary["failureReason"])

    def test_main_records_preflight_failure_with_the_failing_serial(self) -> None:
        # Arrange / Act
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory) / "permission-failure"
            with (
                patch.object(android_direct_proof, "ensure_android_device_ready"),
                patch.object(android_direct_proof, "install_android_app"),
                patch.object(
                    android_direct_proof,
                    "verify_android_runtime_permissions",
                    side_effect=[None, SystemExit("BLUETOOTH_SCAN not granted")],
                ),
            ):
                with self.assertRaises(SystemExit) as error:
                    android_direct_proof.main(
                        [
                            "--sender-android-serial",
                            "sender-1",
                            "--passive-android-serial",
                            "passive-1",
                            "--run-dir",
                            str(run_dir),
                        ]
                    )

            # Assert
            self.assertIn("sender-1", str(error.exception))
            summary = json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))
            self.assertEqual(summary["status"], "failed")
            self.assertEqual(summary["failureStage"], "preflight")
            self.assertIn("sender-1", summary["failureReason"])
            self.assertEqual(summary["htmlReportPath"], "summary.html")

    def test_main_records_install_failure_details_in_summary(self) -> None:
        # Arrange
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory) / "install-failure"
            with (
                patch.object(android_direct_proof, "ensure_android_device_ready"),
                patch.object(
                    android_direct_proof,
                    "install_android_app",
                    side_effect=subprocess.CalledProcessError(
                        255,
                        ["./gradlew", ":meshlink-reference:installDebug", "--console=plain"],
                        stderr="INSTALL_FAILED_UPDATE_INCOMPATIBLE",
                    ),
                ),
                patch.object(android_direct_proof, "verify_android_runtime_permissions"),
            ):
                with self.assertRaises(SystemExit) as error:
                    android_direct_proof.main(
                        [
                            "--sender-android-serial",
                            "sender-1",
                            "--passive-android-serial",
                            "passive-1",
                            "--run-dir",
                            str(run_dir),
                        ]
                    )

            # Assert
            self.assertIn("INSTALL_FAILED_UPDATE_INCOMPATIBLE", str(error.exception))
            summary = json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))
            self.assertEqual(summary["status"], "failed")
            self.assertEqual(summary["failureStage"], "preflight")
            self.assertIn("INSTALL_FAILED_UPDATE_INCOMPATIBLE", summary["failureReason"])
            self.assertEqual(summary["htmlReportPath"], "summary.html")

    def test_install_android_app_retries_after_uninstall_and_preserves_retry_evidence(self) -> None:
        # Arrange
        call_log: list[list[str]] = []

        def fake_subprocess_run(
            command: list[str],
            *,
            check: bool = False,
            capture_output: bool = False,
            text: bool = True,
            env: dict[str, str] | None = None,
            timeout: float | None = None,
        ) -> subprocess.CompletedProcess[str]:
            del check, capture_output, text, env, timeout
            call_log.append(list(command))
            if command[:2] == ["adb", "-s"] and command[3] == "uninstall":
                return subprocess.CompletedProcess(command, 0, stdout="Success\n", stderr="")
            gradle_calls = [c for c in call_log if c[0] == "./gradlew"]
            if len(gradle_calls) == 1:
                return subprocess.CompletedProcess(
                    command,
                    255,
                    stdout="first install stdout\n",
                    stderr="first install stderr\n",
                )
            return subprocess.CompletedProcess(
                command,
                0,
                stdout="second install stdout\n",
                stderr="",
            )

        # Act
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory)
            with (
                patch.object(live_proof, "android_apk_path", return_value=Path("/tmp/fake.apk")),
                patch.object(live_proof, "launcher_source_fingerprint", return_value="fingerprint"),
                patch.object(live_proof, "sha256_file", return_value="hash"),
                patch.object(live_proof, "load_android_install_cache", return_value=None),
                patch.object(live_proof, "write_android_install_cache"),
                patch.object(live_proof.subprocess, "run", side_effect=fake_subprocess_run),
            ):
                live_proof.install_android_app("sender-1", run_dir)

        # Assert
        self.assertEqual(
            [command[0] for command in call_log],
            ["./gradlew", "adb", "./gradlew"],
        )
        self.assertIn("uninstall", call_log[1])

    def test_main_preserves_failure_summary_when_extra_cleanup_fails(self) -> None:
        # Arrange
        shared: dict[str, object] = {}
        force_stop_counts: dict[str, int] = {}

        def fake_run(
            command: list[str],
            *,
            check: bool = True,
            capture_output: bool = False,
            text: bool = True,
            env: dict[str, str] | None = None,
            timeout: float | None = None,
        ) -> subprocess.CompletedProcess[str]:
            del check, capture_output, text, env, timeout
            return subprocess.CompletedProcess(command, 0, stdout="started\n", stderr="")

        def fake_popen(command: list[str], stdout=None, stderr=None, text: bool = True, **kwargs):
            del stderr, text, kwargs
            return FakeLogcatProcess(command, stdout, shared)

        def fake_force_stop(android_serial: str, android_package: str | None = None) -> None:
            del android_package
            force_stop_counts[android_serial] = force_stop_counts.get(android_serial, 0) + 1
            if android_serial == "extra-1" and force_stop_counts[android_serial] == 2:
                raise RuntimeError("adb cleanup failed")

        def fake_read_android_app_file(android_serial: str, relative_path: str) -> str:
            self.assertEqual(android_serial, "passive-1")
            if relative_path.endswith("reference/history.json"):
                return '{"historyStatus": "RETAINED"}'
            if relative_path.endswith("exports/session-redacted.json"):
                return (
                    '{"defaultMode": "redacted-preview", '
                    '"fullPayloadIncluded": false, '
                    '"operatorOptInRecorded": false}'
                )
            if relative_path.endswith("direct-proof-probe/gatt-started.txt"):
                return "role=PASSIVE benchmarkTransport=gatt"
            raise AssertionError(f"Unexpected relative path: {relative_path}")

        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory) / "cleanup-failure"

            # Act
            with (
                patch.object(android_direct_proof, "ensure_android_device_ready"),
                patch.object(android_direct_proof, "install_android_app"),
                patch.object(android_direct_proof, "verify_android_runtime_permissions"),
                patch.object(
                    android_direct_proof,
                    "force_stop_reference_app",
                    side_effect=fake_force_stop,
                ),
                patch.object(
                    android_direct_proof,
                    "force_stop_android_package",
                    side_effect=fake_force_stop,
                ),
                patch.object(android_direct_proof, "run", side_effect=fake_run),
                patch.object(android_direct_proof.subprocess, "Popen", side_effect=fake_popen),
                patch.object(
                    android_direct_proof,
                    "read_android_app_file",
                    side_effect=fake_read_android_app_file,
                ),
                patch.object(android_direct_proof.time, "sleep", return_value=None),
            ):
                with self.assertRaises(SystemExit) as error:
                    android_direct_proof.main(
                        [
                            "--sender-android-serial",
                            "sender-1",
                            "--passive-android-serial",
                            "passive-1",
                            "--extra-force-stop-serial",
                            "extra-1",
                            "--app-id",
                            "demo.meshlink.reference.direct.test",
                            "--run-dir",
                            str(run_dir),
                            "--android-ready-seconds",
                            "0",
                            "--capture-timeout-seconds",
                            "1",
                        ]
                    )

            # Assert
            self.assertIn("cleanup failed", str(error.exception))
            self.assertIn("extra-1", str(error.exception))
            summary = json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))
            self.assertEqual(summary["status"], "failed")
            self.assertEqual(summary["failureStage"], "cleanup")
            self.assertIn("extra-1", summary["failureReason"])
            self.assertTrue(summary["captured"]["senderLogcat"])
            self.assertTrue(summary["captured"]["passiveLogcat"])
            self.assertTrue(summary["partialEvidenceAvailable"])
            self.assertEqual(summary["senderCompletion"].split(" role=")[1].split()[0], "sender")
            self.assertIsNone(summary["passiveCompletion"])

    def test_main_records_sender_launch_timeout_with_retained_start_note(self) -> None:
        # Arrange
        shared: dict[str, object] = {}

        def fake_run(
            command: list[str],
            *,
            check: bool = True,
            capture_output: bool = False,
            text: bool = True,
            env: dict[str, str] | None = None,
            timeout: float | None = None,
        ) -> subprocess.CompletedProcess[str]:
            del check, capture_output, text, env
            if command[:6] == ["adb", "-s", "sender-1", "shell", "am", "start"]:
                raise subprocess.TimeoutExpired(command, android_direct_proof.ANDROID_START_TIMEOUT_SECONDS)
            return subprocess.CompletedProcess(command, 0, stdout="started\n", stderr="")

        def fake_popen(command: list[str], stdout=None, stderr=None, text: bool = True, **kwargs):
            del stderr, text, kwargs
            return FakeLogcatProcess(command, stdout, shared)

        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory) / "sender-launch-timeout"

            # Act
            with (
                patch.object(android_direct_proof, "ensure_android_device_ready"),
                patch.object(android_direct_proof, "install_android_app"),
                patch.object(android_direct_proof, "verify_android_runtime_permissions"),
                patch.object(android_direct_proof, "force_stop_reference_app"),
                patch.object(android_direct_proof, "run", side_effect=fake_run),
                patch.object(android_direct_proof.subprocess, "Popen", side_effect=fake_popen),
                patch.object(android_direct_proof, "read_android_app_file", return_value="{}"),
                patch.object(android_direct_proof.time, "sleep", return_value=None),
            ):
                with self.assertRaises(SystemExit) as error:
                    android_direct_proof.main(
                        [
                            "--sender-android-serial",
                            "sender-1",
                            "--passive-android-serial",
                            "passive-1",
                            "--app-id",
                            "demo.meshlink.reference.direct.test",
                            "--run-dir",
                            str(run_dir),
                            "--android-ready-seconds",
                            "0",
                            "--capture-timeout-seconds",
                            "1",
                        ]
                    )

            # Assert
            self.assertIn("timed out", str(error.exception).lower())
            summary = json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))
            self.assertEqual(summary["status"], "failed")
            self.assertEqual(summary["failureStage"], "launch")
            self.assertIn("timed out", summary["failureReason"].lower())
            self.assertIn("Timed out waiting for Android activity launch to return.", (run_dir / "sender_start.txt").read_text(encoding="utf-8"))
            self.assertTrue((run_dir / "sender_logcat.log").exists())
            self.assertTrue((run_dir / "passive_logcat.log").exists())

    def test_wait_for_android_completions_rejects_missing_passive_export_path(self) -> None:
        # Arrange
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory)
            (run_dir / "sender_logcat.log").write_text(
                "05-31 10:00:01.300 I MeshLinkReferenceAutomation: "
                "REFERENCE_AUTOMATION proof.complete role=sender deliveries=1 routeIsDirect=true\n",
                encoding="utf-8",
            )
            (run_dir / "passive_logcat.log").write_text(
                "05-31 10:00:01.500 I MeshLinkReferenceAutomation: "
                "REFERENCE_AUTOMATION proof.complete role=passive inboundCount=1\n",
                encoding="utf-8",
            )

            # Act / Assert
            with self.assertRaises(SystemExit) as error:
                android_direct_proof.wait_for_android_completions(
                    run_dir,
                    timeout_seconds=0.1,
                    sender_android_serial="sender-1",
                    passive_android_serial="passive-1",
                )

        self.assertIn("missing export path", str(error.exception))

    def test_wait_for_android_completions_times_out_when_only_sender_completes(self) -> None:
        # Arrange
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory)
            (run_dir / "sender_logcat.log").write_text(
                "05-31 10:00:01.300 I MeshLinkReferenceAutomation: "
                "REFERENCE_AUTOMATION proof.complete role=sender deliveries=1 routeIsDirect=true\n",
                encoding="utf-8",
            )
            (run_dir / "passive_logcat.log").write_text(
                "05-31 10:00:01.000 I MeshLinkReferenceAutomation: "
                "REFERENCE_AUTOMATION started mode=LIVE_PROOF role=PASSIVE scenario=direct-guided\n",
                encoding="utf-8",
            )

            with (
                patch.object(
                    android_direct_proof.time,
                    "monotonic",
                    side_effect=[0.0, 0.01, 0.2, 0.21],
                ),
                patch.object(android_direct_proof.time, "sleep", return_value=None),
            ):
                # Act / Assert
                with self.assertRaises(SystemExit) as error:
                    android_direct_proof.wait_for_android_completions(
                        run_dir,
                        timeout_seconds=0.1,
                        sender_android_serial="sender-1",
                        passive_android_serial="passive-1",
                    )

        self.assertIn("passive", str(error.exception))

    def test_summarize_and_verify_rejects_empty_sender_log(self) -> None:
        # Arrange
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory)
            (run_dir / "sender_logcat.log").write_text("", encoding="utf-8")
            (run_dir / "passive_logcat.log").write_text(
                "05-31 10:00:01.500 I MeshLinkReferenceAutomation: "
                "REFERENCE_AUTOMATION proof.complete role=passive inboundCount=1 export=exports/session-redacted.json\n",
                encoding="utf-8",
            )

            completions = android_direct_proof.AndroidDirectCompletions(
                sender_completion=(
                    "05-31 10:00:01.300 I MeshLinkReferenceAutomation: "
                    "REFERENCE_AUTOMATION proof.complete role=sender deliveries=1 routeIsDirect=true"
                ),
                passive_completion=(
                    "05-31 10:00:01.500 I MeshLinkReferenceAutomation: "
                    "REFERENCE_AUTOMATION proof.complete role=passive inboundCount=1 export=exports/session-redacted.json"
                ),
                export_relative_path="exports/session-redacted.json",
                sender_failed=None,
            )

            # Act / Assert
            with self.assertRaises(SystemExit) as error:
                android_direct_proof.summarize_and_verify(
                    sender_android_serial="sender-1",
                    passive_android_serial="passive-1",
                    run_dir=run_dir,
                    storage_subdirectory="storage-subdirectory",
                    app_id="demo.meshlink.reference.direct.test",
                    completions=completions,
                    startup_timing={
                        "sender": {"observed": False, "elapsedSeconds": None, "line": None},
                        "passive": {"observed": False, "elapsedSeconds": None, "line": None},
                        "passiveTransport": {"observed": False, "elapsedSeconds": None, "line": None},
                        "launch": {},
                        "totalSeconds": None,
                    },
                    timings={
                        "totalSeconds": 0.0,
                        "androidReadySeconds": 0.0,
                        "captureTimeoutSeconds": 0.0,
                        "sender": {},
                        "passive": {},
                    },
                )

        self.assertIn("Sender log is empty", str(error.exception))

    def test_direct_proof_result_digest_and_guide_text_stay_aligned(self) -> None:
        # Arrange
        digest_path = Path("docs/reference/android-direct-proof-matrix-result.md")
        guide_path = Path("docs/how-to/run-reference-app-physical-integration-scenarios.md")
        evaluation_path = Path("docs/how-to/evaluate-meshlink-with-the-reference-app.md")
        layout_path = Path("docs/reference/repository-layout.md")

        # Act
        digest_text = digest_path.read_text(encoding="utf-8")
        guide_text = guide_path.read_text(encoding="utf-8")
        evaluation_text = evaluation_path.read_text(encoding="utf-8")
        layout_text = layout_path.read_text(encoding="utf-8")

        # Assert
        self.assertIn("15 / 132", digest_text)
        self.assertIn("preflight", digest_text)
        self.assertIn("capture", digest_text)
        self.assertIn("Mi Note 3", digest_text)
        self.assertIn("OnePlus 7T", digest_text)
        self.assertIn("45s", digest_text)
        self.assertIn("proof.complete", digest_text)
        self.assertIn("L2CAP", digest_text)
        self.assertIn("GATT", digest_text)
        self.assertIn("Android direct-proof matrix result", guide_text)
        self.assertIn("45s", guide_text)
        self.assertIn("preflight", guide_text)
        self.assertIn("capture", guide_text)
        self.assertIn("proof.complete", guide_text)
        self.assertIn("Android direct-proof matrix result", evaluation_text)
        self.assertIn("direct-proof result digest", evaluation_text)
        self.assertIn("direct-proof matrix rerun", layout_text)
        self.assertIn("Retains the canonical summary", layout_text)

    def test_extract_transport_mode_prefers_primary_transport_marker(self) -> None:
        # Arrange
        l2cap_log = "06-15 08:55:31.157 I MeshLinkReferenceAutomation: start() with l2capPsm=201\n"
        gatt_primary_log = (
            "06-15 08:55:30.882 I MeshLinkProof: MeshLink proof app ready on Test OEM Test Model "
            "(SDK 34) appId=test primaryTransport=gattPrototype benchmarkTransport=meshlink\n"
        )
        meshlink_primary_log = (
            "06-15 08:55:30.882 I MeshLinkProof: MeshLink proof app ready on Test OEM Test Model "
            "(SDK 34) appId=test primaryTransport=meshlink benchmarkTransport=gattPrototype\n"
        )

        # Act
        l2cap_result = android_direct_proof.extract_transport_mode(l2cap_log)
        gatt_result = android_direct_proof.extract_transport_mode(gatt_primary_log)
        meshlink_result = android_direct_proof.extract_transport_mode(meshlink_primary_log)

        # Assert
        self.assertEqual(l2cap_result[0], "L2CAP")
        self.assertEqual(gatt_result[0], "GATT")
        self.assertEqual(meshlink_result[0], "L2CAP")

    def test_start_android_role_app_passes_primary_transport_to_proof_app(self) -> None:
        # Arrange
        run_calls: list[list[str]] = []
        shared: dict[str, object] = {}
        logcat_stdout = tempfile.TemporaryFile(mode="w+", encoding="utf-8")

        def fake_run(command: list[str], **kwargs):
            del kwargs
            run_calls.append(list(command))
            return subprocess.CompletedProcess(command, 0, stdout="", stderr="")

        def fake_popen(command: list[str], stdout=None, stderr=None, text: bool = True, **kwargs):
            del stderr, text, kwargs
            return FakeLogcatProcess(command, stdout or logcat_stdout, shared)

        with (
            patch.object(android_direct_proof, "force_stop_android_package"),
            patch.object(android_direct_proof, "run", side_effect=fake_run),
            patch.object(android_direct_proof.subprocess, "Popen", side_effect=fake_popen),
        ):
            # Act
            android_direct_proof.start_android_role_app(
                run_dir=Path("/tmp"),
                android_serial="passive-1",
                label="passive",
                role="passive",
                app_id="demo.meshlink.reference.direct.test",
                storage_subdirectory="storage",
                android_transport_logcat=False,
                advertisement_carrier="uuid-pair-plus-service-data",
                primary_transport="gattNotifyPrototype",
                benchmark_transport="gatt-notify",
                android_activity=android_direct_proof.ANDROID_PROOF_ACTIVITY,
                android_package=android_direct_proof.ANDROID_PROOF_PACKAGE,
            )

        # Assert
        start_command = next(command for command in run_calls if command[:6] == ["adb", "-s", "passive-1", "shell", "am", "start"])
        self.assertIn("meshlink.primaryTransport", start_command)
        self.assertIn("gattNotifyPrototype", start_command)
        self.assertIn("meshlink.benchmarkTransport", start_command)
        self.assertIn("gatt-notify", start_command)
        self.assertIn("meshlink.disableAutoSend", start_command)
        self.assertIn("true", start_command)
        self.assertIn("ch.trancee.meshlink.proof.android/.MainActivity", start_command)


if __name__ == "__main__":
    unittest.main()
