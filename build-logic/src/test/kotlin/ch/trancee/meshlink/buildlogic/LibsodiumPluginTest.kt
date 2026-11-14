package ch.trancee.meshlink.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LibsodiumPluginTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun signedTarballWithPinnedDigestIsReleasedForExtraction() {
        // Arrange
        val root = fixture()

        // Act
        val result = runner(root).build()

        // Assert
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyLibsodium")?.outcome)
        assertContentEquals(resource(TARBALL).readBytes(), verified(root).readBytes())
    }

    @Test
    fun tarballWithOneFlippedByteFailsTheSha256Check() {
        // Arrange
        val root = fixture()
        val served = root.resolve("served/$TARBALL")
        val bytes = served.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        served.writeBytes(bytes)

        // Act
        val result = runner(root).buildAndFail()

        // Assert
        assertContains(result.output, "libsodium SHA-256 check failed for $TARBALL")
        assertFalse(verified(root).exists())
    }

    @Test
    fun signatureFromAnotherKeyFailsTheMinisignCheck() {
        // Arrange
        val root = fixture()
        resource("other-key.minisig").copyTo(root.resolve("served/$TARBALL.minisig"), overwrite = true)

        // Act
        val result = runner(root).buildAndFail()

        // Assert
        assertContains(result.output, "libsodium minisign check failed for $TARBALL")
        assertFalse(verified(root).exists())
    }

    @Test
    fun missingMinisignBinaryFailsClosed() {
        // Arrange
        val root = fixture()

        // Act
        val result = runner(root, "-Pmeshlink.minisign=${root.resolve("absent/minisign")}").buildAndFail()

        // Assert
        assertContains(result.output, "libsodium minisign check needs the minisign binary")
        assertFalse(verified(root).exists())
    }

    private fun fixture(): File {
        val root = temporaryFolder.root
        val served = root.resolve("served").apply { mkdirs() }
        resource(TARBALL).copyTo(served.resolve(TARBALL))
        resource("$TARBALL.minisig").copyTo(served.resolve("$TARBALL.minisig"))
        root.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"\n")
        root.resolve("build.gradle.kts").writeText("plugins { id(\"meshlink.libsodium\") }\n")
        root.resolve("libsodium.properties").writeText(
            """
            version=0.0.1
            baseUrl=${served.toURI()}
            sha256=c285dc9e5c5654d6c6c48feee55c410b488d98e4f1a868bb8aa00edc018b8cf2
            minisignPublicKey=${resource("public-key.txt").readText().trim()}
            """.trimIndent(),
        )
        root.resolve("gradle").mkdirs()
        root.resolve("gradle/libs.versions.toml").writeText(
            """
            [versions]
            android-ndk = "30.0.16248370"
            android-minSdk = "26"
            """.trimIndent(),
        )
        return root
    }

    private fun runner(
        root: File,
        vararg extra: String,
    ): GradleRunner =
        GradleRunner
            .create()
            .withProjectDir(root)
            .withPluginClasspath()
            .withArguments(listOf("verifyLibsodium", "--stacktrace") + extra)

    private fun verified(root: File): File = root.resolve("build/libsodium/verified/$TARBALL")

    private fun resource(name: String): File = File(requireNotNull(javaClass.getResource("/libsodium/$name")).toURI())

    private companion object {
        const val TARBALL = "libsodium-0.0.1.tar.gz"
    }
}
