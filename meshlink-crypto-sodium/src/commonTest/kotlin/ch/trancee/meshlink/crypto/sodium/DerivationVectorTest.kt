package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.internal.Derivations
import ch.trancee.meshlink.internal.SecurityProfile
import ch.trancee.meshlink.vectors.DerivationVector
import ch.trancee.meshlink.vectors.derivationVectors
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs docs/spec/vectors/derivations.json against [Derivations] on libsodium. */
class DerivationVectorTest {
    private val derivations = Derivations(SodiumCryptoProvider)

    private fun vectors(derivation: String) = derivationVectors.filter { it.derivation == derivation }

    private fun assertVectors(
        derivation: String,
        tcIds: IntRange,
        compute: (Map<String, String>) -> ByteArray,
    ) {
        val vectors = vectors(derivation)
        // A vector file edit that drops or renumbers tests must fail here, not pass vacuously.
        assertEquals(tcIds.toList(), vectors.map { it.tcId })
        vectors.forEach { assertEquals(it.output, compute(it.inputs).hex(), "tcId ${it.tcId}") }
    }

    @Test
    fun epoch() =
        assertVectors("epoch", 1..5) { inputs ->
            val epoch = Derivations.epoch(inputs.getValue("unix_seconds").toLong())
            ByteArray(4) { (epoch shr (24 - 8 * it)).toByte() }
        }

    @Test
    fun nodeId() = assertVectors("node-id", 6..8) { derivations.nodeId(it.getValue("ed25519_key").hex()) }

    @Test
    fun meshIdDigest() =
        assertVectors("mesh-id-digest", 9..13) { inputs ->
            val profile = SecurityProfile.entries.single { it.code == inputs.getValue("profile").toInt() }
            derivations.meshIdDigest(inputs.getValue("mesh_id_utf8").hex(), profile)
        }

    @Test
    fun meshTag() =
        assertVectors("mesh-tag", 16..20) { inputs ->
            derivations.meshTag(inputs.getValue("tag_key").hex(), inputs.getValue("epoch").toUInt())
        }
}
