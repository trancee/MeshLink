import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


CONSUMER = Path(__file__).resolve().parents[2] / "swiftpm-consumer"


class SwiftpmDestinationTest(unittest.TestCase):
    def run_discovery(self, destinations, devices, xcode_status=0):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            consumer = path / "tools" / "swiftpm-consumer"
            consumer.mkdir(parents=True)
            shutil.copy(CONSUMER / "test.sh", consumer)
            helper = CONSUMER / "discover-destination.py"
            if helper.exists():
                shutil.copy(helper, consumer)
            framework = path / "meshlink" / "build" / "XCFrameworks" / "release" / "MeshLinkKit.xcframework"
            framework.mkdir(parents=True)
            (framework / "Info.plist").touch()
            for name, body in {
                "gradlew": "exit 0",
                "xcodebuild": (
                    'if [ "$1" = test ]; then case "$*" in *"platform=iOS Simulator,id=NEW"*) '
                    'exit 0;; *) exit 2;; esac; fi; printf "%s" "$DESTINATIONS"; '
                    'echo "PRIVATE-STDERR" >&2; exit "$XCODE_STATUS"'
                ),
                "xcrun": 'printf "%s" "$DEVICES"',
            }.items():
                command = path / name
                command.write_text("#!/bin/sh\n" + body + "\n")
                command.chmod(0o755)
            return subprocess.run(
                ["bash", str(consumer / "test.sh")],
                env={
                    **os.environ,
                    "PATH": f"{path}:{os.environ['PATH']}",
                    "DESTINATIONS": destinations,
                    "DEVICES": json.dumps(devices),
                    "XCODE_STATUS": str(xcode_status),
                    "CI": "true",
                },
                capture_output=True,
                text=True,
            )

    def test_selects_newest_available_iphone(self):
        result = self.run_discovery(
            "Available destinations\n"
            "{ platform:iOS Simulator, id:OLD, OS:18.6, name:iPhone 16 }\n"
            "{ platform:iOS Simulator, id:NEW, OS:26.4, name:iPhone 17 }\n"
            "Ineligible destinations\n",
            {},
        )
        self.assertEqual(result.returncode, 0)
        self.assertIn("SwiftPM simulator test:", result.stdout)
        self.assertIn("parsed available iPhone destinations: 2", result.stderr)
        self.assertIn("iPhone destination parse errors: 0", result.stderr)
        self.assertNotIn("PRIVATE", result.stderr)

    def test_failure_reports_counts_without_identifiers(self):
        result = self.run_discovery(
            "Available destinations\n"
            "{ platform:macOS, id:PRIVATE-ID, name:PRIVATE-NAME }\n"
            "Ineligible destinations\n"
            "{ platform:iOS Simulator, id:PRIVATE-ID, name:iPhone SECRET }\n",
            {
                "devices": {
                    "com.apple.CoreSimulator.SimRuntime.iOS-26-4": [
                        {"name": "iPhone PRIVATE-NAME", "udid": "private-id", "isAvailable": True}
                    ]
                }
            },
            xcode_status=4,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("SwiftPM simulator test:", result.stdout)
        self.assertIn("xcodebuild exit status: 4", result.stderr)
        self.assertIn("available iPhone simulators: 1", result.stderr)
        self.assertIn("ineligible destination entries: 1", result.stderr)
        self.assertIn("available lines mentioning iOS Simulator: 0", result.stderr)
        self.assertIn("ineligible lines mentioning iOS Simulator: 1", result.stderr)
        self.assertIn("diagnostic simulator boot status: 0", result.stderr)
        self.assertIn("post-boot iPhone destination lines: 0", result.stderr)
        self.assertNotIn("PRIVATE", result.stderr)
        self.assertNotIn("private-id", result.stderr)
        self.assertNotIn("SECRET", result.stderr)

    def test_malformed_destination_fails_closed(self):
        result = self.run_discovery(
            "Available destinations\n{ platform:iOS Simulator, OS:PRIVATE, name:iPhone SECRET }\n",
            {},
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("iPhone destination parse errors: 1", result.stderr)
        self.assertNotIn("PRIVATE", result.stderr)
        self.assertNotIn("SECRET", result.stderr)


if __name__ == "__main__":
    unittest.main()
