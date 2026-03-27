package io.meshlink.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoiseXXHandshakeTest {

    private val crypto = createCryptoProvider()

    // ── Vertical slice 1: full handshake completes ──

    @Test
    fun handshakeCompletesBetweenTwoParties() {
        val aliceStatic = crypto.generateX25519KeyPair()
        val bobStatic = crypto.generateX25519KeyPair()

        val alice = NoiseXXHandshake.initiator(crypto, aliceStatic)
        val bob = NoiseXXHandshake.responder(crypto, bobStatic)

        assertFalse(alice.isComplete)
        assertFalse(bob.isComplete)

        // msg1: alice → bob
        val msg1 = alice.writeMessage()
        bob.readMessage(msg1)

        // msg2: bob → alice
        val msg2 = bob.writeMessage()
        alice.readMessage(msg2)

        // msg3: alice → bob
        val msg3 = alice.writeMessage()
        bob.readMessage(msg3)

        assertTrue(alice.isComplete)
        assertTrue(bob.isComplete)
    }

    // ── Vertical slice 2: transport keys are symmetric ──

    @Test
    fun transportKeysAreSymmetric() {
        val aliceStatic = crypto.generateX25519KeyPair()
        val bobStatic = crypto.generateX25519KeyPair()

        val alice = NoiseXXHandshake.initiator(crypto, aliceStatic)
        val bob = NoiseXXHandshake.responder(crypto, bobStatic)

        val msg1 = alice.writeMessage()
        bob.readMessage(msg1)
        val msg2 = bob.writeMessage()
        alice.readMessage(msg2)
        val msg3 = alice.writeMessage()
        bob.readMessage(msg3)

        val aliceResult = alice.finalize()
        val bobResult = bob.finalize()

        // Alice's send key == Bob's receive key and vice versa
        assertTrue(aliceResult.sendKey.contentEquals(bobResult.receiveKey),
            "Alice's send key should equal Bob's receive key")
        assertTrue(aliceResult.receiveKey.contentEquals(bobResult.sendKey),
            "Alice's receive key should equal Bob's send key")
    }

    // ── Vertical slice 3: remote static keys exchanged correctly ──

    @Test
    fun remoteStaticKeysExchangedCorrectly() {
        val aliceStatic = crypto.generateX25519KeyPair()
        val bobStatic = crypto.generateX25519KeyPair()

        val alice = NoiseXXHandshake.initiator(crypto, aliceStatic)
        val bob = NoiseXXHandshake.responder(crypto, bobStatic)

        val msg1 = alice.writeMessage()
        bob.readMessage(msg1)
        val msg2 = bob.writeMessage()
        alice.readMessage(msg2)
        val msg3 = alice.writeMessage()
        bob.readMessage(msg3)

        val aliceResult = alice.finalize()
        val bobResult = bob.finalize()

        assertTrue(aliceResult.remoteStaticKey.contentEquals(bobStatic.publicKey),
            "Alice should know Bob's static key")
        assertTrue(bobResult.remoteStaticKey.contentEquals(aliceStatic.publicKey),
            "Bob should know Alice's static key")
    }

    // ── Vertical slice 4: handshake payloads ──

    @Test
    fun handshakeCarriesPayloads() {
        val aliceStatic = crypto.generateX25519KeyPair()
        val bobStatic = crypto.generateX25519KeyPair()

        val alice = NoiseXXHandshake.initiator(crypto, aliceStatic)
        val bob = NoiseXXHandshake.responder(crypto, bobStatic)

        val payload1 = "hello-from-alice".encodeToByteArray()
        val msg1 = alice.writeMessage(payload1)
        val recv1 = bob.readMessage(msg1)
        assertTrue(payload1.contentEquals(recv1), "Payload in msg1 should be delivered")

        val payload2 = "hello-from-bob".encodeToByteArray()
        val msg2 = bob.writeMessage(payload2)
        val recv2 = alice.readMessage(msg2)
        assertTrue(payload2.contentEquals(recv2), "Payload in msg2 should be delivered")

        val payload3 = "capabilities".encodeToByteArray()
        val msg3 = alice.writeMessage(payload3)
        val recv3 = bob.readMessage(msg3)
        assertTrue(payload3.contentEquals(recv3), "Payload in msg3 should be delivered")
    }

    // ── Vertical slice 5: finalize before complete throws ──

    @Test
    fun finalizeBeforeCompleteThrows() {
        val aliceStatic = crypto.generateX25519KeyPair()
        val alice = NoiseXXHandshake.initiator(crypto, aliceStatic)

        assertFailsWith<IllegalStateException> {
            alice.finalize()
        }
    }

    // ── Vertical slice 6: each handshake produces different keys ──

    @Test
    fun eachHandshakeProducesDifferentKeys() {
        val aliceStatic = crypto.generateX25519KeyPair()
        val bobStatic = crypto.generateX25519KeyPair()

        fun doHandshake(): NoiseXXHandshake.HandshakeResult {
            val alice = NoiseXXHandshake.initiator(crypto, aliceStatic)
            val bob = NoiseXXHandshake.responder(crypto, bobStatic)
            bob.readMessage(alice.writeMessage())
            alice.readMessage(bob.writeMessage())
            bob.readMessage(alice.writeMessage())
            return alice.finalize()
        }

        val result1 = doHandshake()
        val result2 = doHandshake()

        // Ephemeral keys are different each time → different transport keys
        assertFalse(result1.sendKey.contentEquals(result2.sendKey),
            "Different handshakes should produce different transport keys")
    }
}
