package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.internal.CryptoFailure
import ch.trancee.meshlink.internal.CryptoProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChaCha20Poly1305Test {
    private val crypto: CryptoProvider = SodiumCryptoProvider

    // RFC 8439 section 2.8.2.
    private val key = ByteArray(32) { (0x80 + it).toByte() }
    private val nonce = "070000004041424344454647".hex()
    private val associatedData = "50515253c0c1c2c3c4c5c6c7".hex()
    private val plaintext =
        (
            "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, " +
                "sunscreen would be it."
        ).encodeToByteArray()
    private val sealed =
        "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b" +
            "1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
            "3ff4def08e4b7a9de576d26586cec64b6116" +
            "1ae10b594f09e26a7e902ecbd0600691"

    @Test
    fun sealMatchesRfc8439() {
        assertEquals(sealed, crypto.chaCha20Poly1305Seal(key, nonce, plaintext, associatedData).hex())
    }

    @Test
    fun openRecoversTheRfc8439Plaintext() {
        val opened = crypto.chaCha20Poly1305Open(key, nonce, sealed.hex(), associatedData).ok()

        assertEquals(plaintext.decodeToString(), opened.decodeToString())
    }

    @Test
    fun emptyPlaintextAndAssociatedDataRoundTrip() {
        val tagOnly = crypto.chaCha20Poly1305Seal(key, nonce, ByteArray(0), ByteArray(0))

        assertEquals(16, tagOnly.size)
        assertEquals(0, crypto.chaCha20Poly1305Open(key, nonce, tagOnly, ByteArray(0)).ok().size)
    }

    @Test
    fun flippedTagCiphertextOrAssociatedDataFailsAuthentication() {
        val ciphertext = sealed.hex()

        listOf(
            crypto.chaCha20Poly1305Open(key, nonce, ciphertext.flipped(ciphertext.size - 1), associatedData),
            crypto.chaCha20Poly1305Open(key, nonce, ciphertext.flipped(0), associatedData),
            crypto.chaCha20Poly1305Open(key, nonce, ciphertext, associatedData.flipped(0)),
            crypto.chaCha20Poly1305Open(key, nonce.flipped(0), ciphertext, associatedData),
            crypto.chaCha20Poly1305Open(key.flipped(0), nonce, ciphertext, associatedData),
        ).forEach { it.assertFailed(CryptoFailure.AUTHENTICATION_FAILED) }
    }

    @Test
    fun ciphertextShorterThanTheTagFailsAuthentication() {
        SodiumCryptoProvider
            .chaCha20Poly1305Open(key, nonce, ByteArray(15), associatedData)
            .assertFailed(CryptoFailure.AUTHENTICATION_FAILED)
    }

    @Test
    fun wrongLengthKeysAndNoncesAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            crypto.chaCha20Poly1305Seal(ByteArray(31), nonce, plaintext, associatedData)
        }
        assertFailsWith<IllegalArgumentException> {
            crypto.chaCha20Poly1305Seal(key, ByteArray(8), plaintext, associatedData)
        }
        assertFailsWith<IllegalArgumentException> {
            crypto.chaCha20Poly1305Open(ByteArray(33), nonce, sealed.hex(), associatedData)
        }
        assertFailsWith<IllegalArgumentException> {
            crypto.chaCha20Poly1305Open(key, ByteArray(24), sealed.hex(), associatedData)
        }
    }
}
