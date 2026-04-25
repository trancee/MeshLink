package ch.trancee.meshlink.crypto.noise

import ch.trancee.meshlink.crypto.createCryptoProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class NoiseXXHandshakeTest {

    private val crypto = createCryptoProvider()

    // ── Happy path: full 3-message handshake ──────────────────────────────────

    @Test
    fun fullHandshakeDerivesMatchingTransportKeys() {
        val initiatorStatic = crypto.generateX25519KeyPair()
        val responderStatic = crypto.generateX25519KeyPair()

        val initiator = NoiseXXInitiator(crypto, initiatorStatic)
        val responder = NoiseXXResponder(crypto, responderStatic)

        val msg1 = initiator.writeMessage1()
        responder.readMessage1(msg1)
        val msg2 = responder.writeMessage2()
        initiator.readMessage2(msg2)
        val msg3 = initiator.writeMessage3()
        responder.readMessage3(msg3)

        val iSession = initiator.finalize()
        val rSession = responder.finalize()

        // Transport keys must agree: initiator.encrypt → responder.decrypt
        val plaintext = "hello responder".encodeToByteArray()
        val ct = iSession.encrypt(plaintext)
        val recovered = rSession.decrypt(ct)
        assertContentEquals(plaintext, recovered)

        // And the reverse direction: responder.encrypt → initiator.decrypt
        val plaintext2 = "hello initiator".encodeToByteArray()
        val ct2 = rSession.encrypt(plaintext2)
        val recovered2 = iSession.decrypt(ct2)
        assertContentEquals(plaintext2, recovered2)
    }

    @Test
    fun handshakeHashesMatchAfterCompletion() {
        val initiatorStatic = crypto.generateX25519KeyPair()
        val responderStatic = crypto.generateX25519KeyPair()

        val initiator = NoiseXXInitiator(crypto, initiatorStatic)
        val responder = NoiseXXResponder(crypto, responderStatic)

        val msg1 = initiator.writeMessage1()
        responder.readMessage1(msg1)
        val msg2 = responder.writeMessage2()
        initiator.readMessage2(msg2)
        val msg3 = initiator.writeMessage3()
        responder.readMessage3(msg3)

        val iSession = initiator.finalize()
        val rSession = responder.finalize()

        // Channel binding: both parties must have the same handshake hash
        assertContentEquals(iSession.getHandshakeHash(), rSession.getHandshakeHash())
        assertEquals(32, iSession.getHandshakeHash().size)
    }

    @Test
    fun remotesStaticKeysAreCorrectAfterHandshake() {
        val initiatorStatic = crypto.generateX25519KeyPair()
        val responderStatic = crypto.generateX25519KeyPair()

        val initiator = NoiseXXInitiator(crypto, initiatorStatic)
        val responder = NoiseXXResponder(crypto, responderStatic)

        val msg1 = initiator.writeMessage1()
        responder.readMessage1(msg1)
        val msg2 = responder.writeMessage2()
        initiator.readMessage2(msg2)
        val msg3 = initiator.writeMessage3()
        responder.readMessage3(msg3)

        val iSession = initiator.finalize()
        val rSession = responder.finalize()

        // Initiator sees responder's static key; responder sees initiator's static key
        assertContentEquals(responderStatic.publicKey, iSession.getRemoteStaticKey())
        assertContentEquals(initiatorStatic.publicKey, rSession.getRemoteStaticKey())
    }

    // ── Payload round-trip ────────────────────────────────────────────────────

    @Test
    fun payloadsInMessages2And3AreDeliveredCorrectly() {
        val initiatorStatic = crypto.generateX25519KeyPair()
        val responderStatic = crypto.generateX25519KeyPair()

        val initiator = NoiseXXInitiator(crypto, initiatorStatic)
        val responder = NoiseXXResponder(crypto, responderStatic)

        val payload2 = "responder-hello".encodeToByteArray()
        val payload3 = "initiator-hello".encodeToByteArray()

        val msg1 = initiator.writeMessage1()
        responder.readMessage1(msg1)
        val msg2 = responder.writeMessage2(payload2)
        val received2 = initiator.readMessage2(msg2)
        val msg3 = initiator.writeMessage3(payload3)
        val received3 = responder.readMessage3(msg3)

        assertContentEquals(payload2, received2)
        assertContentEquals(payload3, received3)
    }

    @Test
    fun emptyPayloadsInAllMessages() {
        val initiatorStatic = crypto.generateX25519KeyPair()
        val responderStatic = crypto.generateX25519KeyPair()

        val initiator = NoiseXXInitiator(crypto, initiatorStatic)
        val responder = NoiseXXResponder(crypto, responderStatic)

        val msg1 = initiator.writeMessage1(ByteArray(0))
        val received1 = responder.readMessage1(msg1)
        val msg2 = responder.writeMessage2(ByteArray(0))
        val received2 = initiator.readMessage2(msg2)
        val msg3 = initiator.writeMessage3(ByteArray(0))
        val received3 = responder.readMessage3(msg3)

        assertContentEquals(ByteArray(0), received1)
        assertContentEquals(ByteArray(0), received2)
        assertContentEquals(ByteArray(0), received3)

        val iSession = initiator.finalize()
        val rSession = responder.finalize()

        // Handshake still produces working transport session
        val pt = "transport".encodeToByteArray()
        assertContentEquals(pt, rSession.decrypt(iSession.encrypt(pt)))
    }

    // ── Post-handshake transport encryption ───────────────────────────────────

    @Test
    fun multipleMessagesPostHandshakeAllDecryptCorrectly() {
        val iStatic = crypto.generateX25519KeyPair()
        val rStatic = crypto.generateX25519KeyPair()
        val (iSession, rSession) = completeHandshake(iStatic, rStatic)

        for (i in 0 until 12) {
            val msg = "message-$i".encodeToByteArray()
            val ct = iSession.encrypt(msg)
            val pt = rSession.decrypt(ct)
            assertContentEquals(msg, pt, "Message $i must round-trip correctly")
        }

        for (i in 0 until 12) {
            val msg = "reply-$i".encodeToByteArray()
            val ct = rSession.encrypt(msg)
            val pt = iSession.decrypt(ct)
            assertContentEquals(msg, pt, "Reply $i must round-trip correctly")
        }
    }

    @Test
    fun nonceIncrementsMakeEachCiphertextUnique() {
        val iStatic = crypto.generateX25519KeyPair()
        val rStatic = crypto.generateX25519KeyPair()
        val (iSession, _) = completeHandshake(iStatic, rStatic)

        val plaintext = "same plaintext".encodeToByteArray()
        val ct1 = iSession.encrypt(plaintext)
        val ct2 = iSession.encrypt(plaintext)
        assertFalse(ct1.contentEquals(ct2), "Same plaintext with different nonces must differ")
    }

    @Test
    fun handshakeHashReturnsCopy() {
        val iStatic = crypto.generateX25519KeyPair()
        val rStatic = crypto.generateX25519KeyPair()
        val (iSession, _) = completeHandshake(iStatic, rStatic)

        val hash1 = iSession.getHandshakeHash()
        hash1[0] = (hash1[0].toInt() xor 0xFF).toByte()
        val hash2 = iSession.getHandshakeHash()
        assertFalse(hash1[0] == hash2[0], "Modifying returned hash must not affect stored copy")
    }

    @Test
    fun remoteStaticKeyReturnsCopy() {
        val iStatic = crypto.generateX25519KeyPair()
        val rStatic = crypto.generateX25519KeyPair()
        val (iSession, _) = completeHandshake(iStatic, rStatic)

        val key1 = iSession.getRemoteStaticKey()
        key1[0] = (key1[0].toInt() xor 0xFF).toByte()
        val key2 = iSession.getRemoteStaticKey()
        assertFalse(key1[0] == key2[0], "Modifying returned key must not affect stored copy")
    }

    // ── Tampered message 1 ────────────────────────────────────────────────────

    @Test
    fun tamperedMessage1CausesAuthFailureInMessage2() {
        val iStatic = crypto.generateX25519KeyPair()
        val rStatic = crypto.generateX25519KeyPair()

        val initiator = NoiseXXInitiator(crypto, iStatic)
        val responder = NoiseXXResponder(crypto, rStatic)

        val msg1 = initiator.writeMessage1().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        responder.readMessage1(msg1)
        val msg2 = responder.writeMessage2()

        // The initiator will fail when it tries to decrypt msg2 because the shared DH secrets
        // diverged after the tampered ephemeral was mixed into the transcript.
        assertFailsWith<IllegalStateException> { initiator.readMessage2(msg2) }
    }

    // ── Tampered message 2 ────────────────────────────────────────────────────

    @Test
    fun tamperedMessage2ThrowsIllegalStateException() {
        val iStatic = crypto.generateX25519KeyPair()
        val rStatic = crypto.generateX25519KeyPair()

        val initiator = NoiseXXInitiator(crypto, iStatic)
        val responder = NoiseXXResponder(crypto, rStatic)

        val msg1 = initiator.writeMessage1()
        responder.readMessage1(msg1)
        val msg2 = responder.writeMessage2()

        // Tamper the last byte (inside the encrypted/authenticated portion)
        msg2[msg2.size - 1] = (msg2[msg2.size - 1].toInt() xor 0x01).toByte()

        assertFailsWith<IllegalStateException> { initiator.readMessage2(msg2) }
    }

    // ── Tampered message 3 ────────────────────────────────────────────────────

    @Test
    fun tamperedMessage3ThrowsIllegalStateException() {
        val iStatic = crypto.generateX25519KeyPair()
        val rStatic = crypto.generateX25519KeyPair()

        val initiator = NoiseXXInitiator(crypto, iStatic)
        val responder = NoiseXXResponder(crypto, rStatic)

        val msg1 = initiator.writeMessage1()
        responder.readMessage1(msg1)
        val msg2 = responder.writeMessage2()
        initiator.readMessage2(msg2)
        val msg3 = initiator.writeMessage3()

        msg3[msg3.size - 1] = (msg3[msg3.size - 1].toInt() xor 0x01).toByte()

        assertFailsWith<IllegalStateException> { responder.readMessage3(msg3) }
    }

    // ── Wrong session (mismatched key) ────────────────────────────────────────

    @Test
    fun wrongSessionCannotDecryptTransportMessage() {
        val iStatic1 = crypto.generateX25519KeyPair()
        val rStatic1 = crypto.generateX25519KeyPair()
        val iStatic2 = crypto.generateX25519KeyPair()
        val rStatic2 = crypto.generateX25519KeyPair()

        val (iSession1, _) = completeHandshake(iStatic1, rStatic1)
        val (_, rSession2) = completeHandshake(iStatic2, rStatic2)

        val ct = iSession1.encrypt("secret".encodeToByteArray())
        assertFailsWith<IllegalStateException> { rSession2.decrypt(ct) }
    }

    // ── Message 1: too-short message ──────────────────────────────────────────

    @Test
    fun readMessage1TooShortThrowsIllegalArgumentException() {
        val rStatic = crypto.generateX25519KeyPair()
        val responder = NoiseXXResponder(crypto, rStatic)
        assertFailsWith<IllegalArgumentException> {
            responder.readMessage1(ByteArray(10)) // needs 32 bytes for ephemeral key
        }
    }

    // ── Simultaneous race resolution ──────────────────────────────────────────

    @Test
    fun resolveHandshakeRaceHigherKeyStaysInitiator() {
        val higher = ByteArray(32) { if (it == 0) 0xFF.toByte() else 0x00 }
        val lower = ByteArray(32) { 0x00 }
        assertEquals(
            HandshakeRole.STAY_INITIATOR,
            resolveHandshakeRace(localEphemeral = higher, remoteEphemeral = lower),
        )
        assertEquals(
            HandshakeRole.BECOME_RESPONDER,
            resolveHandshakeRace(localEphemeral = lower, remoteEphemeral = higher),
        )
    }

    @Test
    fun resolveHandshakeRaceEqualKeysDefaultsToInitiator() {
        val key = ByteArray(32) { 0x42 }
        assertEquals(
            HandshakeRole.STAY_INITIATOR,
            resolveHandshakeRace(localEphemeral = key, remoteEphemeral = key),
        )
    }

    @Test
    fun resolveHandshakeRaceDifferenceLaterInArray() {
        val local = ByteArray(32) { 0x00 }.also { it[31] = 0x02 }
        val remote = ByteArray(32) { 0x00 }.also { it[31] = 0x01 }
        assertEquals(
            HandshakeRole.STAY_INITIATOR,
            resolveHandshakeRace(localEphemeral = local, remoteEphemeral = remote),
        )
    }

    @Test
    fun resolveHandshakeRaceHighByteUnsignedComparison() {
        // 0xFF (unsigned 255) must beat 0x01 (unsigned 1) even though 0xFF is a negative signed
        // byte
        val local = ByteArray(32) { if (it == 0) 0xFF.toByte() else 0x00 }
        val remote = ByteArray(32) { if (it == 0) 0x01.toByte() else 0x00 }
        assertEquals(
            HandshakeRole.STAY_INITIATOR,
            resolveHandshakeRace(localEphemeral = local, remoteEphemeral = remote),
        )
    }

    @Test
    fun localEphemeralPublicKeyIsAccessibleBeforeHandshake() {
        val static = crypto.generateX25519KeyPair()
        val initiator = NoiseXXInitiator(crypto, static)
        val responder = NoiseXXResponder(crypto, static)
        assertEquals(32, initiator.localEphemeralPublicKey.size)
        assertEquals(32, responder.localEphemeralPublicKey.size)
    }

    // ── Invalid state transitions ─────────────────────────────────────────────

    @Test
    fun callFinalizeBeforeHandshakeCompletedThrows() {
        val static = crypto.generateX25519KeyPair()
        val initiator = NoiseXXInitiator(crypto, static)
        // finalize() before writeMessage1 and beyond must throw
        assertFailsWith<IllegalStateException> { initiator.finalize() }
    }

    @Test
    fun writeMessage1TwiceThrowsIllegalStateException() {
        val static = crypto.generateX25519KeyPair()
        val initiator = NoiseXXInitiator(crypto, static)
        initiator.writeMessage1()
        assertFailsWith<IllegalStateException> { initiator.writeMessage1() }
    }

    @Test
    fun responderWriteMessage2WithoutReadMessage1Throws() {
        val static = crypto.generateX25519KeyPair()
        val responder = NoiseXXResponder(crypto, static)
        // Responder at step=0 cannot call writeMessage (only readMessage is valid)
        assertFailsWith<IllegalStateException> { responder.writeMessage2() }
    }

    @Test
    fun initiatorReadMessage2WithoutWriteMessage1Throws() {
        val static = crypto.generateX25519KeyPair()
        val initiator = NoiseXXInitiator(crypto, static)
        // Initiator at step=0 cannot call readMessage (only writeMessage is valid)
        assertFailsWith<IllegalStateException> { initiator.readMessage2(ByteArray(100)) }
    }

    // ── Coverage gap: HandshakeState.writeMessage() default parameter ─────────

    @Test
    fun handshakeStateWriteMessageDefaultPayload() {
        // Calls HandshakeState.writeMessage() without arguments to exercise the default-parameter
        // synthetic generated by the Kotlin compiler.
        val static = crypto.generateX25519KeyPair()
        val hs = HandshakeState(crypto, isInitiator = true, static)
        val msg = hs.writeMessage() // uses default ByteArray(0) payload
        // Initiator msg1 = 32-byte ephemeral key + 0-byte payload (no key yet, passes through)
        assertEquals(32, msg.size)
    }

    // ── Coverage gap: readTokenS too-short message ────────────────────────────

    @Test
    fun readMessage2TooShortForStaticKeyThrows() {
        val iStatic = crypto.generateX25519KeyPair()
        val rStatic = crypto.generateX25519KeyPair()
        val initiator = NoiseXXInitiator(crypto, iStatic)
        val responder = NoiseXXResponder(crypto, rStatic)

        val msg1 = initiator.writeMessage1()
        responder.readMessage1(msg1)
        val msg2 = responder.writeMessage2()

        // Message 2 layout: 32-byte ephemeral + 48-byte encrypted static + 16-byte payload tag.
        // Truncate to just the ephemeral key so readTokenS sees a too-short message.
        val truncated = msg2.copyOf(32)
        assertFailsWith<IllegalArgumentException> { initiator.readMessage2(truncated) }
    }

    // ── Coverage gap: getRemoteStaticKey before handshake ─────────────────────

    @Test
    fun getRemoteStaticKeyBeforeHandshakeThrows() {
        val static = crypto.generateX25519KeyPair()
        val hs = HandshakeState(crypto, isInitiator = true, static)
        assertFailsWith<IllegalStateException> { hs.getRemoteStaticKey() }
    }

    // ── Coverage gap: readMessage when-else branch (initiator at step 2) ──────

    @Test
    fun initiatorReadMessageAtStepTwoThrows() {
        // Advances initiator to step 2 (after readMessage2), then calls readMessage again.
        // The when-expression in readMessage has no arm for (isInitiator=true, step=2) so it
        // falls to else → IllegalStateException, covering the previously-missed branch.
        val iStatic = crypto.generateX25519KeyPair()
        val rStatic = crypto.generateX25519KeyPair()
        val initiator = NoiseXXInitiator(crypto, iStatic)
        val responder = NoiseXXResponder(crypto, rStatic)

        val msg1 = initiator.writeMessage1()
        responder.readMessage1(msg1)
        val msg2 = responder.writeMessage2()
        initiator.readMessage2(msg2) // step advances to 2

        // Initiator at step 2 cannot call readMessage (only writeMessage is valid)
        assertFailsWith<IllegalStateException> { initiator.readMessage2(msg2) }
    }

    // ── Coverage gap: readMessage when-arm3 condition (!isInitiator && step!=2) ──

    @Test
    fun responderReadMessageAtStepOneThrows() {
        // Responder at step=1 (after readMessage1, before writeMessage2) calling readMessage.
        // The when-expression arm3 check is: !isInitiator && step==2.
        // With !isInitiator=true and step=1 (!=2), the condition is false → else → throws.
        // This covers the !isInitiator=true, step!=2 branch of the arm3 condition check.
        val iStatic = crypto.generateX25519KeyPair()
        val rStatic = crypto.generateX25519KeyPair()
        val initiator = NoiseXXInitiator(crypto, iStatic)
        val responder = NoiseXXResponder(crypto, rStatic)

        val msg1 = initiator.writeMessage1()
        responder.readMessage1(msg1) // responder step advances to 1

        // DO NOT call writeMessage2 — responder stays at step=1.
        // Calling readMessage3 at step=1 hits the else branch.
        assertFailsWith<IllegalStateException> { responder.readMessage3(msg1) }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun completeHandshake(
        iStatic: ch.trancee.meshlink.crypto.KeyPair,
        rStatic: ch.trancee.meshlink.crypto.KeyPair,
    ): Pair<NoiseSession, NoiseSession> {
        val initiator = NoiseXXInitiator(crypto, iStatic)
        val responder = NoiseXXResponder(crypto, rStatic)

        val msg1 = initiator.writeMessage1()
        responder.readMessage1(msg1)
        val msg2 = responder.writeMessage2()
        initiator.readMessage2(msg2)
        val msg3 = initiator.writeMessage3()
        responder.readMessage3(msg3)

        return Pair(initiator.finalize(), responder.finalize())
    }
}
