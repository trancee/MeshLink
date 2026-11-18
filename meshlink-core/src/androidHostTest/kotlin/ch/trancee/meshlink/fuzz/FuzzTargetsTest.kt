package ch.trancee.meshlink.fuzz

import ch.trancee.meshlink.internal.Decoded
import java.io.File
import java.lang.reflect.Modifier
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FuzzTargetsTest {
    @Test
    fun everyDecoderHasATarget() {
        val decoders = decoders()
        val covered = fuzzTargets.flatMap { it.covers }.toSet()

        assertTrue(decoders.isNotEmpty(), "no decoder found in the main classes")
        assertEquals(emptySet(), decoders - covered, "decoders without a fuzz target")
        assertEquals(emptySet(), covered - decoders, "targets naming no decoder")
    }

    @Test
    fun everyTargetRunsItsCommittedCorpus() {
        fuzzTargets.forEach { fuzz ->
            val inputs = File(CORPUS, fuzz.name).listFiles().orEmpty()
            val entry = fuzz.target.java.getMethod("fuzzerTestOneInput", ByteArray::class.java)

            assertTrue(inputs.isNotEmpty(), "${fuzz.name} has no committed corpus")
            inputs.forEach { entry.invoke(null, it.readBytes()) }
        }
    }

    /** Every JVM-visible method of the main classes jar that takes bytes and returns [Decoded]. */
    private fun decoders(): Set<String> {
        val jar = File(checkNotNull(Decoded::class.java.protectionDomain).codeSource.location.toURI())
        return JarFile(jar).use { classes ->
            classes
                .entries()
                .asSequence()
                .map { it.name }
                .filter { it.endsWith(".class") }
                .map { it.removeSuffix(".class").replace('/', '.') }
                .map { Class.forName(it, false, Decoded::class.java.classLoader) }
                .filterNot { it.isSynthetic || it.isAnonymousClass }
                .flatMap { type -> type.declaredMethods.asSequence() }
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isBridge }
                .filter { it.returnType == Decoded::class.java && ByteArray::class.java in it.parameterTypes }
                .map { "${it.declaringClass.name.removePrefix(PACKAGE)}.${it.name.substringBefore('$')}" }
                .toSet()
        }
    }

    private companion object {
        const val PACKAGE = "ch.trancee.meshlink.internal."
        val CORPUS = File("src/androidHostTest/fuzz")
    }
}
