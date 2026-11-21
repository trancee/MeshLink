import importlib.util
import unittest
from pathlib import Path
from unittest.mock import patch


MODULE = Path(__file__).resolve().parents[1] / "simulator_diagnostics.py"
spec = importlib.util.spec_from_file_location("simulator_diagnostics", MODULE)
diagnostics = importlib.util.module_from_spec(spec)
spec.loader.exec_module(diagnostics)


class SimulatorDiagnosticsTest(unittest.TestCase):
    def test_summarizes_destinations_and_inventory_without_identifiers(self):
        destinations = """
Available destinations for the scheme:
  { platform:iOS Simulator, arch:arm64, id:SECRET-ID, OS:26.5, name:iPhone SECRET-NAME }
  { platform:iOS, id:PHYSICAL-ID, name:PHYSICAL-NAME }
Ineligible destinations for the scheme:
  { platform:iOS Simulator, id:OTHER-ID, name:iPhone OTHER-NAME, error:PRIVATE-ERROR }
"""
        runtimes = {
            "runtimes": [
                {"identifier": "com.apple.CoreSimulator.SimRuntime.iOS-26-5", "version": "26.5", "isAvailable": True},
            ]
        }
        devices = {
            "devices": {
                "com.apple.CoreSimulator.SimRuntime.iOS-26-5": [
                    {"name": "iPhone SECRET-NAME", "udid": "fixture-secret", "isAvailable": True},
                    {"name": "iPad PRIVATE-NAME", "udid": "fixture-other", "isAvailable": False},
                ]
            }
        }

        report = diagnostics.summarize(destinations, runtimes, devices)

        self.assertIn("available: os=26.5 iphone=True id=True error=False", report)
        self.assertIn("ineligible: os=unknown iphone=True id=True error=True", report)
        self.assertIn("iOS 26.5: runtime_available=True devices=2 available=1 iphones=1", report)
        for value in ("SECRET", "PHYSICAL", "OTHER", "PRIVATE", "fixture-"):
            self.assertNotIn(value, report)

    def test_empty_destinations_and_inventory_are_explicit(self):
        report = diagnostics.summarize("Available destinations:\n", {"runtimes": []}, {"devices": {}})

        self.assertIn("simulator destinations: 0", report)
        self.assertIn("iOS runtimes: 0", report)

    def test_destination_structure_detects_parser_mismatch_without_exposing_values(self):
        destinations = (
            "Available destinations:\n"
            "{ platform: iOS Simulator,name: iPhone PRIVATE,OS: 26.4,id: SECRET }\n"
            "{ platform:iOS Simulator, arch:arm64, id:OTHER, OS:26.4, name:iPhone OTHER }\n"
        )

        report = diagnostics.summarize(destinations, {"runtimes": []}, {"devices": {}})

        self.assertIn("simulator lines=2 iphone lines=2 numeric os lines=2", report)
        self.assertIn("selector-compatible lines=1", report)
        for value in ("PRIVATE", "SECRET", "OTHER"):
            self.assertNotIn(value, report)

    def test_probe_uses_available_iphone_and_redacts_command_and_output(self):
        runtimes = {"runtimes": [
            {"identifier": "com.apple.CoreSimulator.SimRuntime.iOS-26-4", "version": "26.4", "isAvailable": True},
            {"identifier": "com.apple.CoreSimulator.SimRuntime.iOS-26-5", "version": "26.5", "isAvailable": False},
        ]}
        devices = {"devices": {
            "com.apple.CoreSimulator.SimRuntime.iOS-26-4": [
                {"name": "iPhone PRIVATE", "udid": "fixture-secret", "isAvailable": True},
            ],
        }}
        with patch.object(diagnostics.subprocess, "run") as run:
            run.return_value.returncode = 70
            run.return_value.stderr = "Unable to find a destination fixture-secret PRIVATE"

            report = diagnostics.probe_destination(runtimes, devices)

        self.assertIn("destination probe exit code: 70", report)
        self.assertIn("destination not found=True", report)
        self.assertNotIn("fixture-secret", report)
        self.assertNotIn("PRIVATE", report)
        self.assertIn("platform=iOS Simulator,id=fixture-secret", run.call_args.args[0])
        self.assertIn("test", run.call_args.args[0])

    def test_probe_without_available_iphone_does_not_run_xcode(self):
        with patch.object(diagnostics.subprocess, "run") as run:
            report = diagnostics.probe_destination({"runtimes": []}, {"devices": {}})

        self.assertEqual(report, "destination probe: no available iPhone")
        run.assert_not_called()

    def test_probe_timeout_does_not_leak_command(self):
        runtimes = {"runtimes": [
            {"identifier": "com.apple.CoreSimulator.SimRuntime.iOS-26-4", "version": "26.4", "isAvailable": True},
        ]}
        devices = {"devices": {
            "com.apple.CoreSimulator.SimRuntime.iOS-26-4": [
                {"name": "iPhone PRIVATE", "udid": "fixture-secret", "isAvailable": True},
            ],
        }}
        with patch.object(diagnostics.subprocess, "run", side_effect=diagnostics.subprocess.TimeoutExpired("fixture-secret", 1)):
            report = diagnostics.probe_destination(runtimes, devices)

        self.assertEqual(report, "destination probe: unable to complete")


if __name__ == "__main__":
    unittest.main()
