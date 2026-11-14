package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.internal.Derivations
import ch.trancee.meshlink.internal.WireProfile
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DerivationsTest {
    private val derivations = Derivations(SodiumCryptoProvider)

    @Test
    fun meshIdDigestRejectsMeshIdentifiersOutside1To64Bytes() {
        assertFailsWith<IllegalArgumentException> { derivations.meshIdDigest(ByteArray(0), WireProfile.CLASSICAL) }
        assertFailsWith<IllegalArgumentException> { derivations.meshIdDigest(ByteArray(65), WireProfile.CLASSICAL) }
    }

    @Test
    fun epochRejectsTimesOutsideTheU32Range() {
        assertFailsWith<IllegalArgumentException> { Derivations.epoch(-1) }
        assertFailsWith<IllegalArgumentException> { Derivations.epoch(1L shl 32) }
    }
}
