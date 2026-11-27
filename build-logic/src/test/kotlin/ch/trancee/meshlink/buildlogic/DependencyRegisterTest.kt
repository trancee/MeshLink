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

class DependencyRegisterTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun matchingCatalogRegisterAndWorkflowsPass() {
        // Arrange
        val root = repository()

        // Act
        task(root).check()

        // Assert: no exception.
    }

    @Test
    fun catalogEntryWithoutARowFails() {
        // Arrange
        val root = repository(register = REGISTER.replace("| Jazzer | 0.30.0 | `jazzer`, `jazzer-api` |", "| Jazzer | 0.30.0 | `jazzer` |"))

        // Act
        val failure = assertFailsWith<GradleException> { task(root).check() }

        // Assert
        assertEquals("$HEADER\nmissing row: jazzer-api", failure.message)
    }

    @Test
    fun rowForNoCatalogEntryAndAliasListedTwiceFail() {
        // Arrange
        val root =
            repository(
                register =
                    REGISTER.replace(
                        "| Netty | 4.1.141.Final | `floor-netty-codec` |",
                        "| Netty | 4.1.141.Final | `floor-netty-codec`, `floor-netty-gone`, `jazzer` |",
                    ),
            )

        // Act
        val failure = assertFailsWith<GradleException> { task(root).check() }

        // Assert
        assertEquals(
            "$HEADER\n" +
                "listed twice: jazzer\n" +
                "no catalog entry: floor-netty-gone\n" +
                "version mismatch: jazzer is 0.30.0 in the catalog, the row says 4.1.141.Final",
            failure.message,
        )
    }

    @Test
    fun rowVersionThatDiffersFromTheCatalogFails() {
        // Arrange
        val root = repository(catalog = CATALOG.replace("kotlin = \"2.4.21\"", "kotlin = \"2.4.30\""))

        // Act
        val failure = assertFailsWith<GradleException> { task(root).check() }

        // Assert
        assertEquals(
            "$HEADER\nversion mismatch: plugin:kotlin-multiplatform is 2.4.30 in the catalog, the row says 2.4.21",
            failure.message,
        )
    }

    @Test
    fun versionKeyNoLibraryUsesNeedsAVersionToken() {
        // Arrange
        val root = repository(catalog = CATALOG.replace("[libraries]", "android-minSdk = \"26\"\n\n[libraries]"))

        // Act
        val failure = assertFailsWith<GradleException> { task(root).check() }

        // Assert
        assertEquals("$HEADER\nmissing row: version:android-minSdk", failure.message)
    }

    @Test
    fun workflowActionWithoutARowOrAtAnotherVersionFails() {
        // Arrange
        val root =
            repository(
                workflow =
                    WORKFLOW +
                        "      - uses: actions/download-artifact@${SHA_C} # v8.0.2\n" +
                        "      - uses: actions/cache/restore@${SHA_B} # v6.0.0\n",
            )

        // Act
        val failure = assertFailsWith<GradleException> { task(root).check() }

        // Assert
        assertEquals(
            "$HEADER\n" +
                "ci.yml:9: actions/download-artifact v8.0.2 has no CI tools row\n" +
                "ci.yml:10: actions/cache v6.0.0, but the register says v6.1.0",
            failure.message,
        )
    }

    @Test
    fun actionPinnedToTwoCommitsOrWithoutAVersionCommentFails() {
        // Arrange
        val root =
            repository(
                workflow =
                    WORKFLOW +
                        "      - uses: actions/checkout@${SHA_C} # v7.0.1\n" +
                        "      - uses: actions/checkout@v7\n",
            )

        // Act
        val failure = assertFailsWith<GradleException> { task(root).check() }

        // Assert
        assertEquals(
            "$HEADER\n" +
                "ci.yml:10: actions/checkout@v7 is not pinned by commit SHA with a version comment\n" +
                "actions/checkout is pinned to 2 commits: $SHA_A, $SHA_C",
            failure.message,
        )
    }

    @Test
    fun registeredActionNoWorkflowUsesAndToolVersionMismatchFail() {
        // Arrange
        val root =
            repository(
                workflow =
                    WORKFLOW
                        .replace(
                            "actions/cache@$SHA_B # v6.1.0",
                            "actions/checkout@$SHA_A # v7.0.1",
                        ).replace("v0.24.2", "v0.25.0"),
            )

        // Act
        val failure = assertFailsWith<GradleException> { task(root).check() }

        // Assert
        assertEquals(
            "$HEADER\n" +
                "ci.yml:6: lycheeVersion v0.25.0, but the register says lychee 0.24.2\n" +
                "register row actions/cache v6.1.0 is used by no workflow",
            failure.message,
        )
    }

    private fun repository(
        catalog: String = CATALOG,
        register: String = REGISTER,
        workflow: String = WORKFLOW,
    ): File {
        val root = temporaryFolder.newFolder()
        root.resolve("gradle").mkdirs()
        root.resolve("gradle/libs.versions.toml").writeText(catalog)
        root.resolve("docs/reference").mkdirs()
        root.resolve("docs/reference/dependencies.md").writeText(register)
        root.resolve(".github/workflows").mkdirs()
        root.resolve(".github/workflows/ci.yml").writeText(workflow)
        return root
    }

    private fun task(root: File): CheckDependencyRegister {
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        return project.tasks
            .register("gate", CheckDependencyRegister::class.java) {
                DependencyRegister.configure(this, project)
            }.get()
    }

    private companion object {
        const val HEADER =
            "docs/reference/dependencies.md disagrees with gradle/libs.versions.toml or .github/workflows (ADR 0006 decision 11):"
        const val SHA_A = "3d3c42e5aac5ba805825da76410c181273ba90b1"
        const val SHA_B = "55cc8345863c7cc4c66a329aec7e433d2d1c52a9"
        const val SHA_C = "9000827ccba6bdab643e8b6fd33ac0654aef8333"

        val CATALOG =
            """
            [versions]
            # A comment.
            kotlin = "2.4.21"
            jazzer = "0.30.0"
            floor-netty = "4.1.141.Final"
            jdk-toolchain = "21"
            compose-bom = "2026.09.00"

            [libraries]
            jazzer = { module = "com.code-intelligence:jazzer", version.ref = "jazzer" }
            jazzer-api = { module = "com.code-intelligence:jazzer-api", version.ref = "jazzer" }
            floor-netty-codec = { module = "io.netty:netty-codec", version.ref = "floor-netty" }
            compose-bom = { module = "androidx.compose:compose-bom", version.ref = "compose-bom" }
            compose-ui = { module = "androidx.compose.ui:ui" }
            plain = { module = "a:b", version = "1.0" }

            [plugins]
            kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
            """.trimIndent()

        val REGISTER =
            """
            # Dependency register

            ## Build tooling

            | Dependency | Version | Catalog | Need |
            |---|---|---|---|
            | Kotlin Gradle plugin | 2.4.21 | `plugin:kotlin-multiplatform` | KMP |
            | JDK toolchain | 21 | `version:jdk-toolchain` | Compiles |
            | Plain | 1.0 | `plain` | Fixture |

            ## Test only

            | Dependency | Version | Catalog | Need |
            |---|---|---|---|
            | Jazzer | 0.30.0 | `jazzer`, `jazzer-api` | Fuzzing |
            | Netty | 4.1.141.Final | `floor-netty-codec` | Floor |
            | Compose BOM | 2026.09.00 (`ui` 1.12.1) | `compose-bom`, `compose-ui` | Lab |
            | libsodium host build | 1.0.22 | none | Native |

            ## CI tools (docs checks ADR 0006, build ADR 0005)

            | Tool | Version | Action | Need |
            |---|---|---|---|
            | lychee | 0.24.2 | `lycheeverse/lychee-action` v2.9.0 | Links |
            | actions/checkout | 7.0.1 | `actions/checkout` v7.0.1 | Checkout |
            | actions/cache | 6.1.0 | `actions/cache` v6.1.0, with restore and save | Cache |
            | actionlint | 1.7.12 | None: run locally (Homebrew) | Lint |
            | GitHub-hosted runner `ubuntu-24.04` | Image as of the run | `runs-on` | Linux |
            """.trimIndent() + "\n"

        val WORKFLOW =
            """
            jobs:
              lint:
                steps:
                  - uses: actions/checkout@$SHA_A # v7.0.1
                  - uses: lycheeverse/lychee-action@e7477775783ea5526144ba13e8db5eec57747ce8 # v2.9.0
                    with: { lycheeVersion: v0.24.2 }
                  - uses: actions/cache@$SHA_B # v6.1.0
                  - uses: ./.github/actions/local
            """.trimIndent() + "\n"
    }
}
