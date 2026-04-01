package io.meshlink.crypto

import io.meshlink.wire.WireCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeerHandshakeManagerTest {

    private val crypto = CryptoProvider()
    private val aliceStatic = crypto.generateX25519KeyPair()
    private val bobStatic = crypto.generateX25519KeyPair()

    // --- TDD Cycle 1: Two peers complete handshake via message exchange ---
    @Test
    fun twoPeersCompleteHandshake() {
        val aliceManager = PeerHandshakeManager(crypto, aliceStatic)
        val bobManager = PeerHandshakeManager(crypto, bobStatic)

        val bobId = byteArrayOf(0xBB.toByte())
        val aliceId = byteArrayOf(0xAA.toByte())

        // Alice initiates → produces msg1
        val msg1 = aliceManager.initiateHandshake(bobId)
        assertNotNull(msg1, "Initiator should produce msg1")

        // Bob receives msg1 → produces msg2
        val msg2 = bobManager.handleIncoming(aliceId, msg1)
        assertNotNull(msg2, "Responder should produce msg2 after receiving msg1")

        // Alice receives msg2 → produces msg3
        val msg3 = aliceManager.handleIncoming(bobId, msg2)
        assertNotNull(msg3, "Initiator should produce msg3 after receiving msg2")

        // Bob receives msg3 → handshake complete (no response)
        val msg4 = bobManager.handleIncoming(aliceId, msg3)
        assertNull(msg4, "No more messages after msg3")

        // Both sides should have session keys
        assertTrue(aliceManager.isComplete(bobId))
        assertTrue(bobManager.isComplete(aliceId))

        val aliceKeys = aliceManager.getSessionKeys(bobId)
        val bobKeys = bobManager.getSessionKeys(aliceId)

        assertNotNull(aliceKeys)
        assertNotNull(bobKeys)

        // Alice's send key should be Bob's receive key
        assertContentEquals(aliceKeys.sendKey, bobKeys.receiveKey)
        assertContentEquals(aliceKeys.receiveKey, bobKeys.sendKey)
    }

    // --- TDD Cycle 2: Handshake not complete before all messages exchanged ---
    @Test
    fun handshakeNotCompleteBeforeAllMessages() {
        val manager = PeerHandshakeManager(crypto, aliceStatic)
        val peerId = byteArrayOf(0x01)

        assertFalse(manager.isComplete(peerId))
        assertNull(manager.getSessionKeys(peerId))

        manager.initiateHandshake(peerId)
        assertFalse(manager.isComplete(peerId))
    }

    // --- TDD Cycle 3: Handshake carries payload (protocol version + capability + PSM) ---
    @Test
    fun handshakeCarriesPayload() {
        val aliceManager = PeerHandshakeManager(crypto, aliceStatic)
        val bobManager = PeerHandshakeManager(crypto, bobStatic)

        val bobId = byteArrayOf(0xBB.toByte())
        val aliceId = byteArrayOf(0xAA.toByte())

        // Handshake payload: version(2B) + capability(1B) + L2CAP PSM(2B) = 5 bytes
        val alicePayload = byteArrayOf(0x01, 0x00, 0x03, 0x00, 0x50) // v1.0, cap=3, psm=80

        val msg1 = aliceManager.initiateHandshake(bobId, alicePayload)!!
        val msg2 = bobManager.handleIncoming(aliceId, msg1)!!
        val msg3 = aliceManager.handleIncoming(bobId, msg2)!!
        bobManager.handleIncoming(aliceId, msg3)

        assertTrue(aliceManager.isComplete(bobId))
        assertTrue(bobManager.isComplete(aliceId))
    }

    // --- TDD Cycle 4: Remote static key is accessible after handshake ---
    @Test
    fun remoteStaticKeyAvailableAfterHandshake() {
        val aliceManager = PeerHandshakeManager(crypto, aliceStatic)
        val bobManager = PeerHandshakeManager(crypto, bobStatic)

        val bobId = byteArrayOf(0xBB.toByte())
        val aliceId = byteArrayOf(0xAA.toByte())

        val msg1 = aliceManager.initiateHandshake(bobId)!!
        val msg2 = bobManager.handleIncoming(aliceId, msg1)!!
        val msg3 = aliceManager.handleIncoming(bobId, msg2)!!
        bobManager.handleIncoming(aliceId, msg3)

        val aliceKeys = aliceManager.getSessionKeys(bobId)!!
        val bobKeys = bobManager.getSessionKeys(aliceId)!!

        // Alice sees Bob's static key, Bob sees Alice's
        assertContentEquals(bobStatic.publicKey, aliceKeys.remoteStaticKey)
        assertContentEquals(aliceStatic.publicKey, bobKeys.remoteStaticKey)
    }

    // --- TDD Cycle 5: Unknown peer returns no session keys ---
    @Test
    fun unknownPeerReturnsNull() {
        val manager = PeerHandshakeManager(crypto, aliceStatic)
        assertNull(manager.getSessionKeys(byteArrayOf(0xFF.toByte())))
        assertFalse(manager.isComplete(byteArrayOf(0xFF.toByte())))
    }
}
