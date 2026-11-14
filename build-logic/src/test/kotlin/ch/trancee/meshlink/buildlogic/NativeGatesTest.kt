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

class NativeGatesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun libraryExportingOnlyJniOnLoadPassesTheSymbolGate() {
        // Arrange
        val library = sharedLibrary("clean", "int JNI_OnLoad(void) { return 0; }")

        // Act
        val task = symbolGate(library)
        task.check()

        // Assert
        assertEquals(
            "\n",
            task.report
                .get()
                .asFile
                .readText(),
        )
    }

    @Test
    fun exportedLibsodiumSymbolFailsTheSymbolGate() {
        // Arrange
        val library = sharedLibrary("exports-crypto", "int crypto_generichash(void) { return 0; }")

        // Act
        val failure = assertFailsWith<GradleException> { symbolGate(library).check() }

        // Assert
        assertContains(failure.message.orEmpty(), "exports libsodium symbol ")
        assertContains(failure.message.orEmpty(), "crypto_generichash")
    }

    @Test
    fun importedAesSymbolFailsTheSymbolGate() {
        // Arrange
        val library = sharedLibrary("imports-aes", "int aes_encrypt(void);\nint JNI_OnLoad(void) { return aes_encrypt(); }")

        // Act
        val failure = assertFailsWith<GradleException> { symbolGate(library).check() }

        // Assert
        assertContains(failure.message.orEmpty(), "imports ")
        assertContains(failure.message.orEmpty(), "aes_encrypt")
    }

    @Test
    fun symbolRulesCoverEveryNamedFamily() {
        // Arrange
        val nm =
            """
            _sodium_init T 0 0
            randombytes_buf T 0 0
            crypto_aead_aegis256_encrypt U 0 0
            _ipcrypt_encrypt T 0 0
            memcpy@LIBC U 0 0
            sodium_memzero U 0 0
            _OBJC_CLASS_${'$'}_MLKMeshLink S 0 0
            """.trimIndent()

        // Act
        val violations = NativeGates.symbolViolations(nm)

        // Assert
        assertEquals(
            listOf(
                "exports libsodium symbol _sodium_init",
                "exports libsodium symbol randombytes_buf",
                "imports crypto_aead_aegis256_encrypt",
                "exports _ipcrypt_encrypt",
            ),
            violations,
        )
    }

    @Test
    fun libraryOverTheLimitFailsTheSizeGate() {
        // Arrange
        val library = temporaryFolder.newFile("big.so").apply { writeBytes(ByteArray(11)) }

        // Act
        val failure = assertFailsWith<GradleException> { sizeGate(library, limit = 10).check() }

        // Assert
        assertContains(failure.message.orEmpty(), "native size gate failed")
    }

    @Test
    fun libraryAtTheLimitPassesTheSizeGate() {
        // Arrange
        val library = temporaryFolder.newFile("small.so").apply { writeBytes(ByteArray(10)) }

        // Act
        val task = sizeGate(library, limit = 10)
        task.check()

        // Assert
        assertContains(
            task.report
                .get()
                .asFile
                .readText(),
            "small.so: 10 B (FILE, limit 10 B)",
        )
    }

    @Test
    fun darwinSegmentBytesSumsTheDecColumn() {
        // Arrange
        val size =
            """
            __TEXT	__DATA	__OBJC	others	dec	hex
            504	8	0	352	864	360	libsodium.a(a.o)
            5908	16	0	192	6116	17e4	libsodium.a(b.o)
            """.trimIndent()

        // Act
        val bytes = NativeGates.darwinSegmentBytes(size)

        // Assert
        assertEquals(6980L, bytes)
    }

    private fun sharedLibrary(
        name: String,
        source: String,
    ): File {
        val c = temporaryFolder.newFile("$name.c").apply { writeText("$source\n") }
        val library = File(temporaryFolder.root, "lib$name.so")
        val darwin = System.getProperty("os.name").startsWith("Mac")
        val command =
            listOf("cc", "-shared", "-fPIC") +
                (if (darwin) listOf("-undefined", "dynamic_lookup") else emptyList()) +
                listOf("-o", library.absolutePath, c.absolutePath)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        return library
    }

    private fun symbolGate(library: File): CheckNativeSymbols {
        val project = ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder()).build()
        return project.tasks
            .register("gate", CheckNativeSymbols::class.java) {
                binaries.from(library)
                nm.set(listOf("nm"))
                report.set(project.layout.buildDirectory.file("report.txt"))
            }.get()
    }

    private fun sizeGate(
        library: File,
        limit: Long,
    ): CheckNativeSize {
        val project = ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder()).build()
        return project.tasks
            .register("gate", CheckNativeSize::class.java) {
                binaries.from(library)
                measure.set(NativeSizeMeasure.FILE)
                this.limit.set(limit)
                report.set(project.layout.buildDirectory.file("report.txt"))
            }.get()
    }
}
