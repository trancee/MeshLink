package io.meshlink.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecurityEngineTest {

    private fun createEngine(): SecurityEngine {
        val crypto = PureKotlinCryptoProvider()
        return SecurityEngine(crypto)
    }

    // --- Vertical slice 1: E2E seal + unseal round-trip ---

    @Test
    fun sealAndUnsealRoundTrip() {
        val sender = createEngine()
        val receiver = createEngine()

        sender.registerPeerKey("receiver01", receiver.localPublicKey)

        val plaintext = "hello mesh".encodeToByteArray()
        val sealResult = sender.seal("receiver01", plaintext)
        assertIs<SealResult.Sealed>(sealResult)

        val unsealResult = receiver.unseal(sealResult.ciphertext)
        assertIs<UnsealResult.Decrypted>(unsealResult)
        assertEquals(plaintext.decodeToString(), unsealResult.plaintext.decodeToString())
    }

    // --- Vertical slice 2: seal for unknown recipient ---

    @Test
    fun sealForUnknownRecipientReturnsUnknownRecipient() {
        val engine = createEngine()
        val result = engine.seal("nobody", "data".encodeToByteArray())
        assertIs<SealResult.UnknownRecipient>(result)
    }

    // --- Vertical slice 3: unseal tampered ciphertext ---

    @Test
    fun unsealTamperedCiphertextReturnsFailed() {
        val sender = createEngine()
        val receiver = createEngine()
        sender.registerPeerKey("receiver01", receiver.localPublicKey)

        val sealed = (sender.seal("receiver01", "secret".encodeToByteArray()) as SealResult.Sealed).ciphertext
        // Tamper with the ciphertext
        sealed[sealed.size - 1] = (sealed[sealed.size - 1] + 1).toByte()

        val result = receiver.unseal(sealed)
        assertIs<UnsealResult.Failed>(result)
    }

    // --- Vertical slice 4: unseal payload too short ---

    @Test
    fun unsealPayloadTooShortReturnsTooShort() {
        val engine = createEngine()
        val short = ByteArray(47) // < 48 minimum
        val result = engine.unseal(short)
        assertIs<UnsealResult.TooShort>(result)
        assertTrue(result.originalPayload.contentEquals(short))
    }

    // --- Vertical slice 5: sign and verify round-trip ---

    @Test
    fun signAndVerifyRoundTrip() {
        val engine = createEngine()
        val data = "important data".encodeToByteArray()
        val signed = engine.sign(data)

        assertTrue(engine.verify(signed.signerPublicKey, data, signed.signature))
    }

    @Test
    fun verifyRejectsTamperedSignature() {
        val engine = createEngine()
        val data = "important data".encodeToByteArray()
        val signed = engine.sign(data)

        val tampered = signed.signature.copyOf()
        tampered[0] = (tampered[0] + 1).toByte()

        assertFalse(engine.verify(signed.signerPublicKey, data, tampered))
    }

    @Test
    fun verifyRejectsTamperedData() {
        val engine = createEngine()
        val data = "important data".encodeToByteArray()
        val signed = engine.sign(data)

        assertFalse(engine.verify(signed.signerPublicKey, "wrong data".encodeToByteArray(), signed.signature))
    }

    // --- Vertical slice 6: identity rotation ---

    @Test
    fun registerNewKeyReturnsNew() {
        val engine = createEngine()
        val key = ByteArray(32) { it.toByte() }
        val result = engine.registerPeerKey("peer1", key)
        assertIs<KeyRegistrationResult.New>(result)
    }

    @Test
    fun registerSameKeyReturnsUnchanged() {
        val engine = createEngine()
        val key = ByteArray(32) { it.toByte() }
        engine.registerPeerKey("peer1", key)
        val result = engine.registerPeerKey("peer1", key)
        assertIs<KeyRegistrationResult.Unchanged>(result)
    }

    @Test
    fun registerDifferentKeyReturnsChanged() {
        val engine = createEngine()
        val key1 = ByteArray(32) { it.toByte() }
        val key2 = ByteArray(32) { (it + 1).toByte() }
        engine.registerPeerKey("peer1", key1)
        val result = engine.registerPeerKey("peer1", key2)
        assertIs<KeyRegistrationResult.Changed>(result)
        assertTrue(result.previousKey.contentEquals(key1))
    }

    @Test
    fun registeredKeyIsRetrievable() {
        val engine = createEngine()
        val key = ByteArray(32) { it.toByte() }
        engine.registerPeerKey("peer1", key)
        assertTrue(engine.peerPublicKey("peer1")!!.contentEquals(key))
    }

    @Test
    fun unknownPeerKeyReturnsNull() {
        val engine = createEngine()
        assertNull(engine.peerPublicKey("unknown"))
    }

    // --- Vertical slice 8: identity rotation ---

    @Test
    fun rotateIdentityChangesKeys() {
        val engine = createEngine()
        val oldPub = engine.localPublicKey.copyOf()
        val oldBroadcast = engine.localBroadcastPublicKey.copyOf()

        engine.rotateIdentity()

        assertFalse(engine.localPublicKey.contentEquals(oldPub))
        assertFalse(engine.localBroadcastPublicKey.contentEquals(oldBroadcast))
    }

    @Test
    fun rotateIdentityBreaksOldSealedMessages() {
        val sender = createEngine()
        val receiver = createEngine()
        sender.registerPeerKey("receiver01", receiver.localPublicKey)

        val sealed = (sender.seal("receiver01", "before rotation".encodeToByteArray()) as SealResult.Sealed).ciphertext

        receiver.rotateIdentity() // new key pair

        val result = receiver.unseal(sealed)
        assertIs<UnsealResult.Failed>(result) // can't decrypt with new keys
    }

    // --- Vertical slice 9: clear resets peer keys ---

    @Test
    fun clearResetsPeerKeys() {
        val engine = createEngine()
        val other = createEngine()
        engine.registerPeerKey("peer1", other.localPublicKey)

        // Before clear, seal should work
        assertIs<SealResult.Sealed>(engine.seal("peer1", "test".encodeToByteArray()))

        engine.clear()

        // After clear, peer key is gone
        assertNull(engine.peerPublicKey("peer1"))
        assertIs<SealResult.UnknownRecipient>(engine.seal("peer1", "test".encodeToByteArray()))
    }
}
