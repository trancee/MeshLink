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

    // --- TM-007: Rotation timestamp freshness ---

    @Test
    fun rotationAnnouncementWithFreshTimestampAccepted() {
        var now = 100_000L
        val crypto = PureKotlinCryptoProvider()
        val engine = SecurityEngine(crypto, clock = { now }, rotationFreshnessWindowMillis = 30_000L)

        // Generate keys for "peer" and register old key
        val oldKp = crypto.generateX25519KeyPair()
        val newKp = crypto.generateX25519KeyPair()
        val oldEd = crypto.generateEd25519KeyPair()
        val newEd = crypto.generateEd25519KeyPair()
        engine.registerPeerKey("0a0b0c0d0e0f0102030405060708090a", oldKp.publicKey)

        // Build a valid rotation announcement at current time
        val payload = io.meshlink.wire.RotationAnnouncement.buildSignablePayload(
            oldKp.publicKey, newKp.publicKey, oldEd.publicKey, newEd.publicKey, now.toULong(),
        )
        val sig = crypto.sign(oldEd.privateKey, payload)

        val msg = io.meshlink.wire.RotationAnnouncement.RotationMessage(
            oldKp.publicKey, newKp.publicKey, oldEd.publicKey, newEd.publicKey, now.toULong(), sig,
        )

        val result = engine.handleRotationAnnouncement("0a0b0c0d0e0f0102030405060708090a", msg)
        assertIs<RotationResult.Accepted>(result)
    }

    @Test
    fun rotationAnnouncementWithExpiredTimestampRejectedAsStale() {
        var now = 100_000L
        val crypto = PureKotlinCryptoProvider()
        val engine = SecurityEngine(crypto, clock = { now }, rotationFreshnessWindowMillis = 30_000L)

        val oldKp = crypto.generateX25519KeyPair()
        val newKp = crypto.generateX25519KeyPair()
        val oldEd = crypto.generateEd25519KeyPair()
        val newEd = crypto.generateEd25519KeyPair()
        engine.registerPeerKey("0a0b0c0d0e0f0102030405060708090a", oldKp.publicKey)

        // Announcement timestamp is 60s in the past (outside 30s window)
        val staleTs = (now - 60_000L).toULong()
        val payload = io.meshlink.wire.RotationAnnouncement.buildSignablePayload(
            oldKp.publicKey, newKp.publicKey, oldEd.publicKey, newEd.publicKey, staleTs,
        )
        val sig = crypto.sign(oldEd.privateKey, payload)

        val msg = io.meshlink.wire.RotationAnnouncement.RotationMessage(
            oldKp.publicKey, newKp.publicKey, oldEd.publicKey, newEd.publicKey, staleTs, sig,
        )

        val result = engine.handleRotationAnnouncement("0a0b0c0d0e0f0102030405060708090a", msg)
        assertIs<RotationResult.Stale>(result)
    }

    @Test
    fun rotationReplayWithSameTimestampRejectedAsStale() {
        var now = 100_000L
        val crypto = PureKotlinCryptoProvider()
        val engine = SecurityEngine(crypto, clock = { now }, rotationFreshnessWindowMillis = 30_000L)

        val oldKp = crypto.generateX25519KeyPair()
        val newKp = crypto.generateX25519KeyPair()
        val oldEd = crypto.generateEd25519KeyPair()
        val newEd = crypto.generateEd25519KeyPair()
        engine.registerPeerKey("0a0b0c0d0e0f0102030405060708090a", oldKp.publicKey)

        val payload = io.meshlink.wire.RotationAnnouncement.buildSignablePayload(
            oldKp.publicKey, newKp.publicKey, oldEd.publicKey, newEd.publicKey, now.toULong(),
        )
        val sig = crypto.sign(oldEd.privateKey, payload)
        val msg = io.meshlink.wire.RotationAnnouncement.RotationMessage(
            oldKp.publicKey, newKp.publicKey, oldEd.publicKey, newEd.publicKey, now.toULong(), sig,
        )

        // First rotation accepted
        assertIs<RotationResult.Accepted>(engine.handleRotationAnnouncement("0a0b0c0d0e0f0102030405060708090a", msg))

        // Build a second rotation from newKp -> newerKp with the SAME timestamp
        val newerKp = crypto.generateX25519KeyPair()
        val newerEd = crypto.generateEd25519KeyPair()
        val payload2 = io.meshlink.wire.RotationAnnouncement.buildSignablePayload(
            newKp.publicKey, newerKp.publicKey, newEd.publicKey, newerEd.publicKey, now.toULong(),
        )
        val sig2 = crypto.sign(newEd.privateKey, payload2)
        val msg2 = io.meshlink.wire.RotationAnnouncement.RotationMessage(
            newKp.publicKey, newerKp.publicKey, newEd.publicKey, newerEd.publicKey, now.toULong(), sig2,
        )

        // Same timestamp → rejected as stale
        val result = engine.handleRotationAnnouncement("0a0b0c0d0e0f0102030405060708090a", msg2)
        assertIs<RotationResult.Stale>(result)
    }
}
