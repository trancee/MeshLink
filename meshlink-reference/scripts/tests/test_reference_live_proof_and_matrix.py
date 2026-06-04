from __future__ import annotations

import io
import sys
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch

from support import SCRIPTS_DIR

if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import run_headless_reference_live_proof as live_proof  # noqa: E402
import run_headless_reference_physical_matrix as physical_matrix  # noqa: E402


class ReferenceLiveProofScriptTests(unittest.TestCase):
    def test_parse_args_accepts_the_new_resilience_scenario_names(self) -> None:
        # Arrange
        argv = [
            "run_headless_reference_live_proof.py",
            "--android-serial",
            "android-serial-123",
            "--ios-device",
            "ios-device-123",
            "--scenario",
            "direct-route-break-recovery",
        ]

        # Act
        with patch.object(sys, "argv", argv):
            parsed_args = live_proof.parse_args()

        # Assert
        self.assertEqual(parsed_args.scenario, "direct-route-break-recovery")
        self.assertIn("direct-restart-recovery", live_proof.DIRECT_PHYSICAL_SCENARIOS)
        self.assertIn("direct-isolation-recovery", live_proof.DIRECT_PHYSICAL_SCENARIOS)
        self.assertIn("direct-route-break-recovery", live_proof.DIRECT_PHYSICAL_SCENARIOS)


class ReferencePhysicalMatrixScriptTests(unittest.TestCase):
    def test_help_lists_the_new_resilience_scenarios(self) -> None:
        # Arrange
        argv = ["run_headless_reference_physical_matrix.py", "--help"]
        help_stream = io.StringIO()

        # Act
        with patch.object(sys, "argv", argv):
            with redirect_stdout(help_stream):
                with self.assertRaises(SystemExit) as exit_context:
                    physical_matrix.parse_args()

        # Assert
        self.assertEqual(exit_context.exception.code, 0)
        help_text = help_stream.getvalue()
        self.assertIn("direct-restart-", help_text)
        self.assertIn("direct-isolation-", help_text)
        self.assertIn("direct-route-", help_text)

    def test_resolve_scenarios_accepts_the_new_names_without_changing_auto_defaults(self) -> None:
        # Arrange
        explicit_args = physical_matrix.argparse.Namespace(
            scenarios="direct-guided,direct-restart-recovery,direct-route-break-recovery"
        )
        auto_args = physical_matrix.argparse.Namespace(
            scenarios="auto",
            passive_android_serial=None,
            relay_android_serial=None,
        )

        # Act
        explicit_scenarios = physical_matrix.resolve_scenarios(explicit_args)
        auto_scenarios = physical_matrix.resolve_scenarios(auto_args)

        # Assert
        self.assertEqual(
            explicit_scenarios,
            ["direct-guided", "direct-restart-recovery", "direct-route-break-recovery"],
        )
        self.assertEqual(
            auto_scenarios,
            [
                "direct-guided",
                "direct-pause-resume",
                "direct-full-export",
                "direct-trust-reset-recovery",
                "direct-large-transfer",
            ],
        )
        self.assertNotIn("direct-restart-recovery", auto_scenarios)
        self.assertNotIn("direct-isolation-recovery", auto_scenarios)
        self.assertNotIn("direct-route-break-recovery", auto_scenarios)
        self.assertTrue(
            {
                "direct-restart-recovery",
                "direct-isolation-recovery",
                "direct-route-break-recovery",
            }.issubset(physical_matrix.AVAILABLE_SCENARIOS)
        )


if __name__ == "__main__":
    unittest.main()
