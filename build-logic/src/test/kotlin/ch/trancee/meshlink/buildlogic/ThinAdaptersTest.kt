package ch.trancee.meshlink.buildlogic

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class ThinAdaptersTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun listMatchingEveryNativeMainFilePasses() {
        // Arrange
        val root =
            sources("m/src/iosMain/kotlin/A.kt", "m/src/appleMain/kotlin/B.kt", "m/src/commonMain/kotlin/C.kt", "m/src/iosTest/kotlin/D.kt")
        root.resolve(LIST).writeText("# comment\n\nm/src/appleMain/kotlin/B.kt\nm/src/iosMain/kotlin/A.kt\n")

        // Act
        task(root).check()

        // Assert: no exception.
    }

    @Test
    fun unlistedNativeMainFileFailsTheCheck() {
        // Arrange
        val root = sources("m/src/iosMain/kotlin/A.kt", "m/src/nativeMain/kotlin/N.kt")
        root.resolve(LIST).writeText("m/src/iosMain/kotlin/A.kt\n")

        // Act
        val failure = assertFailsWith<GradleException> { task(root).check() }

        // Assert
        assertContains(failure.message.orEmpty(), "unlisted: m/src/nativeMain/kotlin/N.kt")
    }

    @Test
    fun listedFileThatNoLongerExistsFailsTheCheck() {
        // Arrange
        val root = sources("m/src/iosMain/kotlin/A.kt")
        root.resolve(LIST).writeText("m/src/iosMain/kotlin/A.kt\nm/src/iosMain/kotlin/Gone.kt\n")

        // Act
        val failure = assertFailsWith<GradleException> { task(root).check() }

        // Assert
        assertContains(failure.message.orEmpty(), "stale: m/src/iosMain/kotlin/Gone.kt")
    }

    private fun sources(vararg paths: String): File {
        val root = temporaryFolder.newFolder()
        paths.forEach { root.resolve(it).apply { parentFile.mkdirs() }.writeText("package p\n") }
        return root
    }

    private fun task(root: File): CheckThinAdapters {
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        return project.tasks
            .register("gate", CheckThinAdapters::class.java) {
                list.set(root.resolve(LIST))
                ThinAdapters.configure(this, project)
            }.get()
    }

    private companion object {
        const val LIST = "thin-adapters.txt"
    }
}
