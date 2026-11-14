package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.internal.Derivations
import ch.trancee.meshlink.internal.SecurityProfile
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DerivationsTest {
    private val derivations = Derivations(SodiumCryptoProvider)

    @Test
    fun meshIdDigestRejectsMeshIdentifiersOutside1To64Bytes() {
        assertFailsWith<IllegalArgumentException> { derivations.meshIdDigest(ByteArray(0), SecurityProfile.CLASSICAL) }
        assertFailsWith<IllegalArgumentException> { derivations.meshIdDigest(ByteArray(65), SecurityProfile.CLASSICAL) }
    }

    @Test
    fun epochRejectsTimesOutsideTheU32Range() {
        assertFailsWith<IllegalArgumentException> { Derivations.epoch(-1) }
        assertFailsWith<IllegalArgumentException> { Derivations.epoch(1L shl 32) }
    }
}
