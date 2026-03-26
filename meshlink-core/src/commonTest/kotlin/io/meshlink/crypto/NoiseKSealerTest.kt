package io.meshlink.crypto

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoiseKSealerTest {

    private val crypto = createCryptoProvider()
    private val sealer = NoiseKSealer(crypto)

    // ── Vertical slice 1: seal/unseal round-trip ──

    @Test
    fun sealUnsealRoundTrip() {
        val recipient = crypto.generateX25519KeyPair()
        val plaintext = "secret message for mesh peer".encodeToByteArray()

        val sealed = sealer.seal(recipient.publicKey, plaintext)
        val decrypted = sealer.unseal(recipient.privateKey, sealed)

        assertTrue(plaintext.contentEquals(decrypted), "Decrypted should match original")
    }

    // ── Vertical slice 2: wrong key fails ──

    @Test
    fun unsealWithWrongKeyFails() {
        val recipient = crypto.generateX25519KeyPair()
        val wrongKey = crypto.generateX25519KeyPair()
        val plaintext = "secret".encodeToByteArray()

        val sealed = sealer.seal(recipient.publicKey, plaintext)

        assertFailsWith<Exception> {
            sealer.unseal(wrongKey.privateKey, sealed)
        }
    }

    // ── Vertical slice 3: tampered sealed data fails ──

    @Test
    fun unsealTamperedDataFails() {
        val recipient = crypto.generateX25519KeyPair()
        val plaintext = "secret".encodeToByteArray()

        val sealed = sealer.seal(recipient.publicKey, plaintext)
        val tampered = sealed.copyOf()
        tampered[40] = (tampered[40].toInt() xor 0xFF).toByte()

        assertFailsWith<Exception> {
            sealer.unseal(recipient.privateKey, tampered)
        }
    }

    // ── Vertical slice 4: each seal produces different ciphertext (forward secrecy) ──

    @Test
    fun eachSealProducesDifferentCiphertext() {
        val recipient = crypto.generateX25519KeyPair()
        val plaintext = "same message".encodeToByteArray()

        val sealed1 = sealer.seal(recipient.publicKey, plaintext)
        val sealed2 = sealer.seal(recipient.publicKey, plaintext)

        // Different ephemeral keys → different ciphertext
        assertFalse(sealed1.contentEquals(sealed2),
            "Same plaintext sealed twice should produce different ciphertext")

        // But both should unseal to the same plaintext
        val d1 = sealer.unseal(recipient.privateKey, sealed1)
        val d2 = sealer.unseal(recipient.privateKey, sealed2)
        assertTrue(d1.contentEquals(d2))
    }

    // ── Vertical slice 5: sealed data too short rejected ──

    @Test
    fun unsealRejectsTooShortData() {
        val recipient = crypto.generateX25519KeyPair()

        assertFailsWith<IllegalArgumentException> {
            sealer.unseal(recipient.privateKey, ByteArray(47))
        }
    }
}
