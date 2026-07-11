from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import run_android_proof_fleet as fleet  # noqa: E402


def _write_proof_log(run_root: Path, serial: str, lines: list[str]) -> None:
    log_path = run_root / "logs" / f"{serial}.proof.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


class FullMeshMatrixTests(unittest.TestCase):
    def test_parse_args_full_mesh_defaults_to_disabled(self) -> None:
        # Arrange / Act
        args = fleet.parse_args([])

        # Assert
        self.assertFalse(args.full_mesh)

    def test_full_mesh_rejects_pair_label(self) -> None:
        # Arrange / Act / Assert
        with self.assertRaises(SystemExit):
            fleet.parse_args(["--full-mesh", "--pair-label", "a__b"])

    def test_full_mesh_requires_at_least_two_devices_when_device_list_given(self) -> None:
        # Arrange / Act / Assert
        with self.assertRaises(SystemExit):
            fleet.parse_args(["--full-mesh", "--device", "only-one"])

    def test_full_mesh_allows_more_than_two_explicit_devices(self) -> None:
        # Arrange / Act
        args = fleet.parse_args(
            ["--full-mesh", "--device", "a", "--device", "b", "--device", "c"]
        )

        # Assert
        self.assertTrue(args.full_mesh)
        self.assertEqual(args.devices, ["a", "b", "c"])

    def test_matrix_reports_a_confirmed_round_trip_between_two_devices(self) -> None:
        # Arrange
        device_a = fleet.DeviceRecord(
            listed_id="serial-a", resolved_serial="serial-a", model="Alpha", api_level=34, raw=""
        )
        device_b = fleet.DeviceRecord(
            listed_id="serial-b", resolved_serial="serial-b", model="Beta", api_level=33, raw=""
        )
        with tempfile.TemporaryDirectory() as tmp:
            run_root = Path(tmp)
            _write_proof_log(
                run_root,
                "serial-a",
                [
                    "MeshLink proof app ready on Alpha (SDK 34) keyHash=aaaaaaaaaaaaaaaaaaaaaaaa",
                    "BENCHMARK receipt confirmed peer=bbbbbb token=00001234 bytes=64",
                ],
            )
            _write_proof_log(
                run_root,
                "serial-b",
                [
                    "MeshLink proof app ready on Beta (SDK 33) keyHash=bbbbbbbbbbbbbbbbbbbbbbbb",
                ],
            )

            # Act
            matrix = fleet.build_full_mesh_matrix([device_a, device_b], run_root)

        # Assert
        self.assertEqual(matrix["directedPairsTotal"], 2)
        self.assertEqual(matrix["directedPairsConfirmed"], 1)
        self.assertEqual(len(matrix["confirmedDeliveries"]), 1)
        delivery = matrix["confirmedDeliveries"][0]
        self.assertEqual(delivery["senderSerial"], "serial-a")
        self.assertEqual(delivery["receiverSerial"], "serial-b")
        self.assertEqual(delivery["bytes"], 64)

    def test_matrix_reports_zero_confirmed_when_no_receipts_arrive(self) -> None:
        # Arrange
        device_a = fleet.DeviceRecord(
            listed_id="serial-a", resolved_serial="serial-a", model="Alpha", api_level=34, raw=""
        )
        device_b = fleet.DeviceRecord(
            listed_id="serial-b", resolved_serial="serial-b", model="Beta", api_level=33, raw=""
        )
        with tempfile.TemporaryDirectory() as tmp:
            run_root = Path(tmp)
            _write_proof_log(
                run_root,
                "serial-a",
                ["MeshLink proof app ready on Alpha (SDK 34) keyHash=aaaaaaaaaaaaaaaaaaaaaaaa"],
            )
            _write_proof_log(
                run_root,
                "serial-b",
                ["MeshLink proof app ready on Beta (SDK 33) keyHash=bbbbbbbbbbbbbbbbbbbbbbbb"],
            )

            # Act
            matrix = fleet.build_full_mesh_matrix([device_a, device_b], run_root)

        # Assert
        self.assertEqual(matrix["directedPairsConfirmed"], 0)
        self.assertEqual(matrix["confirmedDeliveries"], [])

    def test_matrix_flags_ambiguous_hint_instead_of_misattributing_it(self) -> None:
        # Arrange: two devices whose key hashes collide on the last 6 hex
        # characters used as the free-text log hint.
        device_a = fleet.DeviceRecord(
            listed_id="serial-a", resolved_serial="serial-a", model="Alpha", api_level=34, raw=""
        )
        device_b = fleet.DeviceRecord(
            listed_id="serial-b", resolved_serial="serial-b", model="Beta", api_level=33, raw=""
        )
        device_c = fleet.DeviceRecord(
            listed_id="serial-c", resolved_serial="serial-c", model="Gamma", api_level=33, raw=""
        )
        with tempfile.TemporaryDirectory() as tmp:
            run_root = Path(tmp)
            _write_proof_log(
                run_root,
                "serial-a",
                [
                    "MeshLink proof app ready on Alpha (SDK 34) keyHash=aaaaaaaaaaaaaaaaaaaaaaaa",
                    "BENCHMARK receipt confirmed peer=cccccc token=00001234 bytes=64",
                ],
            )
            _write_proof_log(
                run_root,
                "serial-b",
                ["MeshLink proof app ready on Beta (SDK 33) keyHash=111111111111111cccccc"],
            )
            _write_proof_log(
                run_root,
                "serial-c",
                ["MeshLink proof app ready on Gamma (SDK 33) keyHash=222222222222222cccccc"],
            )

            # Act
            matrix = fleet.build_full_mesh_matrix([device_a, device_b, device_c], run_root)

        # Assert
        self.assertEqual(matrix["confirmedDeliveries"], [])
        self.assertIn("cccccc", matrix["ambiguousHints"])

    def test_markdown_renders_a_checkmark_only_for_confirmed_directed_pairs(self) -> None:
        # Arrange
        mesh_matrix = {
            "devices": [
                {"serial": "serial-a", "model": "Alpha", "apiLevel": 34, "ownKeyHash": "aa"},
                {"serial": "serial-b", "model": "Beta", "apiLevel": 33, "ownKeyHash": "bb"},
            ],
            "confirmedDeliveries": [
                {"senderSerial": "serial-a", "receiverSerial": "serial-b", "token": "t", "bytes": 64, "line": "x"}
            ],
            "directedPairsTotal": 2,
            "directedPairsConfirmed": 1,
            "ambiguousHints": [],
        }

        # Act
        markdown = fleet.render_full_mesh_matrix_markdown(mesh_matrix)

        # Assert
        self.assertIn("1` / `2`", markdown)
        lines = markdown.splitlines()
        alpha_row = next(line for line in lines if line.startswith("| Alpha "))
        beta_row = next(line for line in lines if line.startswith("| Beta "))
        self.assertIn("✅", alpha_row)
        self.assertIn("—", beta_row)


if __name__ == "__main__":
    unittest.main()
