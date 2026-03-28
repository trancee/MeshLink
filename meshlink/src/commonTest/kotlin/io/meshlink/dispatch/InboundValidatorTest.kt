package io.meshlink.dispatch

import io.meshlink.config.meshLinkConfig
import io.meshlink.crypto.PureKotlinCryptoProvider
import io.meshlink.crypto.SecurityEngine
import io.meshlink.delivery.DeliveryPipeline
import io.meshlink.diagnostics.DiagnosticCode
import io.meshlink.diagnostics.DiagnosticEvent
import io.meshlink.diagnostics.DiagnosticSink
import io.meshlink.util.AppIdFilter
import io.meshlink.util.RateLimitPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InboundValidatorTest {

    private val localPeer = ByteArray(4) { (it + 1).toByte() }

    private fun createValidator(
        securityEngine: SecurityEngine? = null,
        appId: String? = null,
        inboundRateLimit: Int = 0,
        clock: () -> Long = { 0L },
    ): Pair<InboundValidator, DiagnosticSink> {
        val diagnosticSink = DiagnosticSink(bufferCapacity = 64, clock = clock)
        val config = meshLinkConfig {
            requireEncryption = false
            inboundRateLimitPerSenderPerMinute = inboundRateLimit
        }
        val deliveryPipeline = DeliveryPipeline(diagnosticSink = diagnosticSink, clock = clock)
        val rateLimitPolicy = RateLimitPolicy(config, clock)
        val appIdFilter = AppIdFilter(appId)
        val validator = InboundValidator(
            securityEngine = securityEngine,
            deliveryPipeline = deliveryPipeline,
            rateLimitPolicy = rateLimitPolicy,
            appIdFilter = appIdFilter,
            diagnosticSink = diagnosticSink,
            localPeerId = localPeer,
            config = config,
        )
        return validator to diagnosticSink
    }

    private fun DiagnosticSink.drain(): List<DiagnosticEvent> {
        val events = mutableListOf<DiagnosticEvent>()
        drainTo(events)
        return events
    }

    // ── cryptoRequired ─────────────────────────────────────────────

    @Test
    fun cryptoRequiredFalseWhenNoSecurityEngine() {
        val (v, _) = createValidator(securityEngine = null)
        assertFalse(v.cryptoRequired)
    }

    @Test
    fun cryptoRequiredTrueWhenSecurityEnginePresent() {
        val se = SecurityEngine(PureKotlinCryptoProvider())
        val (v, _) = createValidator(securityEngine = se)
        assertTrue(v.cryptoRequired)
    }

    // ── App-ID filtering ───────────────────────────────────────────

    @Test
    fun appIdAcceptsWhenNoFilter() {
        val (v, sink) = createValidator(appId = null)
        assertTrue(v.checkAppId("msg1", "anything".encodeToByteArray()))
        assertTrue(sink.drain().isEmpty())
    }

    @Test
    fun appIdRejectsNonMatchingHash() {
        val (v, sink) = createValidator(appId = "com.example.chat")
        val wrongHash = AppIdFilter.hash("com.other.app")
        assertFalse(v.checkAppId("msg1", wrongHash))
        val events = sink.drain()
        assertEquals(1, events.size)
        assertEquals(DiagnosticCode.APP_ID_REJECTED, events[0].code)
    }

    @Test
    fun appIdAcceptsMatchingHash() {
        val (v, sink) = createValidator(appId = "com.example.chat")
        val matchingHash = AppIdFilter.hash("com.example.chat")
        assertTrue(v.checkAppId("msg1", matchingHash))
        assertTrue(sink.drain().isEmpty())
    }

    // ── Loop detection ─────────────────────────────────────────────

    @Test
    fun loopDetectedWhenLocalPeerInVisitedList() {
        val (v, sink) = createValidator()
        val visited = listOf(ByteArray(4) { 0xFF.toByte() }, localPeer.copyOf())
        assertFalse(v.checkLoop("msg1", visited, "origin01"))
        val events = sink.drain()
        assertEquals(1, events.size)
        assertEquals(DiagnosticCode.LOOP_DETECTED, events[0].code)
    }

    @Test
    fun noLoopWhenLocalPeerNotInVisitedList() {
        val (v, sink) = createValidator()
        val visited = listOf(ByteArray(4) { 0xFF.toByte() })
        assertTrue(v.checkLoop("msg1", visited, "origin01"))
        assertTrue(sink.drain().isEmpty())
    }

    @Test
    fun noLoopWithEmptyVisitedList() {
        val (v, _) = createValidator()
        assertTrue(v.checkLoop("msg1", emptyList(), "origin01"))
    }

    // ── Hop limit ──────────────────────────────────────────────────

    @Test
    fun hopLimitExceededAtZero() {
        val (v, sink) = createValidator()
        assertFalse(v.checkHopLimit("msg1", 0u, "origin01"))
        val events = sink.drain()
        assertEquals(1, events.size)
        assertEquals(DiagnosticCode.HOP_LIMIT_EXCEEDED, events[0].code)
    }

    @Test
    fun hopLimitPassesAboveZero() {
        val (v, sink) = createValidator()
        assertTrue(v.checkHopLimit("msg1", 1u, "origin01"))
        assertTrue(v.checkHopLimit("msg2", 10u, "origin01"))
        assertTrue(sink.drain().isEmpty())
    }

    // ── Replay counter ─────────────────────────────────────────────

    @Test
    fun replayCounterZeroAlwaysPasses() {
        val (v, _) = createValidator()
        assertTrue(v.checkReplay("msg1", "origin01", 0uL))
    }

    @Test
    fun replayCounterAcceptsForwardProgress() {
        val (v, _) = createValidator()
        assertTrue(v.checkReplay("msg1", "origin01", 5uL))
    }

    @Test
    fun replayCounterRejectsRepeatedCounter() {
        val (v, sink) = createValidator()
        assertTrue(v.checkReplay("msg1", "origin01", 5uL))
        sink.drain() // clear
        assertFalse(v.checkReplay("msg2", "origin01", 5uL))
        val events = sink.drain()
        assertEquals(1, events.size)
        assertEquals(DiagnosticCode.REPLAY_REJECTED, events[0].code)
    }

    // ── Inbound rate limiting ──────────────────────────────────────

    @Test
    fun inboundRateDisabledByDefault() {
        val (v, _) = createValidator(inboundRateLimit = 0)
        assertTrue(v.checkInboundRate("origin01"))
    }

    @Test
    fun inboundRateRejectsWhenExceeded() {
        val (v, sink) = createValidator(inboundRateLimit = 1)
        assertTrue(v.checkInboundRate("origin01"))
        sink.drain() // clear
        assertFalse(v.checkInboundRate("origin01"))
        val events = sink.drain()
        assertEquals(1, events.size)
        assertEquals(DiagnosticCode.RATE_LIMIT_HIT, events[0].code)
    }

    // ── Relay rate limiting ────────────────────────────────────────

    @Test
    fun relayRatePassesUnderLimit() {
        val (v, _) = createValidator()
        assertTrue(v.checkRelayRate("origin01", "neighbor01"))
    }

    // ── Signature validation (no crypto) ───────────────────────────

    @Test
    fun routeUpdatePassesWithoutCrypto() {
        val (v, _) = createValidator(securityEngine = null)
        assertTrue(v.validateRouteUpdateSignature("peer01", null, null, ByteArray(0)))
    }

    @Test
    fun broadcastPassesWithoutCrypto() {
        val (v, _) = createValidator(securityEngine = null)
        assertTrue(v.validateBroadcastSignature(ByteArray(0), ByteArray(0), ByteArray(0)))
    }

    @Test
    fun deliveryAckPassesWithoutCrypto() {
        val (v, _) = createValidator(securityEngine = null)
        assertTrue(v.validateDeliveryAckSignature(ByteArray(0), ByteArray(0), ByteArray(0)))
    }

    // ── Signature validation (with crypto) ─────────────────────────

    @Test
    fun routeUpdateRejectsUnsignedWhenCryptoEnabled() {
        val se = SecurityEngine(PureKotlinCryptoProvider())
        val (v, sink) = createValidator(securityEngine = se)
        assertFalse(v.validateRouteUpdateSignature("peer01", null, null, ByteArray(10)))
        val events = sink.drain()
        assertEquals(1, events.size)
        assertEquals(DiagnosticCode.MALFORMED_DATA, events[0].code)
        assertTrue(events[0].payload?.contains("unsigned") == true)
    }

    @Test
    fun routeUpdateRejectsInvalidSignature() {
        val se = SecurityEngine(PureKotlinCryptoProvider())
        val (v, sink) = createValidator(securityEngine = se)
        assertFalse(v.validateRouteUpdateSignature(
            "peer01",
            signature = ByteArray(64),
            signerPublicKey = ByteArray(32),
            signedData = ByteArray(10),
        ))
        val events = sink.drain()
        assertEquals(1, events.size)
        assertTrue(events[0].payload?.contains("verification failed") == true)
    }

    @Test
    fun broadcastRejectsEmptySignatureWhenCryptoEnabled() {
        val se = SecurityEngine(PureKotlinCryptoProvider())
        val (v, _) = createValidator(securityEngine = se)
        assertFalse(v.validateBroadcastSignature(ByteArray(0), ByteArray(0), ByteArray(10)))
    }

    @Test
    fun deliveryAckRejectsEmptySignatureWhenCryptoEnabled() {
        val se = SecurityEngine(PureKotlinCryptoProvider())
        val (v, _) = createValidator(securityEngine = se)
        assertFalse(v.validateDeliveryAckSignature(ByteArray(0), ByteArray(0), ByteArray(10)))
    }

    // ── Unseal ─────────────────────────────────────────────────────

    @Test
    fun unsealReturnsPlaintextWithoutCrypto() {
        val (v, _) = createValidator(securityEngine = null)
        val payload = "hello".encodeToByteArray()
        val result = v.unsealPayload(payload, "test")
        assertNotNull(result)
        assertEquals("hello", result.decodeToString())
    }

    @Test
    fun unsealReturnsNullOnFailure() {
        val se = SecurityEngine(PureKotlinCryptoProvider())
        val (v, sink) = createValidator(securityEngine = se)
        val result = v.unsealPayload("garbage".encodeToByteArray(), "test context")
        assertNull(result)
        val events = sink.drain()
        assertEquals(1, events.size)
        assertEquals(DiagnosticCode.DECRYPTION_FAILED, events[0].code)
    }
}
