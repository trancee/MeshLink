package ch.trancee.meshlink.buildlogic

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class SuppressionsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun deprecationSuppressionInAListedFilePasses() {
        // Arrange
        val root =
            sources(
                "m/src/androidMain/kotlin/Legacy.kt" to "@Suppress(\"DEPRECATION\")\nfun f() = Unit\n",
                "m/src/commonTest/kotlin/T.kt" to "@Suppress(\"UNUSED\")\nfun t() = Unit\n",
            )
        root.resolve(LIST).writeText("# comment\n\nm/src/androidMain/kotlin/Legacy.kt\n")

        // Act
        task(root).check()

        // Assert: no exception.
    }

    @Test
    fun suppressionInAnUnlistedMainFileFailsTheCheck() {
        // Arrange
        val root = sources("m/src/commonMain/kotlin/A.kt" to "@Suppress(\"DEPRECATION\")\nfun f() = Unit\n")
        root.resolve(LIST).writeText("")

        // Act
        val failure = assertFailsWith<GradleException> { task(root).check() }

        // Assert
        assertContains(failure.message.orEmpty(), "m/src/commonMain/kotlin/A.kt:1: @Suppress(\"DEPRECATION\")")
    }

    @Test
    fun otherSuppressionInAListedFileFailsTheCheck() {
        // Arrange
        val root =
            sources(
                "m/src/androidMain/kotlin/Legacy.kt" to
                    "@Suppress(\"DEPRECATION\")\nfun f() = Unit\n@file:Suppress(\"MagicNumber\")\n",
            )
        root.resolve(LIST).writeText("m/src/androidMain/kotlin/Legacy.kt\n")

        // Act
        val failure = assertFailsWith<GradleException> { task(root).check() }

        // Assert
        assertContains(failure.message.orEmpty(), "m/src/androidMain/kotlin/Legacy.kt:3: @file:Suppress(\"MagicNumber\")")
    }

    @Test
    fun listedFileWithoutADeprecationSuppressionIsStale() {
        // Arrange
        val root = sources("m/src/androidMain/kotlin/Plain.kt" to "fun f() = Unit\n")
        root.resolve(LIST).writeText("m/src/androidMain/kotlin/Plain.kt\nm/src/androidMain/kotlin/Gone.kt\n")

        // Act
        val failure = assertFailsWith<GradleException> { task(root).check() }

        // Assert
        assertContains(failure.message.orEmpty(), "stale: m/src/androidMain/kotlin/Plain.kt")
        assertContains(failure.message.orEmpty(), "stale: m/src/androidMain/kotlin/Gone.kt")
    }

    private fun sources(vararg files: Pair<String, String>): File {
        val root = temporaryFolder.newFolder()
        files.forEach { (path, text) -> root.resolve(path).apply { parentFile.mkdirs() }.writeText(text) }
        return root
    }

    private fun task(root: File): CheckSuppressions {
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        return project.tasks
            .register("gate", CheckSuppressions::class.java) {
                list.set(root.resolve(LIST))
                Suppressions.configure(this, project)
            }.get()
    }

    private companion object {
        const val LIST = "legacy-platform-apis.txt"
    }
}
