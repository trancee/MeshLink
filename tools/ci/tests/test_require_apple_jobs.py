import os
import re
import subprocess
import unittest
from pathlib import Path


WORKFLOW = Path(__file__).resolve().parents[3] / ".github" / "workflows" / "build.yml"


def run_gate(status):
    lines = WORKFLOW.read_text().splitlines()
    gate = lines.index("      - name: Require all Apple phases")
    command = lines[gate + 1].strip().removeprefix("run: ")
    return subprocess.run(["bash", "-c", command], env={**os.environ, "APPLE_RESULT": status}, capture_output=True)


class RequireAppleJobsTest(unittest.TestCase):
    def test_required_check_aggregates_the_apple_matrix(self):
        workflow = WORKFLOW.read_text()
        apple = re.search(r"(?ms)^  apple:\n(.*?)(?=^  macos:)", workflow)
        macos = re.search(r"(?ms)^  macos:\n(.*?)(?=^  xcode-27:)", workflow)

        self.assertIsNotNone(apple)
        self.assertIsNotNone(macos)
        self.assertIn("phase: [simulator, swiftpm, maven]", apple.group(1))
        self.assertIn("fail-fast: false", apple.group(1))
        self.assertIn("needs: [apple]", macos.group(1))
        self.assertIn("if: always() && github.event_name != 'schedule'", macos.group(1))
        self.assertIn("APPLE_RESULT: ${{ needs.apple.result }}", macos.group(1))
        self.assertIn("phase: [preview]", workflow)
        self.assertIn("steps: *apple", workflow)

    def test_apple_phases_retain_the_required_checks(self):
        workflow = WORKFLOW.read_text()
        checks = (
            ("matrix.phase == 'simulator' || matrix.phase == 'preview'", "./gradlew iosSimulatorArm64Test --profile"),
            ("matrix.phase == 'swiftpm' || matrix.phase == 'preview'", "tools/swiftpm-consumer/test.sh"),
            ("matrix.phase == 'maven'", "./gradlew buildLibsodiumAndroid"),
            ("matrix.phase == 'maven'", "tools/maven-consumer/test.sh"),
            ("matrix.phase == 'swiftpm' || matrix.phase == 'preview'", "./gradlew checkKotlinAbi checkSodiumAppleSize :meshlink:checkFrameworkSymbols"),
        )

        for condition, command in checks:
            with self.subTest(command=command):
                self.assertIn(f"      - if: {condition}\n        run: {command}", workflow)
        self.assertIn("      - name: Install the pinned NDK\n        if: matrix.phase == 'maven'", workflow)
        self.assertIn(
            "if: ${{ !cancelled() && (matrix.phase == 'simulator' || matrix.phase == 'preview') }}",
            workflow,
        )

    def test_succeeds_when_all_matrix_jobs_succeed(self):
        result = run_gate("success")

        self.assertEqual(result.returncode, 0)

    def test_rejects_non_success_and_missing_results(self):
        for status in ("failure", "cancelled", "skipped", ""):
            with self.subTest(status=status):
                result = run_gate(status)

                self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
