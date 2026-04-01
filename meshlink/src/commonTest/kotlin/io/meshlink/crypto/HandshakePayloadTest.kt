package io.meshlink.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HandshakePayloadTest {

    // ── Encode / decode round-trip ──

    @Test
    fun encodeDecodeRoundTrip() {
        val original = HandshakePayload(
            protocolVersion = 0x0100u,
            capabilityFlags = 0x01u,
            l2capPsm = 0x0050u,
        )
        val bytes = original.encode()
        assertEquals(HandshakePayload.SIZE, bytes.size)
        val decoded = HandshakePayload.decode(bytes)
        assertEquals(original, decoded)
    }

    @Test
    fun encodesAsBigEndian() {
        val payload = HandshakePayload(
            protocolVersion = 0x0102u,  // major=1, minor=2
            capabilityFlags = 0x03u,
            l2capPsm = 0x00C8u,         // 200
        )
        val bytes = payload.encode()
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03, 0x00, 0xC8.toByte()), bytes)
    }

    @Test
    fun decodePreservesValues() {
        val bytes = byteArrayOf(0x00, 0x01, 0x01, 0x00, 0x50)
        val payload = HandshakePayload.decode(bytes)
        assertEquals(0x0001u.toUShort(), payload.protocolVersion)
        assertEquals(0x01u.toUByte(), payload.capabilityFlags)
        assertEquals(0x0050u.toUShort(), payload.l2capPsm)
    }

    @Test
    fun noL2capSupport() {
        val payload = HandshakePayload(
            protocolVersion = 0x0001u,
            capabilityFlags = 0x00u,
            l2capPsm = 0x0000u,
        )
        val decoded = HandshakePayload.decode(payload.encode())
        assertEquals(0x00u.toUByte(), decoded.capabilityFlags)
        assertEquals(0x0000u.toUShort(), decoded.l2capPsm)
    }

    @Test
    fun l2capCapFlag() {
        assertEquals(0x01u.toUByte(), HandshakePayload.CAP_L2CAP)
    }

    @Test
    fun maxValues() {
        val payload = HandshakePayload(
            protocolVersion = UShort.MAX_VALUE,
            capabilityFlags = UByte.MAX_VALUE,
            l2capPsm = UShort.MAX_VALUE,
        )
        val decoded = HandshakePayload.decode(payload.encode())
        assertEquals(payload, decoded)
    }

    @Test
    fun zeroValues() {
        val payload = HandshakePayload(0u, 0u, 0u)
        val decoded = HandshakePayload.decode(payload.encode())
        assertEquals(payload, decoded)
    }

    @Test
    fun decodeTooShortThrows() {
        assertFailsWith<IllegalArgumentException> {
            HandshakePayload.decode(byteArrayOf(0x01, 0x02))
        }
    }

    @Test
    fun decodeExtraBytesIgnored() {
        val bytes = byteArrayOf(0x00, 0x01, 0x01, 0x00, 0x50, 0xFF.toByte(), 0xFF.toByte())
        val payload = HandshakePayload.decode(bytes)
        assertEquals(0x0001u.toUShort(), payload.protocolVersion)
    }

    // ── Noise XX handshake payload exchange ──

    private val crypto = CryptoProvider()

    @Test
    fun handshakeExchangesPayloadInMsg2AndMsg3() {
        val aliceStatic = crypto.generateX25519KeyPair()
        val bobStatic = crypto.generateX25519KeyPair()

        val alicePayload = HandshakePayload(0x0001u, 0x01u, 0x0050u).encode()
        val bobPayload = HandshakePayload(0x0001u, 0x00u, 0x0000u).encode()

        val alice = NoiseXXHandshake.initiator(crypto, aliceStatic)
        val bob = NoiseXXHandshake.responder(crypto, bobStatic)

        // msg1: alice → bob (no capability payload)
        val msg1 = alice.writeMessage()
        bob.readMessage(msg1)

        // msg2: bob → alice (bob sends payload)
        val msg2 = bob.writeMessage(bobPayload)
        val recvBobPayload = alice.readMessage(msg2)

        // msg3: alice → bob (alice sends payload)
        val msg3 = alice.writeMessage(alicePayload)
        val recvAlicePayload = bob.readMessage(msg3)

        assertTrue(alice.isComplete)
        assertTrue(bob.isComplete)

        // Verify payloads delivered correctly
        assertContentEquals(bobPayload, recvBobPayload)
        assertContentEquals(alicePayload, recvAlicePayload)

        // Verify peerPayload property
        assertContentEquals(bobPayload, alice.peerPayload)
        assertContentEquals(alicePayload, bob.peerPayload)

        // Verify via HandshakeResult
        val aliceResult = alice.finalize()
        val bobResult = bob.finalize()
        assertContentEquals(bobPayload, aliceResult.peerPayload)
        assertContentEquals(alicePayload, bobResult.peerPayload)

        // Decode and verify values
        val decodedBob = HandshakePayload.decode(aliceResult.peerPayload!!)
        assertEquals(0x0001u.toUShort(), decodedBob.protocolVersion)
        assertEquals(0x00u.toUByte(), decodedBob.capabilityFlags)
        assertEquals(0x0000u.toUShort(), decodedBob.l2capPsm)

        val decodedAlice = HandshakePayload.decode(bobResult.peerPayload!!)
        assertEquals(0x0001u.toUShort(), decodedAlice.protocolVersion)
        assertEquals(0x01u.toUByte(), decodedAlice.capabilityFlags)
        assertEquals(0x0050u.toUShort(), decodedAlice.l2capPsm)
    }

    @Test
    fun handshakeWorksWithoutPayload() {
        val aliceStatic = crypto.generateX25519KeyPair()
        val bobStatic = crypto.generateX25519KeyPair()

        val alice = NoiseXXHandshake.initiator(crypto, aliceStatic)
        val bob = NoiseXXHandshake.responder(crypto, bobStatic)

        bob.readMessage(alice.writeMessage())
        alice.readMessage(bob.writeMessage())
        bob.readMessage(alice.writeMessage())

        assertTrue(alice.isComplete)
        assertTrue(bob.isComplete)

        val aliceResult = alice.finalize()
        val bobResult = bob.finalize()

        // No payloads → peerPayload is null
        assertEquals(null, aliceResult.peerPayload)
        assertEquals(null, bobResult.peerPayload)

        // Keys still work
        assertContentEquals(aliceResult.sendKey, bobResult.receiveKey)
    }

    @Test
    fun peerHandshakeManagerExchangesPayloads() {
        val aliceStatic = crypto.generateX25519KeyPair()
        val bobStatic = crypto.generateX25519KeyPair()

        val alicePayload = HandshakePayload(0x0001u, 0x01u, 0x0050u).encode()
        val bobPayload = HandshakePayload(0x0001u, 0x00u, 0x0000u).encode()

        val aliceManager = PeerHandshakeManager(crypto, aliceStatic, alicePayload)
        val bobManager = PeerHandshakeManager(crypto, bobStatic, bobPayload)

        val bobId = byteArrayOf(0xBB.toByte())
        val aliceId = byteArrayOf(0xAA.toByte())

        val msg1 = aliceManager.initiateHandshake(bobId)!!
        val msg2 = bobManager.handleIncoming(aliceId, msg1)!!
        val msg3 = aliceManager.handleIncoming(bobId, msg2)!!
        bobManager.handleIncoming(aliceId, msg3)

        assertTrue(aliceManager.isComplete(bobId))
        assertTrue(bobManager.isComplete(aliceId))

        // Alice received Bob's payload (from msg2)
        val aliceSeesBob = aliceManager.getPeerPayload(bobId)!!
        val decodedBob = HandshakePayload.decode(aliceSeesBob)
        assertEquals(0x0001u.toUShort(), decodedBob.protocolVersion)
        assertEquals(0x00u.toUByte(), decodedBob.capabilityFlags)
        assertEquals(0x0000u.toUShort(), decodedBob.l2capPsm)

        // Bob received Alice's payload (from msg3)
        val bobSeesAlice = bobManager.getPeerPayload(aliceId)!!
        val decodedAlice = HandshakePayload.decode(bobSeesAlice)
        assertEquals(0x0001u.toUShort(), decodedAlice.protocolVersion)
        assertEquals(0x01u.toUByte(), decodedAlice.capabilityFlags)
        assertEquals(0x0050u.toUShort(), decodedAlice.l2capPsm)
    }

    @Test
    fun peerHandshakeManagerWorksWithoutPayload() {
        val aliceStatic = crypto.generateX25519KeyPair()
        val bobStatic = crypto.generateX25519KeyPair()

        // No localPayload (default empty)
        val aliceManager = PeerHandshakeManager(crypto, aliceStatic)
        val bobManager = PeerHandshakeManager(crypto, bobStatic)

        val bobId = byteArrayOf(0xBB.toByte())
        val aliceId = byteArrayOf(0xAA.toByte())

        val msg1 = aliceManager.initiateHandshake(bobId)!!
        val msg2 = bobManager.handleIncoming(aliceId, msg1)!!
        val msg3 = aliceManager.handleIncoming(bobId, msg2)!!
        bobManager.handleIncoming(aliceId, msg3)

        assertTrue(aliceManager.isComplete(bobId))
        assertTrue(bobManager.isComplete(aliceId))

        // No payload exchanged
        assertEquals(null, aliceManager.getPeerPayload(bobId))
        assertEquals(null, bobManager.getPeerPayload(aliceId))
    }
}
