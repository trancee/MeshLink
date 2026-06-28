from __future__ import annotations

import importlib.util
import sys
import unittest

from support import SCRIPTS_DIR


class AndroidDirectMatrixImportSafetyTests(unittest.TestCase):
    def test_script_loads_without_preseeded_scripts_dir(self) -> None:
        # Arrange
        script_path = SCRIPTS_DIR / "run_headless_reference_android_direct_matrix.py"
        original_sys_path = list(sys.path)
        while str(SCRIPTS_DIR) in sys.path:
            sys.path.remove(str(SCRIPTS_DIR))
        spec = importlib.util.spec_from_file_location("android_direct_matrix_import_check", script_path)
        self.assertIsNotNone(spec)
        self.assertIsNotNone(spec.loader)
        module = importlib.util.module_from_spec(spec)

        try:
            # Act
            spec.loader.exec_module(module)

            # Assert
            self.assertTrue(hasattr(module, "main"))
            self.assertTrue(hasattr(module, "parse_args"))
        finally:
            sys.path[:] = original_sys_path
