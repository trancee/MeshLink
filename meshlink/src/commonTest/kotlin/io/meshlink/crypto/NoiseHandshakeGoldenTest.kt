package io.meshlink.crypto

import io.meshlink.util.hexToBytes
import io.meshlink.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Golden test vectors for the Noise XX handshake and X25519 key derivation.
 *
 * Uses RFC 7748 Section 6.1 test vectors for deterministic key material.
 * The Noise XX handshake generates ephemeral keys internally, so full
 * transcript determinism is not possible. Instead we verify:
 *   - Key derivation from known private keys produces expected public keys
 *   - Handshake completes successfully with known static keys
 *   - Transport keys are symmetric (alice.send == bob.receive)
 *   - Data encrypted by one side can be decrypted by the other
 *   - Both sides learn the correct remote static public key
 */
class NoiseHandshakeGoldenTest {

    private val crypto = CryptoProvider()

    // ── RFC 7748 Section 6.1 test vectors ──
    // These are the canonical X25519 key pairs used across all tests.

    private val alicePrivate = hexToBytes(
        "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"
    )
    private val alicePublicExpected = hexToBytes(
        "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"
    )
    private val bobPrivate = hexToBytes(
        "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"
    )
    private val bobPublicExpected = hexToBytes(
        "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"
    )
    private val sharedSecretExpected = hexToBytes(
        "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
    )

    private val basepoint = ByteArray(32).also { it[0] = 9 }

    private fun keyPairFrom(privateKey: ByteArray): CryptoKeyPair {
        val publicKey = X25519.scalarMult(privateKey, basepoint)
        return CryptoKeyPair(publicKey = publicKey, privateKey = privateKey)
    }

    // ── X25519 Key Derivation Golden Vectors ──

    @Test
    fun x25519DeriveAlicePublicKeyFromKnownPrivateKey() {
        val derived = X25519.scalarMult(alicePrivate, basepoint)
        // Golden vector: alice_pub = 8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a
        assertEquals(
            "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a",
            derived.toHex(),
            "Alice public key must match RFC 7748 Section 6.1",
        )
    }

    @Test
    fun x25519DeriveBobPublicKeyFromKnownPrivateKey() {
        val derived = X25519.scalarMult(bobPrivate, basepoint)
        // Golden vector: bob_pub = de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f
        assertEquals(
            "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f",
            derived.toHex(),
            "Bob public key must match RFC 7748 Section 6.1",
        )
    }

    @Test
    fun x25519SharedSecretMatchesRfc7748GoldenVector() {
        // Golden vector: shared = 4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742
        val shared = X25519.sharedSecret(alicePrivate, bobPublicExpected)
        assertEquals(
            "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742",
            shared.toHex(),
            "Shared secret must match RFC 7748 Section 6.1",
        )
    }

    @Test
    fun x25519SharedSecretIsSymmetric() {
        val ab = X25519.sharedSecret(alicePrivate, bobPublicExpected)
        val ba = X25519.sharedSecret(bobPrivate, alicePublicExpected)
        assertEquals(ab.toHex(), ba.toHex(), "DH must be symmetric: alice⊗bob == bob⊗alice")
    }

    // ── Noise XX Handshake with Known Static Keys ──

    private fun doHandshake(): Pair<NoiseXXHandshake.HandshakeResult, NoiseXXHandshake.HandshakeResult> {
        val alice = NoiseXXHandshake.initiator(crypto, keyPairFrom(alicePrivate))
        val bob = NoiseXXHandshake.responder(crypto, keyPairFrom(bobPrivate))

        // msg1: → e
        val msg1 = alice.writeMessage()
        bob.readMessage(msg1)

        // msg2: ← e, ee, s, es
        val msg2 = bob.writeMessage()
        alice.readMessage(msg2)

        // msg3: → s, se
        val msg3 = alice.writeMessage()
        bob.readMessage(msg3)

        assertTrue(alice.isComplete, "Alice handshake must be complete")
        assertTrue(bob.isComplete, "Bob handshake must be complete")

        return alice.finalize() to bob.finalize()
    }

    @Test
    fun handshakeCompletesWithKnownStaticKeys() {
        doHandshake() // asserts isComplete inside
    }

    @Test
    fun transportKeysAreSymmetricWithKnownStaticKeys() {
        val (aliceResult, bobResult) = doHandshake()

        assertTrue(
            aliceResult.sendKey.contentEquals(bobResult.receiveKey),
            "Alice sendKey must equal Bob receiveKey",
        )
        assertTrue(
            aliceResult.receiveKey.contentEquals(bobResult.sendKey),
            "Alice receiveKey must equal Bob sendKey",
        )
    }

    @Test
    fun transportKeysAre32Bytes() {
        val (aliceResult, _) = doHandshake()

        assertEquals(32, aliceResult.sendKey.size, "Send key must be 32 bytes")
        assertEquals(32, aliceResult.receiveKey.size, "Receive key must be 32 bytes")
        assertEquals(32, aliceResult.remoteStaticKey.size, "Remote static key must be 32 bytes")
    }

    @Test
    fun remoteStaticKeysMatchKnownPublicKeys() {
        val (aliceResult, bobResult) = doHandshake()

        assertEquals(
            bobPublicExpected.toHex(),
            aliceResult.remoteStaticKey.toHex(),
            "Alice must learn Bob's RFC 7748 static public key",
        )
        assertEquals(
            alicePublicExpected.toHex(),
            bobResult.remoteStaticKey.toHex(),
            "Bob must learn Alice's RFC 7748 static public key",
        )
    }

    // ── Data Encryption / Decryption after Handshake ──

    @Test
    fun aliceTooBobEncryptionDecryption() {
        val (aliceResult, bobResult) = doHandshake()

        val plaintext = "golden test payload from alice".encodeToByteArray()
        val nonce = ByteArray(12) // zero nonce
        val ciphertext = crypto.aeadEncrypt(aliceResult.sendKey, nonce, plaintext)
        val decrypted = crypto.aeadDecrypt(bobResult.receiveKey, nonce, ciphertext)

        assertTrue(
            plaintext.contentEquals(decrypted),
            "Bob must decrypt Alice's ciphertext to the original plaintext",
        )
    }

    @Test
    fun bobToAliceEncryptionDecryption() {
        val (aliceResult, bobResult) = doHandshake()

        val plaintext = "golden test payload from bob".encodeToByteArray()
        val nonce = ByteArray(12).also { it[0] = 1 }
        val ciphertext = crypto.aeadEncrypt(bobResult.sendKey, nonce, plaintext)
        val decrypted = crypto.aeadDecrypt(aliceResult.receiveKey, nonce, ciphertext)

        assertTrue(
            plaintext.contentEquals(decrypted),
            "Alice must decrypt Bob's ciphertext to the original plaintext",
        )
    }

    // ── Handshake Payload Delivery ──

    @Test
    fun handshakePayloadsDeliverWithKnownStaticKeys() {
        val alice = NoiseXXHandshake.initiator(crypto, keyPairFrom(alicePrivate))
        val bob = NoiseXXHandshake.responder(crypto, keyPairFrom(bobPrivate))

        val p1 = "init-hello".encodeToByteArray()
        val recv1 = bob.readMessage(alice.writeMessage(p1))
        assertTrue(p1.contentEquals(recv1), "msg1 payload must be delivered")

        val p2 = "resp-hello".encodeToByteArray()
        val recv2 = alice.readMessage(bob.writeMessage(p2))
        assertTrue(p2.contentEquals(recv2), "msg2 payload must be delivered")

        val p3 = "init-caps".encodeToByteArray()
        val recv3 = bob.readMessage(alice.writeMessage(p3))
        assertTrue(p3.contentEquals(recv3), "msg3 payload must be delivered")

        assertTrue(alice.isComplete)
        assertTrue(bob.isComplete)
    }

    // ── Ephemeral Randomness ──

    @Test
    fun ephemeralRandomnessProducesDifferentTransportKeys() {
        val (result1, _) = doHandshake()
        val (result2, _) = doHandshake()

        assertFalse(
            result1.sendKey.contentEquals(result2.sendKey),
            "Different handshakes must produce different transport keys " +
                "because ephemeral keys are random",
        )
    }
}
