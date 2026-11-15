package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.internal.CryptoProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Sha256HkdfTest {
    private val crypto: CryptoProvider = SodiumCryptoProvider

    private val ikm = ByteArray(22) { 0x0b }

    @Test
    fun sha256MatchesFips180Example() {
        // FIPS 180-2 Appendix B.1: SHA-256("abc").
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            crypto.sha256("abc".encodeToByteArray()).hex(),
        )
    }

    @Test
    fun hkdfMatchesRfc5869A1() {
        val prk = crypto.hkdfSha256Extract("000102030405060708090a0b0c".hex(), ikm)

        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", prk.hex())
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            crypto.hkdfSha256Expand(prk, "f0f1f2f3f4f5f6f7f8f9".hex(), 42).hex(),
        )
    }

    @Test
    fun hkdfWithEmptySaltAndInfoMatchesRfc5869A3() {
        val prk = crypto.hkdfSha256Extract(ByteArray(0), ikm)

        assertEquals("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04", prk.hex())
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            crypto.hkdfSha256Expand(prk, ByteArray(0), 42).hex(),
        )
    }

    @Test
    fun hkdfExpandSpansItsWholeLengthRange() {
        val prk = crypto.hkdfSha256Extract(ByteArray(0), ikm)
        val longest = crypto.hkdfSha256Expand(prk, ByteArray(0), CryptoProvider.HKDF_SHA256_MAX_LENGTH)

        assertEquals(CryptoProvider.HKDF_SHA256_MAX_LENGTH, longest.size)
        assertEquals(longest.copyOf(1).hex(), crypto.hkdfSha256Expand(prk, ByteArray(0), 1).hex())
    }

    @Test
    fun wrongLengthsAreRejected() {
        val prk = ByteArray(32)

        assertFailsWith<IllegalArgumentException> { crypto.hkdfSha256Expand(ByteArray(31), ByteArray(0), 32) }
        assertFailsWith<IllegalArgumentException> { crypto.hkdfSha256Expand(prk, ByteArray(0), 0) }
        assertFailsWith<IllegalArgumentException> {
            crypto.hkdfSha256Expand(prk, ByteArray(0), CryptoProvider.HKDF_SHA256_MAX_LENGTH + 1)
        }
    }
}
