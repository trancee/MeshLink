import importlib.util
import unittest
from pathlib import Path


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


if __name__ == "__main__":
    unittest.main()
