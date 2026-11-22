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
    def test_linux_build_uses_self_hosted_runner_except_for_forks(self):
        linux = re.search(r"(?ms)^  linux:\n(.*?)(?=^  apple:)", WORKFLOW.read_text()).group(1)
        fork = "github.event.pull_request.head.repo.fork"

        self.assertIn(
            f"runs-on: ${{{{ {fork} && 'ubuntu-24.04'"
            " || fromJSON('[\"self-hosted\",\"Linux\",\"X64\",\"meshlink-linux\"]') }}",
            linux,
        )
        self.assertIn(
            "name: Install minisign\n        if: runner.environment != 'self-hosted'",
            linux,
        )

    def test_required_check_aggregates_the_apple_matrix(self):
        workflow = WORKFLOW.read_text()
        apple = re.search(r"(?ms)^  apple:\n(.*?)(?=^  macos:)", workflow)
        macos = re.search(r"(?ms)^  macos:\n(.*)", workflow)

        self.assertIsNotNone(apple)
        self.assertIsNotNone(macos)
        self.assertIn("phase: [simulator, swiftpm, maven]", apple.group(1))
        self.assertIn("fail-fast: false", apple.group(1))
        self.assertIn("needs: [apple]", macos.group(1))
        self.assertIn("if: always()\n", macos.group(1))
        self.assertIn("APPLE_RESULT: ${{ needs.apple.result }}", macos.group(1))

    def test_same_repository_runs_use_the_self_hosted_mac_and_forks_the_hosted_image(self):
        apple = re.search(r"(?ms)^  apple:\n(.*?)(?=^  macos:)", WORKFLOW.read_text()).group(1)
        fork = "github.event.pull_request.head.repo.fork"

        self.assertIn(
            f"runs-on: ${{{{ {fork} && 'macos-26' || fromJSON('[\"self-hosted\",\"macOS\",\"ARM64\"]') }}}}",
            apple,
        )
        self.assertIn(
            f"DEVELOPER_DIR: ${{{{ {fork} && '/Applications/Xcode_26.4.1.app/Contents/Developer'"
            " || '/Applications/Xcode-27.2.0.app/Contents/Developer' }}",
            apple,
        )

    def test_the_self_hosted_mac_skips_the_actions_cache_for_its_persistent_homes(self):
        apple = re.search(r"(?ms)^  apple:\n(.*?)(?=^  macos:)", WORKFLOW.read_text()).group(1)
        self_hosted = "runner.environment == 'self-hosted'"
        hosted = "runner.environment != 'self-hosted'"

        self.assertIn(f"cache-disabled: ${{{{ {self_hosted} }}}}", apple)
        restore = re.search(r"(?ms)- name: Restore Kotlin/Native dependencies\n(.*?)(?=^      - )", apple).group(1)
        save = re.search(r"(?ms)- name: Save Kotlin/Native dependencies\n(.*?)(?=^      - |\Z)", apple).group(1)
        self.assertIn(f"if: {hosted}\n", restore)
        self.assertIn(f"if: {hosted} && github.ref == 'refs/heads/main'", save)

    def test_no_scheduled_preview_job_remains(self):
        workflow = WORKFLOW.read_text()

        self.assertNotIn("schedule", workflow)
        self.assertNotIn("xcode-27", workflow)
        self.assertNotIn("preview", workflow)

    def test_apple_phases_retain_the_required_checks(self):
        workflow = WORKFLOW.read_text()
        checks = (
            ("matrix.phase == 'simulator'", "./gradlew iosSimulatorArm64Test --profile"),
            ("matrix.phase == 'swiftpm'", "tools/swiftpm-consumer/test.sh"),
            ("matrix.phase == 'maven'", "./gradlew buildLibsodiumAndroid"),
            ("matrix.phase == 'maven'", "tools/maven-consumer/test.sh"),
            ("matrix.phase == 'swiftpm'", "./gradlew checkKotlinAbi checkSodiumAppleSize :meshlink:checkFrameworkSymbols"),
        )

        for condition, command in checks:
            with self.subTest(command=command):
                self.assertIn(f"      - if: {condition}\n        run: {command}", workflow)
        self.assertIn("      - name: Install the pinned NDK\n        if: matrix.phase == 'maven'", workflow)
        self.assertIn("if: ${{ !cancelled() && matrix.phase == 'simulator' }}", workflow)

    def test_simulator_diagnostics_run_only_after_swiftpm_failure(self):
        workflow = WORKFLOW.read_text()

        self.assertIn(
            "run: tools/swiftpm-consumer/test.sh\n        id: swiftpm_consumer",
            workflow,
        )
        self.assertIn(
            "if: ${{ failure() && steps.swiftpm_consumer.outcome == 'failure' }}\n"
            "        run: python3 tools/ci/simulator_diagnostics.py",
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
