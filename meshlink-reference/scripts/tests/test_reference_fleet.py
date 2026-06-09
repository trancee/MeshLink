from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from support import (
    FakeCommandRunner,
    adb_devices_output,
    android_model_output,
    candidate_by_shape,
    devicectl_table,
    probe_response,
    reason_codes,
    reference_fleet,
    resolved_team_lookup,
    unresolved_team_lookup,
)


class ReferenceFleetTests(unittest.TestCase):
    def test_build_fleet_manifest_remains_a_pure_manifest_seam(self) -> None:
        # Arrange
        runner = FakeCommandRunner(
            {
                ("adb", "devices"): probe_response(
                    stdout=adb_devices_output(
                        ("android-passive", "device"),
                        ("android-sender", "device"),
                    )
                ),
                ("adb", "-s", "android-passive", "shell", "getprop", "ro.product.model"): probe_response(
                    stdout=android_model_output("Pixel 8")
                ),
                ("adb", "-s", "android-sender", "shell", "getprop", "ro.product.model"): probe_response(
                    stdout=android_model_output("Pixel 7")
                ),
                ("xcrun", "devicectl", "list", "devices"): probe_response(stdout=devicectl_table()),
            }
        )

        # Act
        manifest = reference_fleet.build_fleet_manifest(command_runner=runner)

        # Assert
        self.assertIn("selectedAssignment", manifest)
        self.assertIn("candidateAssignments", manifest)
        self.assertEqual(manifest["selection"]["status"], "selected")
        self.assertEqual(manifest["selectedAssignment"]["shape"], "android-only")
        self.assertTrue(any(candidate["shape"] == "mixed" for candidate in manifest["candidateAssignments"]))
        self.assertTrue(any(candidate["shape"] == "android-only" for candidate in manifest["candidateAssignments"]))
        self.assertTrue(all("runnerCommand" not in candidate for candidate in manifest["candidateAssignments"]))
        self.assertTrue(all("analysisCommand" not in candidate for candidate in manifest["candidateAssignments"]))

    def test_prefers_android_only_fallback_when_live_mixed_is_not_supported(self) -> None:
        # Arrange
        runner = FakeCommandRunner(
            {
                ("adb", "devices"): probe_response(
                    stdout=adb_devices_output(
                        ("android-passive", "device"),
                        ("android-sender", "device"),
                    )
                ),
                ("adb", "-s", "android-passive", "shell", "getprop", "ro.product.model"): probe_response(
                    stdout=android_model_output("Pixel 8")
                ),
                ("adb", "-s", "android-sender", "shell", "getprop", "ro.product.model"): probe_response(
                    stdout=android_model_output("Galaxy S24")
                ),
                ("xcrun", "devicectl", "list", "devices"): probe_response(
                    stdout=devicectl_table(
                        ("iPhone 15", "iOS 18.0", "Connected", "00008110-000E196E0E92401E"),
                    )
                ),
            }
        )

        # Act
        manifest = reference_fleet.build_fleet_manifest(
            command_runner=runner,
            environment={},
            development_team_lookup=resolved_team_lookup,
        )

        # Assert
        mixed_candidate = candidate_by_shape(manifest, "mixed")
        android_only_candidate = candidate_by_shape(manifest, "android-only")

        self.assertEqual(manifest["selection"]["status"], "selected")
        self.assertEqual(manifest["selectedAssignment"]["shape"], "android-only")
        self.assertEqual(manifest["selectedAssignment"]["baseline"], "direct-guided")
        self.assertEqual(mixed_candidate["status"], "runnable")
        self.assertEqual(android_only_candidate["status"], "runnable")
        self.assertIn("selected-android-only-fallback", reason_codes(manifest["selection"]))
        self.assertTrue(all(isinstance(call["command"], list) for call in runner.calls))

    def test_selects_android_only_when_two_healthy_android_devices_are_available(self) -> None:
        # Arrange
        runner = FakeCommandRunner(
            {
                ("adb", "devices"): probe_response(
                    stdout=adb_devices_output(
                        ("android-one", "device"),
                        ("android-two", "device"),
                    )
                ),
                ("adb", "-s", "android-one", "shell", "getprop", "ro.product.model"): probe_response(
                    stdout=android_model_output("Pixel 8")
                ),
                ("adb", "-s", "android-two", "shell", "getprop", "ro.product.model"): probe_response(
                    stdout=android_model_output("Pixel Fold")
                ),
                ("xcrun", "devicectl", "list", "devices"): probe_response(stdout=devicectl_table()),
            }
        )

        # Act
        manifest = reference_fleet.build_fleet_manifest(
            command_runner=runner,
            environment={},
            development_team_lookup=resolved_team_lookup,
        )

        # Assert
        mixed_candidate = candidate_by_shape(manifest, "mixed")
        android_only_candidate = candidate_by_shape(manifest, "android-only")

        self.assertEqual(manifest["selection"]["status"], "selected")
        self.assertEqual(manifest["selectedAssignment"]["shape"], "android-only")
        self.assertEqual(mixed_candidate["status"], "skipped")
        self.assertEqual(android_only_candidate["status"], "runnable")

    def test_skips_honestly_when_only_one_android_device_is_available(self) -> None:
        # Arrange
        runner = FakeCommandRunner(
            {
                ("adb", "devices"): probe_response(stdout=adb_devices_output(("android-one", "device"))),
                ("adb", "-s", "android-one", "shell", "getprop", "ro.product.model"): probe_response(
                    stdout=android_model_output("Pixel 8")
                ),
                ("xcrun", "devicectl", "list", "devices"): probe_response(stdout=devicectl_table()),
            }
        )

        # Act
        manifest = reference_fleet.build_fleet_manifest(
            command_runner=runner,
            environment={},
            development_team_lookup=resolved_team_lookup,
        )

        # Assert
        android_only_candidate = candidate_by_shape(manifest, "android-only")

        self.assertEqual(manifest["selection"]["status"], "skipped")
        self.assertIsNone(manifest["selectedAssignment"])
        self.assertEqual(android_only_candidate["status"], "skipped")
        self.assertIn("android-fleet-too-small", reason_codes(manifest["selection"]))

    def test_marks_unresolved_ios_signing_as_invalid_environment_when_mixed_is_the_only_shape(self) -> None:
        # Arrange
        runner = FakeCommandRunner(
            {
                ("adb", "devices"): probe_response(stdout=adb_devices_output(("android-one", "device"))),
                ("adb", "-s", "android-one", "shell", "getprop", "ro.product.model"): probe_response(
                    stdout=android_model_output("Pixel 8")
                ),
                ("xcrun", "devicectl", "list", "devices"): probe_response(
                    stdout=devicectl_table(
                        ("iPhone 15", "iOS 18.0", "Connected", "00008110-000E196E0E92401E"),
                    )
                ),
            }
        )

        # Act
        manifest = reference_fleet.build_fleet_manifest(
            command_runner=runner,
            environment={},
            development_team_lookup=unresolved_team_lookup,
        )

        # Assert
        mixed_candidate = candidate_by_shape(manifest, "mixed")

        self.assertEqual(manifest["selection"]["status"], "invalid-environment")
        self.assertIsNone(manifest["selectedAssignment"])
        self.assertEqual(mixed_candidate["status"], "invalid-environment")
        self.assertIn("ios-development-team-unresolved", reason_codes(mixed_candidate))

    def test_surfaces_malformed_devicectl_rows_without_losing_a_good_ios_candidate(self) -> None:
        # Arrange
        runner = FakeCommandRunner(
            {
                ("adb", "devices"): probe_response(stdout=adb_devices_output(("android-one", "device"))),
                ("adb", "-s", "android-one", "shell", "getprop", "ro.product.model"): probe_response(
                    stdout=android_model_output("Pixel 8")
                ),
                (
                    "xcrun",
                    "devicectl",
                    "list",
                    "devices",
                ): probe_response(
                    stdout=(
                        devicectl_table(
                            ("iPhone 15", "iOS 18.0", "Connected", "00008110-000E196E0E92401E"),
                        )
                        + "Broken iPhone | iOS 18.0 | Connected\n"
                    )
                ),
            }
        )

        # Act
        manifest = reference_fleet.build_fleet_manifest(
            command_runner=runner,
            environment={},
            development_team_lookup=resolved_team_lookup,
        )

        # Assert
        self.assertEqual(manifest["selection"]["status"], "selected")
        self.assertEqual(manifest["selectedAssignment"]["shape"], "mixed")
        self.assertEqual(manifest["tooling"]["devicectl"]["status"], "ready-with-warnings")
        self.assertIn("ios-devicectl-row-malformed", reason_codes(manifest["tooling"]["devicectl"]))

    def test_prefers_mixed_candidate_and_records_all_discovered_devices_with_extra_android_peers(self) -> None:
        # Arrange
        runner = FakeCommandRunner(
            {
                ("adb", "devices"): probe_response(
                    stdout=adb_devices_output(
                        ("android-passive", "device"),
                        ("android-relay", "device"),
                        ("android-spare", "device"),
                    )
                ),
                ("adb", "-s", "android-passive", "shell", "getprop", "ro.product.model"): probe_response(
                    stdout=android_model_output("Pixel 8")
                ),
                ("adb", "-s", "android-relay", "shell", "getprop", "ro.product.model"): probe_response(
                    stdout=android_model_output("Galaxy S24")
                ),
                ("adb", "-s", "android-spare", "shell", "getprop", "ro.product.model"): probe_response(
                    stdout=android_model_output("Pixel Fold")
                ),
                ("xcrun", "devicectl", "list", "devices"): probe_response(
                    stdout=devicectl_table(
                        ("iPhone 15", "iOS 18.0", "Connected", "00008110-000E196E0E92401E"),
                        ("iPhone 14", "iOS 17.6", "Connected", "00008120-000F196F0E92401F"),
                    )
                ),
            }
        )

        # Act
        manifest = reference_fleet.build_fleet_manifest(
            command_runner=runner,
            environment={},
            development_team_lookup=resolved_team_lookup,
        )

        # Assert
        mixed_candidate = candidate_by_shape(manifest, "mixed")
        android_only_candidate = candidate_by_shape(manifest, "android-only")
        ios_aliases = [device["alias"] for device in manifest["devices"] if device["platform"] == "ios"]
        android_aliases = [device["alias"] for device in manifest["devices"] if device["platform"] == "android"]

        self.assertEqual(manifest["selection"]["status"], "selected")
        self.assertEqual(manifest["selectedAssignment"]["shape"], "android-only")
        self.assertEqual(mixed_candidate["participants"]["sender"], ios_aliases[0])
        self.assertEqual(mixed_candidate["participants"]["passive"], android_aliases[0])
        self.assertEqual(mixed_candidate["consideredDeviceAliases"]["ios"], ios_aliases)
        self.assertEqual(mixed_candidate["consideredDeviceAliases"]["android"], android_aliases)
        self.assertEqual(android_only_candidate["participants"], {"sender": android_aliases[0], "passive": android_aliases[1]})

    def test_marks_extra_android_devices_unhealthy_without_breaking_android_only_selection(self) -> None:
        # Arrange
        runner = FakeCommandRunner(
            {
                ("adb", "devices"): probe_response(
                    stdout=adb_devices_output(
                        ("android-one", "device"),
                        ("android-two", "device"),
                        ("android-three", "offline"),
                    )
                ),
                ("adb", "-s", "android-one", "shell", "getprop", "ro.product.model"): probe_response(
                    stdout=android_model_output("Pixel 8")
                ),
                ("adb", "-s", "android-two", "shell", "getprop", "ro.product.model"): probe_response(
                    stdout=android_model_output("Pixel Fold")
                ),
                ("adb", "-s", "android-three", "shell", "getprop", "ro.product.model"): probe_response(
                    stdout=android_model_output("Galaxy S22")
                ),
                ("xcrun", "devicectl", "list", "devices"): probe_response(stdout=devicectl_table()),
            }
        )

        # Act
        manifest = reference_fleet.build_fleet_manifest(
            command_runner=runner,
            environment={},
            development_team_lookup=resolved_team_lookup,
        )

        # Assert
        android_only_candidate = candidate_by_shape(manifest, "android-only")
        android_aliases = [device["alias"] for device in manifest["devices"] if device["platform"] == "android"]

        self.assertEqual(manifest["selection"]["status"], "selected")
        self.assertEqual(manifest["selectedAssignment"]["shape"], "android-only")
        self.assertEqual(android_only_candidate["status"], "runnable")
        self.assertEqual(len(android_only_candidate["consideredDeviceAliases"]["android"]), 3)
        self.assertEqual(len(set(android_only_candidate["participants"].values())), 2)
        for alias in android_only_candidate["participants"].values():
            self.assertIn(alias, android_only_candidate["consideredDeviceAliases"]["android"])
        self.assertIn("android-only-direct-guided-runnable", reason_codes(android_only_candidate))

    def test_write_manifest_round_trips_through_json(self) -> None:
        # Arrange
        runner = FakeCommandRunner(
            {
                ("adb", "devices"): probe_response(stdout=adb_devices_output(("android-one", "device"))),
                ("adb", "-s", "android-one", "shell", "getprop", "ro.product.model"): probe_response(
                    stdout=android_model_output("Pixel 8")
                ),
                ("xcrun", "devicectl", "list", "devices"): probe_response(stdout=devicectl_table()),
            }
        )
        manifest = reference_fleet.build_fleet_manifest(
            command_runner=runner,
            environment={},
            development_team_lookup=resolved_team_lookup,
        )

        # Act
        with tempfile.TemporaryDirectory() as temporary_directory:
            manifest_path = Path(temporary_directory) / "fleet-manifest.json"
            reference_fleet.write_manifest(manifest_path, manifest)
            persisted = reference_fleet.read_manifest(manifest_path)

        # Assert
        self.assertEqual(persisted["selection"], manifest["selection"])
        self.assertEqual(persisted["candidateAssignments"], manifest["candidateAssignments"])

    def test_subprocess_runner_rejects_shell_style_string_commands(self) -> None:
        # Arrange / Act / Assert
        with self.assertRaises(TypeError):
            reference_fleet.subprocess_runner("adb devices")


if __name__ == "__main__":
    unittest.main()
