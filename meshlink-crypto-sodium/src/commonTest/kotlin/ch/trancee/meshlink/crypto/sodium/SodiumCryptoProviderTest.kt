package ch.trancee.meshlink.crypto.sodium

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SodiumCryptoProviderTest {
    @Test
    fun ed25519PublicKeyOfSeedMatchesRfc8032Test1() {
        // RFC 8032 section 7.1, TEST 1.
        val seed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".hex()

        val keyPair = SodiumCryptoProvider.ed25519KeyPair(seed)

        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", keyPair.publicKey.hex())
        assertContentEquals(seed, keyPair.seed)
    }

    @Test
    fun freshKeyPairIsTheKeyPairOfItsSeed() {
        val keyPair = SodiumCryptoProvider.ed25519KeyPair()

        assertContentEquals(SodiumCryptoProvider.ed25519KeyPair(keyPair.seed).publicKey, keyPair.publicKey)
    }

    @Test
    fun freshKeyPairsDiffer() {
        val first = SodiumCryptoProvider.ed25519KeyPair()
        val second = SodiumCryptoProvider.ed25519KeyPair()

        assertFalse(first.seed.contentEquals(second.seed))
    }

    @Test
    fun blake2bMatchesRfc7693AppendixA() {
        // RFC 7693 Appendix A: BLAKE2b-512("abc").
        val expected =
            "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d1" +
                "7d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923"

        assertEquals(expected, SodiumCryptoProvider.blake2b(64, "abc".encodeToByteArray()).hex())
    }

    @Test
    fun blake2bHashesEmptyInput() {
        // BLAKE2b-256 of the empty string.
        assertEquals(
            "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
            SodiumCryptoProvider.blake2b(32, ByteArray(0)).hex(),
        )
    }

    @Test
    fun blake2bRejectsOutOfRangeArguments() {
        assertFailsWith<IllegalArgumentException> { SodiumCryptoProvider.blake2b(15, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { SodiumCryptoProvider.blake2b(65, ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { SodiumCryptoProvider.blake2b(32, ByteArray(0), ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> { SodiumCryptoProvider.blake2b(32, ByteArray(0), ByteArray(65)) }
    }

    @Test
    fun ed25519KeyPairRejectsSeedOfWrongLength() {
        assertFailsWith<IllegalArgumentException> { SodiumCryptoProvider.ed25519KeyPair(ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { SodiumCryptoProvider.ed25519KeyPair(ByteArray(33)) }
    }

    @Test
    fun randomBytesHasTheRequestedLength() {
        assertEquals(0, SodiumCryptoProvider.randomBytes(0).size)
        assertEquals(64, SodiumCryptoProvider.randomBytes(64).size)
        assertFailsWith<IllegalArgumentException> { SodiumCryptoProvider.randomBytes(-1) }
    }
}
