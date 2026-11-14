package ch.trancee.meshlink.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ChangelogPluginTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun abiDumpChangeWithoutChangelogChangeFailsCheck() {
        // Arrange
        val root = committedFixture()
        root.resolve("meshlink/api/android/meshlink.api").appendText("public final class Added\n")

        // Act
        val result = runner(root).buildAndFail()

        // Assert
        assertContains(result.output, "ABI dump changed without a CHANGELOG.md change: meshlink/api/android/meshlink.api")
    }

    @Test
    fun newAbiDumpWithoutChangelogChangeFailsCheck() {
        // Arrange
        val root = committedFixture()
        root.resolve("meshlink-testing/api").mkdirs()
        root.resolve("meshlink-testing/api/meshlink-testing.klib.api").writeText("// klib ABI dump\n")

        // Act
        val result = runner(root).buildAndFail()

        // Assert
        assertContains(result.output, "meshlink-testing/api/meshlink-testing.klib.api")
    }

    @Test
    fun abiDumpChangeWithChangelogChangePassesCheck() {
        // Arrange
        val root = committedFixture()
        root.resolve("meshlink/api/android/meshlink.api").appendText("public final class Added\n")
        root.resolve("CHANGELOG.md").appendText("- Added `Added` (MINOR).\n")

        // Act
        val result = runner(root).build()

        // Assert
        assertEquals(TaskOutcome.SUCCESS, result.task(":checkChangelog")?.outcome)
    }

    @Test
    fun missingBaseRefFailsClosed() {
        // Arrange
        val root = committedFixture()

        // Act
        val result = runner(root, base = "origin/absent").buildAndFail()

        // Assert
        assertContains(result.output, "checkChangelog cannot find the base ref origin/absent")
    }

    private fun committedFixture(): File {
        val root = temporaryFolder.root
        root.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"\n")
        root.resolve("build.gradle.kts").writeText("plugins { id(\"meshlink.changelog\") }\n")
        root.resolve("CHANGELOG.md").writeText("# Changelog\n\n## Unreleased\n\n")
        root.resolve("meshlink/api/android").mkdirs()
        root.resolve("meshlink/api/android/meshlink.api").writeText("public final class Existing\n")
        root.resolve(".gitignore").writeText(".gradle/\nbuild/\n")
        git(root, "init", "-q", "-b", "main")
        git(root, "add", "-A")
        git(root, "-c", "user.name=fixture", "-c", "user.email=fixture", "commit", "-q", "-m", "base")
        git(root, "switch", "-q", "-c", "feature")
        return root
    }

    private fun runner(
        root: File,
        base: String = "main",
    ): GradleRunner =
        GradleRunner
            .create()
            .withProjectDir(root)
            .withPluginClasspath()
            .withArguments("check", "-Pmeshlink.changelog.base=$base", "--configuration-cache", "--stacktrace")

    private fun git(
        root: File,
        vararg args: String,
    ) {
        val process = ProcessBuilder(listOf("git") + args).directory(root).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
    }
}
