package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.internal.CryptoFailure
import ch.trancee.meshlink.internal.CryptoProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class X25519Test {
    private val crypto: CryptoProvider = SodiumCryptoProvider

    // RFC 7748 section 6.1.
    private val alicePrivate = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".hex()
    private val alicePublic = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"
    private val bobPrivate = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb".hex()
    private val bobPublic = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"
    private val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"

    @Test
    fun keyPairsMatchRfc7748() {
        val alice = crypto.x25519KeyPair(alicePrivate)

        assertEquals(alicePublic, alice.publicKey.hex())
        assertContentEquals(alicePrivate, alice.secretKey)
        assertEquals(bobPublic, crypto.x25519KeyPair(bobPrivate).publicKey.hex())
    }

    @Test
    fun bothSidesDeriveTheRfc7748SharedSecret() {
        assertEquals(shared, crypto.x25519(alicePrivate, bobPublic.hex()).ok().hex())
        assertEquals(shared, crypto.x25519(bobPrivate, alicePublic.hex()).ok().hex())
    }

    @Test
    fun freshKeyPairIsTheKeyPairOfItsSecretKey() {
        val first = crypto.x25519KeyPair()
        val second = crypto.x25519KeyPair()

        assertContentEquals(crypto.x25519KeyPair(first.secretKey).publicKey, first.publicKey)
        assertFalse(first.secretKey.contentEquals(second.secretKey))
    }

    @Test
    fun lowOrderPublicKeysAreRefused() {
        // The low-order points libsodium's has_small_order lists (x25519_ref10.c), all-zero first.
        val lowOrder =
            listOf(
                "0000000000000000000000000000000000000000000000000000000000000000",
                "0100000000000000000000000000000000000000000000000000000000000000",
                "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
                "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157",
                "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
                "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
                "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            )

        lowOrder.forEach { crypto.x25519(alicePrivate, it.hex()).assertFailed(CryptoFailure.INVALID_PUBLIC_KEY) }
    }

    @Test
    fun wrongLengthKeysAreRejected() {
        assertFailsWith<IllegalArgumentException> { crypto.x25519KeyPair(ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { crypto.x25519(ByteArray(33), bobPublic.hex()) }
        assertFailsWith<IllegalArgumentException> { crypto.x25519(alicePrivate, ByteArray(31)) }
    }
}
