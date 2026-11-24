import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path


WORKFLOW = Path(__file__).resolve().parents[3] / ".github" / "workflows" / "build.yml"


def run_gate(status, code=""):
    lines = WORKFLOW.read_text().splitlines()
    gate = lines.index("      - name: Require all Apple phases")
    command = lines[gate + 1].strip().removeprefix("run: ")
    env = {**os.environ, "APPLE_RESULT": status, "CODE": code}
    return subprocess.run(["bash", "-c", command], env=env, capture_output=True)


def changes_script():
    lines = WORKFLOW.read_text().splitlines()
    start = lines.index("      - id: diff") + 2
    end = next(i for i in range(start, len(lines)) if not lines[i].startswith("          "))
    return "\n".join(line[10:] for line in lines[start:end])


def run_changes(paths, event="pull_request"):
    """Commits `paths` on top of an empty commit and returns the `code` output the changes step writes."""
    with tempfile.TemporaryDirectory() as repo:
        git = ["git", "-C", repo, "-c", "user.name=t", "-c", "user.email=t@example.com"]
        subprocess.run(["git", "init", "-q", repo], check=True)
        subprocess.run([*git, "commit", "-q", "--allow-empty", "-m", "base"], check=True)
        for path in paths:
            file = Path(repo, path)
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text("x\n")
        subprocess.run([*git, "add", "-A"], check=True)
        subprocess.run([*git, "commit", "-q", "--allow-empty", "-m", "change"], check=True)
        output = Path(repo, "output")
        env = {**os.environ, "GITHUB_EVENT_NAME": event, "GITHUB_OUTPUT": str(output)}
        subprocess.run(["bash", "-c", changes_script()], cwd=repo, env=env, check=True)
        return output.read_text().strip()


class RequireAppleJobsTest(unittest.TestCase):
    def test_linux_build_uses_self_hosted_runner_except_for_forks(self):
        linux = re.search(r"(?ms)^  linux:\n(.*?)(?=^  apple:)", WORKFLOW.read_text()).group(1)
        fork = "github.event.pull_request.head.repo.fork"

        self.assertIn(
            f"runs-on: ${{{{ {fork} && 'ubuntu-24.04'"
            " || fromJSON('[\"self-hosted\",\"Linux\",\"X64\",\"linux-ci\"]') }}",
            linux,
        )
        self.assertIn(
            "name: Install minisign\n        if: runner.environment != 'self-hosted'",
            linux,
        )
        self.assertIn("sha256sum /etc/os-release", linux)

    def test_required_check_aggregates_the_apple_matrix(self):
        workflow = WORKFLOW.read_text()
        apple = re.search(r"(?ms)^  apple:\n(.*?)(?=^  macos:)", workflow)
        macos = re.search(r"(?ms)^  macos:\n(.*)", workflow)

        self.assertIsNotNone(apple)
        self.assertIsNotNone(macos)
        self.assertIn("phase: [simulator, swiftpm, maven]", apple.group(1))
        self.assertIn("fail-fast: false", apple.group(1))
        self.assertIn("needs: [changes, apple]", macos.group(1))
        self.assertIn("if: always()\n", macos.group(1))
        self.assertIn("APPLE_RESULT: ${{ needs.apple.result }}", macos.group(1))
        self.assertIn("CODE: ${{ needs.changes.outputs.code }}", macos.group(1))

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
            for code in ("true", ""):
                with self.subTest(status=status, code=code):
                    result = run_gate(status, code)

                    self.assertNotEqual(result.returncode, 0)

    def test_accepts_skipped_phases_only_without_code_changes(self):
        self.assertEqual(run_gate("skipped", "false").returncode, 0)
        for status in ("failure", "cancelled", ""):
            with self.subTest(status=status):
                self.assertNotEqual(run_gate(status, "false").returncode, 0)

    def test_linux_and_apple_jobs_skip_only_when_changes_reports_no_code(self):
        workflow = WORKFLOW.read_text()
        condition = "    needs: [changes]\n    if: ${{ !cancelled() && needs.changes.outputs.code != 'false' }}\n"

        for job in ("linux", "apple"):
            with self.subTest(job=job):
                body = re.search(rf"(?ms)^  {job}:\n(.*?)(?=^  [a-z]+:\n)", workflow).group(1)
                self.assertIn(condition, body)

    def test_changes_reports_code_unless_a_pull_request_touches_only_markdown(self):
        cases = (
            (["docs/a.md", ".scratch/b/c.md", "README.md"], "pull_request", "code=false"),
            (["docs/spec/vectors/x.json"], "pull_request", "code=true"),
            (["docs/a.md", "build.gradle.kts"], "pull_request", "code=true"),
            ([".github/workflows/build.yml"], "pull_request", "code=true"),
            (["docs/a.md"], "push", "code=true"),
            (["docs/a.md"], "workflow_dispatch", "code=true"),
        )

        for paths, event, expected in cases:
            with self.subTest(paths=paths, event=event):
                self.assertEqual(run_changes(paths, event), expected)


if __name__ == "__main__":
    unittest.main()
