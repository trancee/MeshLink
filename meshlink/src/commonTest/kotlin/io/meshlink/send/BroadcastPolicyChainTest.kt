package io.meshlink.send

import io.meshlink.crypto.SignedPayload
import io.meshlink.util.ByteArrayKey
import io.meshlink.util.RateLimitResult
import io.meshlink.wire.WireCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BroadcastPolicyChainTest {

    private val localPeerId = ByteArray(16) { it.toByte() }
    private val appIdHash = ByteArray(16) { 0xAA.toByte() }

    private fun chain(
        bufferCapacity: Int = 1024,
        checkBroadcastRate: () -> RateLimitResult = { RateLimitResult.Allowed },
        signData: ((ByteArray) -> SignedPayload?)? = null,
    ) = BroadcastPolicyChain(
        bufferCapacity = bufferCapacity,
        checkBroadcastRate = checkBroadcastRate,
        signData = signData,
        appIdHash = appIdHash,
        localPeerId = localPeerId,
    )

    // ── Buffer capacity ─────────────────────────────────────────

    @Test
    fun bufferFull_rejectsOversizedPayload() {
        val c = chain(bufferCapacity = 100)
        val result = c.evaluate(ByteArray(101), maxHops = 3u)
        assertIs<BroadcastDecision.BufferFull>(result)
    }

    @Test
    fun bufferFull_allowsExactCapacity() {
        val c = chain(bufferCapacity = 100)
        val result = c.evaluate(ByteArray(100), maxHops = 3u)
        assertIs<BroadcastDecision.Proceed>(result)
    }

    // ── Rate limiting ───────────────────────────────────────────

    @Test
    fun rateLimited_rejectsWhenBroadcastRateExceeded() {
        val c = chain(
            checkBroadcastRate = { RateLimitResult.Limited("broadcast", "broadcast") },
        )
        val result = c.evaluate(ByteArray(10), maxHops = 3u)
        assertIs<BroadcastDecision.RateLimited>(result)
    }

    @Test
    fun rateLimited_allowsWhenBroadcastRateOk() {
        val c = chain(
            checkBroadcastRate = { RateLimitResult.Allowed },
        )
        val result = c.evaluate(ByteArray(10), maxHops = 3u)
        assertIs<BroadcastDecision.Proceed>(result)
    }

    // ── Policy priority ─────────────────────────────────────────

    @Test
    fun priority_bufferFullCheckedBeforeRateLimit() {
        val c = chain(
            bufferCapacity = 5,
            checkBroadcastRate = { RateLimitResult.Limited("broadcast", "broadcast") },
        )
        val result = c.evaluate(ByteArray(10), maxHops = 3u)
        // Buffer check comes first, so we get BufferFull not RateLimited
        assertIs<BroadcastDecision.BufferFull>(result)
    }

    // ── Proceed: unsigned ───────────────────────────────────────

    @Test
    fun proceed_unsignedBroadcastEncodesCorrectly() {
        val c = chain(signData = null)
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        val result = c.evaluate(payload, maxHops = 5u)
        assertIs<BroadcastDecision.Proceed>(result)

        // Verify the encoded frame can be decoded
        val decoded = WireCodec.decodeBroadcast(result.encodedFrame)
        assertEquals(5u, decoded.remainingHops)
        assertTrue(decoded.payload.contentEquals(payload))
        assertTrue(decoded.origin.contentEquals(localPeerId))
        assertTrue(decoded.appIdHash.contentEquals(appIdHash))
        assertEquals(0, decoded.signature.size)
    }

    @Test
    fun proceed_messageIdIsUniquePerCall() {
        val c = chain()
        val r1 = c.evaluate(ByteArray(1), maxHops = 1u) as BroadcastDecision.Proceed
        val r2 = c.evaluate(ByteArray(1), maxHops = 1u) as BroadcastDecision.Proceed
        assertTrue(!r1.messageId.contentEquals(r2.messageId))
    }

    // ── Proceed: signed ─────────────────────────────────────────

    @Test
    fun proceed_signedBroadcastIncludesSignature() {
        val fakeSignature = ByteArray(64) { 0x55 }
        val fakePublicKey = ByteArray(32) { 0x66 }
        val c = chain(
            signData = { SignedPayload(fakeSignature, fakePublicKey) },
        )
        val result = c.evaluate(byteArrayOf(0x01), maxHops = 3u)
        assertIs<BroadcastDecision.Proceed>(result)

        val decoded = WireCodec.decodeBroadcast(result.encodedFrame)
        assertTrue(decoded.signature.contentEquals(fakeSignature))
        assertTrue(decoded.signerPublicKey.contentEquals(fakePublicKey))
    }

    @Test
    fun proceed_signDataReceivesCorrectInput() {
        var capturedData: ByteArray? = null
        val c = chain(
            signData = { data ->
                capturedData = data
                SignedPayload(ByteArray(64), ByteArray(32))
            },
        )
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        c.evaluate(payload, maxHops = 3u)

        // signedData = msgIdBytes + localPeerId + appIdHash + payload
        // 16 (msgId) + 16 (peerId) + 16 (appIdHash) + 3 (payload) = 51
        assertEquals(51, capturedData!!.size)
        // Last 3 bytes should be the payload
        assertTrue(capturedData.sliceArray(48..50).contentEquals(payload))
    }

    // ── Dedup marking ───────────────────────────────────────────

    @Test
    fun proceed_markAsSeen_isCalled() {
        var markedKey: ByteArrayKey? = null
        val c = BroadcastPolicyChain(
            bufferCapacity = 1024,
            checkBroadcastRate = { RateLimitResult.Allowed },
            signData = null,
            appIdHash = appIdHash,
            localPeerId = localPeerId,
            markAsSeen = { markedKey = it },
        )
        val result = c.evaluate(byteArrayOf(0x01), maxHops = 3u) as BroadcastDecision.Proceed
        // The marked key bytes should correspond to the messageId
        assertEquals(result.messageId.size, markedKey!!.bytes.size)
    }

    // ── maxHops propagation ─────────────────────────────────────

    @Test
    fun proceed_maxHopsPropagatedToEncodedFrame() {
        val c = chain()
        val r1 = c.evaluate(ByteArray(1), maxHops = 1u) as BroadcastDecision.Proceed
        val r7 = c.evaluate(ByteArray(1), maxHops = 7u) as BroadcastDecision.Proceed
        val d1 = WireCodec.decodeBroadcast(r1.encodedFrame)
        val d7 = WireCodec.decodeBroadcast(r7.encodedFrame)
        assertEquals(1u, d1.remainingHops)
        assertEquals(7u, d7.remainingHops)
    }

    // ── Empty payload ───────────────────────────────────────────

    @Test
    fun proceed_emptyPayloadIsAllowed() {
        val c = chain()
        val result = c.evaluate(ByteArray(0), maxHops = 3u)
        assertIs<BroadcastDecision.Proceed>(result)
        val decoded = WireCodec.decodeBroadcast(result.encodedFrame)
        assertEquals(0, decoded.payload.size)
    }
}
