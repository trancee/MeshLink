package ch.trancee.meshlink.buildlogic

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DocRegionsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun blockMatchingItsDedentedRegionPasses() {
        // Arrange
        val root = repository(doc = page(KOTLIN_MARKER, "```kotlin", "val node = create()", "node.start()", "```"))

        // Act
        task(root, update = false).run()

        // Assert: no exception.
    }

    @Test
    fun changedLineFailsTheCheck() {
        // Arrange
        val root = repository(doc = page(KOTLIN_MARKER, "```kotlin", "val node = create()", "node.stop()", "```"))

        // Act
        val failure = assertFailsWith<GradleException> { task(root, update = false).run() }

        // Assert
        assertContains(failure.message.orEmpty(), "docs/page.md:3: block differs from $KOTLIN_SOURCE#start")
    }

    @Test
    fun missingRegionFailsTheCheck() {
        // Arrange
        val root = repository(doc = page("<!-- region: $KOTLIN_SOURCE#gone -->", "```kotlin", "x", "```"))

        // Act
        val failure = assertFailsWith<GradleException> { task(root, update = false).run() }

        // Assert
        assertContains(failure.message.orEmpty(), "docs/page.md:3: no region gone in $KOTLIN_SOURCE")
    }

    @Test
    fun missingSourceFileFailsTheCheck() {
        // Arrange
        val root = repository(doc = page("<!-- region: m/src/commonTest/kotlin/Gone.kt#start -->", "```kotlin", "x", "```"))

        // Act
        val failure = assertFailsWith<GradleException> { task(root, update = false).run() }

        // Assert
        assertContains(failure.message.orEmpty(), "docs/page.md:3: no file m/src/commonTest/kotlin/Gone.kt")
    }

    @Test
    fun markerWithoutAFenceBelowItFailsTheCheck() {
        // Arrange
        val root = repository(doc = page(KOTLIN_MARKER, "", "```kotlin", "val node = create()", "node.start()", "```"))

        // Act
        val failure = assertFailsWith<GradleException> { task(root, update = false).run() }

        // Assert
        assertContains(failure.message.orEmpty(), "docs/page.md:3: orphan region marker: no fenced block on the next line")
    }

    @Test
    fun regionOutsideATestSourceFailsTheCheck() {
        // Arrange
        val root = repository(doc = page("<!-- region: m/src/commonMain/kotlin/Main.kt#start -->", "```kotlin", "x", "```"))
        root.resolve("m/src/commonMain/kotlin/Main.kt").apply { parentFile.mkdirs() }.writeText("// region start\nx\n// endregion\n")

        // Act
        val failure = assertFailsWith<GradleException> { task(root, update = false).run() }

        // Assert
        assertContains(failure.message.orEmpty(), "docs/page.md:3: m/src/commonMain/kotlin/Main.kt is not a test source")
    }

    @Test
    fun unterminatedRegionFailsTheCheck() {
        // Arrange
        val root = repository(doc = page(SWIFT_MARKER, "```swift", "try await node.start()", "```"))
        root.resolve(SWIFT_SOURCE).writeText("func testStart() async throws {\n    // region start\n    try await node.start()\n}\n")

        // Act
        val failure = assertFailsWith<GradleException> { task(root, update = false).run() }

        // Assert
        assertContains(failure.message.orEmpty(), "docs/page.md:3: region start in $SWIFT_SOURCE has no // endregion")
    }

    @Test
    fun swiftRegionInAnXCTestFileMatches() {
        // Arrange
        val root = repository(doc = page(SWIFT_MARKER, "```swift", "try await node.start()", "```"))

        // Act
        task(root, update = false).run()

        // Assert: no exception.
    }

    @Test
    fun updateRewritesEveryTiedBlockFromItsRegion() {
        // Arrange
        val root = repository(doc = page(KOTLIN_MARKER, "```kotlin", "old", "```", "", SWIFT_MARKER, "```swift", "old", "```"))

        // Act
        task(root, update = true).run()

        // Assert
        val expected =
            page(
                KOTLIN_MARKER,
                "```kotlin",
                "val node = create()",
                "node.start()",
                "```",
                "",
                SWIFT_MARKER,
                "```swift",
                "try await node.start()",
                "```",
            )
        assertEquals(expected, root.resolve(DOC).readText())
    }

    private fun repository(doc: String): File {
        val root = temporaryFolder.newFolder()
        root.resolve(DOC).apply { parentFile.mkdirs() }.writeText(doc)
        root.resolve(KOTLIN_SOURCE).apply { parentFile.mkdirs() }.writeText(
            """
            |class StartTest {
            |    fun start() {
            |        // region start
            |        val node = create()
            |        node.start()
            |        // endregion
            |    }
            |}
            |
            """.trimMargin(),
        )
        root.resolve(SWIFT_SOURCE).apply { parentFile.mkdirs() }.writeText(
            "func testStart() async throws {\n    // region start\n    try await node.start()\n    // endregion\n}\n",
        )
        return root
    }

    private fun page(vararg lines: String) = (listOf("# Page", "") + lines).joinToString("\n", postfix = "\n")

    private fun task(
        root: File,
        update: Boolean,
    ): DocRegionsTask {
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        return project.tasks
            .register("regions", DocRegionsTask::class.java) {
                this.update.set(update)
                DocRegions.configure(this, project)
            }.get()
    }

    private companion object {
        const val DOC = "docs/page.md"
        const val KOTLIN_SOURCE = "m/src/commonTest/kotlin/StartTest.kt"
        const val SWIFT_SOURCE = "tools/consumer/Tests/AppTests/StartTests.swift"
        const val KOTLIN_MARKER = "<!-- region: $KOTLIN_SOURCE#start -->"
        const val SWIFT_MARKER = "<!-- region: $SWIFT_SOURCE#start -->"
    }
}
