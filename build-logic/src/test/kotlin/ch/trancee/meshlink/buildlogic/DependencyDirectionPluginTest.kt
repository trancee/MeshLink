package ch.trancee.meshlink.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class DependencyDirectionPluginTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun forbiddenEdgeInKmpTestSourceSetFailsCheck() {
        // Arrange
        val build = fixtureBuild(allowedEdges(), testEdges = listOf(":meshlink-core" to ":meshlink"))

        // Act
        val result = build.buildAndFail()

        // Assert
        assertContains(result.output, "Dependency direction violated: :meshlink-core depends on :meshlink")
    }

    @Test
    fun allowedGraphPassesCheck() {
        // Arrange
        val build = fixtureBuild(allowedEdges())

        // Act
        val result = build.build()

        // Assert
        val outcomes = MODULES.map { result.task("$it:checkDependencyDirection")?.outcome }
        assertEquals(List(MODULES.size) { TaskOutcome.SUCCESS }, outcomes)
    }

    @Test
    fun moduleWithoutAllowListEntryFailsCheck() {
        // Arrange
        val build = fixtureBuild(allowedEdges(), extraModule = ":meshlink-extra")

        // Act
        val result = build.buildAndFail()

        // Assert
        assertContains(result.output, "Dependency direction unknown: :meshlink-extra has no allow-list entry")
    }

    @Test
    fun kotlinSwiftPmLockFileAggregateIsNotADeclaredEdge() {
        // Arrange
        val build = fixtureBuild(allowedEdges())
        temporaryFolder.root.resolve("meshlink/build.gradle.kts").appendText(
            """

            configurations.create("swiftPMDependenciesForLockFilesMetadataClasspathDependencies")
            dependencies { "swiftPMDependenciesForLockFilesMetadataClasspathDependencies"(project(":meshlink-testing")) }
            """.trimIndent(),
        )

        // Act
        val result = build.build()

        // Assert
        assertEquals(TaskOutcome.SUCCESS, result.task(":meshlink:checkDependencyDirection")?.outcome)
    }

    @Test
    fun checkRunsDependencyDirectionInEveryModule() {
        // Arrange
        val build = fixtureBuild(allowedEdges()).withArguments("check", "--dry-run", "--no-configuration-cache")

        // Act
        val result = build.build()

        // Assert
        MODULES.forEach { assertContains(result.output, "$it:checkDependencyDirection SKIPPED") }
    }

    private fun allowedEdges(): List<Pair<String, String>> =
        listOf(
            ":meshlink-crypto-sodium" to ":meshlink-core",
            ":meshlink" to ":meshlink-core",
            ":meshlink" to ":meshlink-crypto-sodium",
            ":meshlink-testing" to ":meshlink",
        )

    private fun fixtureBuild(
        mainEdges: List<Pair<String, String>>,
        testEdges: List<Pair<String, String>> = emptyList(),
        extraModule: String? = null,
    ): GradleRunner {
        val root = temporaryFolder.root
        val modules = MODULES + listOfNotNull(extraModule)
        root.resolve("settings.gradle.kts").writeText(
            """
            dependencyResolutionManagement { repositories { mavenCentral() } }
            rootProject.name = "fixture"
            include(${modules.joinToString { "\"$it\"" }})
            """.trimIndent(),
        )
        modules.forEach { module ->
            fun dependencies(edges: List<Pair<String, String>>) =
                edges.filter { it.first == module }.joinToString("\n") { "implementation(project(\"${it.second}\"))" }
            moduleDirectory(root, module).resolve("build.gradle.kts").writeText(
                """
                plugins {
                    id("org.jetbrains.kotlin.multiplatform")
                    id("meshlink.dependency-direction")
                }
                kotlin {
                    jvm()
                    sourceSets.commonMain.dependencies {
                        ${dependencies(mainEdges)}
                    }
                    sourceSets.commonTest.dependencies {
                        ${dependencies(testEdges)}
                    }
                }
                """.trimIndent(),
            )
        }
        return GradleRunner
            .create()
            .withProjectDir(root)
            .withPluginClasspath()
            .withArguments("checkDependencyDirection", "--configuration-cache", "--offline", "--stacktrace")
    }

    private fun moduleDirectory(
        root: File,
        module: String,
    ): File = root.resolve(module.removePrefix(":")).also { it.mkdirs() }

    private companion object {
        val MODULES = listOf(":meshlink-core", ":meshlink-crypto-sodium", ":meshlink", ":meshlink-testing")
    }
}
