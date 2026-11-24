package ch.trancee.meshlink.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class SecurityFloorsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun requestBelowTheFloorResolvesToTheFloor() {
        // Arrange
        val project = project("com.google.guava:guava:33.4.0-jre", "com.google.guava:guava:33.8.0-jre")
        SecurityFloors.apply(project, listOf(SecurityFloor("com.google.guava", "guava", "33.8.0-jre")))

        // Act
        val resolved = resolve(project, "com.google.guava:guava:33.4.0-jre")

        // Assert
        assertEquals(listOf("com.google.guava:guava:33.8.0-jre"), resolved)
    }

    @Test
    fun requestAtOrAboveTheFloorIsKept() {
        // Arrange
        val project = project("io.netty:netty-codec:4.2.0.Final", "io.netty:netty-codec:4.1.141.Final")
        SecurityFloors.apply(project, listOf(SecurityFloor("io.netty", "netty-codec", "4.1.141.Final")))

        // Act
        val resolved = resolve(project, "io.netty:netty-codec:4.2.0.Final")

        // Assert
        assertEquals(listOf("io.netty:netty-codec:4.2.0.Final"), resolved)
    }

    @Test
    fun moduleWithoutAFloorIsKept() {
        // Arrange
        val project = project("org.example:other:1.0", "com.google.guava:guava:33.8.0-jre")
        SecurityFloors.apply(project, listOf(SecurityFloor("com.google.guava", "guava", "33.8.0-jre")))

        // Act
        val resolved = resolve(project, "org.example:other:1.0")

        // Assert
        assertEquals(listOf("org.example:other:1.0"), resolved)
    }

    @Test
    fun versionsCompareByNumericSegments() {
        // Arrange
        val cases =
            listOf(
                Triple("4.1.110.Final", "4.1.141.Final", true),
                Triple("4.1.141.Final", "4.1.141.Final", false),
                Triple("2.22", "2.22.4", true),
                Triple("1.86", "1.85", false),
                Triple("33.8.0-android", "33.8.0-jre", false),
                Triple("[1.0,2.0)", "1.5", false),
                Triple("", "1.0", false),
            )

        // Act
        val actual = cases.map { (requested, floor, _) -> SecurityFloors.isBelow(requested, floor) }

        // Assert
        assertEquals(cases.map { it.third }, actual)
    }

    private fun project(vararg modules: String): Project {
        val repository = temporaryFolder.newFolder("repository")
        modules.forEach { pom(repository, it) }
        val project = ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder("project")).build()
        project.repositories.maven { setUrl(repository.toURI()) }
        return project
    }

    private fun resolve(
        project: Project,
        notation: String,
    ): List<String> {
        val configuration = project.configurations.create("tool")
        project.dependencies.add("tool", notation)
        return configuration.incoming.resolutionResult.allComponents
            .map { it.id }
            .filterIsInstance<ModuleComponentIdentifier>()
            .map { it.displayName }
    }

    private fun pom(
        repository: File,
        coordinates: String,
    ) {
        val (group, name, version) = coordinates.split(":")
        val directory = repository.resolve("${group.replace('.', '/')}/$name/$version").apply { mkdirs() }
        directory.resolve("$name-$version.pom").writeText(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>$group</groupId>
              <artifactId>$name</artifactId>
              <version>$version</version>
              <packaging>pom</packaging>
            </project>
            """.trimIndent(),
        )
    }
}
