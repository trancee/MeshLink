from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from support import SCRIPTS_DIR

if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import run_headless_reference_live_proof as live_proof  # noqa: E402
import run_headless_reference_relay_proof as relay_proof  # noqa: E402


class ReferenceRelayProofScriptTests(unittest.TestCase):
    def test_main_force_stops_android_and_ios_apps_even_when_the_passive_device_never_reports_readiness(self) -> None:
        # Arrange: simulate a crash/hang before the iPhone sender is ever launched, so
        # ios_process stays None but the physical iPhone must still be force-quit.
        with tempfile.TemporaryDirectory() as temporary_directory:
            run_dir = Path(temporary_directory) / "relay-proof-crash"
            argv = [
                "run_headless_reference_relay_proof.py",
                "--ios-device",
                "ios-device-123",
                "--relay-android-serial",
                "android-relay-123",
                "--passive-android-serial",
                "android-passive-123",
                "--run-dir",
                str(run_dir),
                "--skip-android-install",
                "--skip-ios-build",
                "--skip-ios-install",
            ]

            class FakeProcess:
                def stop(self) -> None:
                    return None

            with (
                patch.object(sys, "argv", argv),
                patch.object(relay_proof, "ensure_android_device_ready"),
                patch.object(relay_proof, "verify_android_runtime_permissions"),
                patch.object(live_proof, "latest_built_app", return_value=Path("/tmp/fake.app")),
                patch.object(relay_proof, "install_ios_app"),
                patch.object(relay_proof, "start_android_role_app", return_value=FakeProcess()),
                patch.object(
                    relay_proof,
                    "wait_for_android_identity_preferences",
                    side_effect=SystemExit("passive Android app crashed before reporting identity"),
                ),
                patch.object(relay_proof, "force_stop_ios_app") as mock_force_stop_ios,
            ):
                # Act
                with self.assertRaises(SystemExit):
                    relay_proof.main()

            # Assert
            mock_force_stop_ios.assert_called_once_with("ios-device-123")


if __name__ == "__main__":
    unittest.main()
