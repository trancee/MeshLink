package io.meshlink.gossip

import io.meshlink.crypto.SecurityEngine
import io.meshlink.diagnostics.DiagnosticSink
import io.meshlink.routing.RoutingEngine
import io.meshlink.util.toHex
import io.meshlink.util.toKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GossipCoordinatorTest {

    private val localPeerId = ByteArray(16) { it.toByte() }
    private val peerA = ByteArray(16) { (it + 0x10).toByte() }
    private val peerAHex = peerA.toHex()

    private fun routingEngine(
        gossipIntervalMillis: Long = 5_000L,
        clock: () -> Long = { 0L },
    ) = RoutingEngine(
        localPeerId = localPeerId.toKey(),
        dedupCapacity = 100,
        triggeredUpdateThreshold = 0.3,
        gossipIntervalMillis = gossipIntervalMillis,
        clock = clock,
    )

    private fun coordinator(
        routingEngine: RoutingEngine,
        securityEngine: SecurityEngine? = null,
        gossipIntervalMillis: Long = 5_000L,
        keepaliveIntervalMillis: Long = 10_000L,
        triggeredUpdateBatchMillis: Long = 50L,
        currentPowerMode: () -> String = { "PERFORMANCE" },
        sendFrame: suspend (ByteArray, ByteArray) -> Unit = { _, _ -> },
        clock: () -> Long = { 0L },
    ) = GossipCoordinator(
        routingEngine = routingEngine,
        securityEngine = securityEngine,
        diagnosticSink = DiagnosticSink(),
        localPeerId = localPeerId,
        gossipIntervalMillis = gossipIntervalMillis,
        triggeredUpdateBatchMillis = triggeredUpdateBatchMillis,
        keepaliveIntervalMillis = keepaliveIntervalMillis,
        currentPowerMode = currentPowerMode,
        sendFrame = sendFrame,
        clock = clock,
    )

    // ── broadcastRouteUpdate ────────────────────────────────────

    @Test
    fun routeUpdate_noPeers_sendsNothing() = kotlinx.coroutines.test.runTest {
        val re = routingEngine()
        val sent = mutableListOf<Pair<String, ByteArray>>()
        val gc = coordinator(re, sendFrame = { id, data -> sent.add(id.toHex() to data) })
        gc.broadcastRouteUpdate()
        assertEquals(0, sent.size)
    }

    @Test
    fun routeUpdate_withPeers_sendsToEachPeer() = kotlinx.coroutines.test.runTest {
        val re = routingEngine()
        re.peerSeen(peerA.toKey())
        val peerB = ByteArray(16) { (it + 0x20).toByte() }
        re.peerSeen(peerB.toKey())

        val sentPeers = mutableListOf<String>()
        val gc = coordinator(re, sendFrame = { id, _ -> sentPeers.add(id.toHex()) })
        gc.broadcastRouteUpdate()
        assertEquals(2, sentPeers.size)
        assertTrue(sentPeers.contains(peerAHex))
        assertTrue(sentPeers.contains(peerB.toHex()))
    }

    @Test
    fun routeUpdate_unsigned_sendsRouteUpdateFrame() = kotlinx.coroutines.test.runTest {
        val re = routingEngine()
        re.peerSeen(peerA.toKey())

        val sentData = mutableListOf<ByteArray>()
        val gc = coordinator(re, sendFrame = { _, data -> sentData.add(data) })
        gc.broadcastRouteUpdate()
        assertEquals(1, sentData.size)
        // TYPE_ROUTE_UPDATE is 0x02
        assertEquals(0x02, sentData[0][0].toInt())
    }

    @Test
    fun routeUpdate_recordsGossipSent() = kotlinx.coroutines.test.runTest {
        var time = 0L
        val re = routingEngine(clock = { time })

        re.peerSeen(peerA.toKey())

        val gc = coordinator(re, clock = { time }, sendFrame = { _, _ -> })
        time = 1000L
        gc.broadcastRouteUpdate()
        time = 1500L
        assertEquals(500L, re.timeSinceLastGossip())
    }

    @Test
    fun routeUpdate_triggered_skipsThrottledPeers() = kotlinx.coroutines.test.runTest {
        var time = 0L
        val re = routingEngine(clock = { time })
        re.peerSeen(peerA.toKey())

        val sentPeers = mutableListOf<String>()
        val gc = coordinator(re, clock = { time }, sendFrame = { id, _ -> sentPeers.add(id.toHex()) })

        // First triggered update should send
        gc.broadcastRouteUpdate(isTriggered = true)
        assertEquals(1, sentPeers.size)

        // Immediate second triggered update should be throttled
        time = 100L
        sentPeers.clear()
        gc.broadcastRouteUpdate(isTriggered = true)
        assertEquals(0, sentPeers.size)
    }

    @Test
    fun routeUpdate_nonTriggered_sendsRegardlessOfThrottle() = kotlinx.coroutines.test.runTest {
        var time = 0L
        val re = routingEngine(clock = { time })
        re.peerSeen(peerA.toKey())

        val sentPeers = mutableListOf<String>()
        val gc = coordinator(re, clock = { time }, sendFrame = { id, _ -> sentPeers.add(id.toHex()) })

        gc.broadcastRouteUpdate(isTriggered = false)
        assertEquals(1, sentPeers.size)

        // Non-triggered should always send
        sentPeers.clear()
        time = 100L
        gc.broadcastRouteUpdate(isTriggered = false)
        assertEquals(1, sentPeers.size)
    }

    // ── broadcastKeepalive ──────────────────────────────────────

    @Test
    fun keepalive_noPeers_sendsNothing() = kotlinx.coroutines.test.runTest {
        val re = routingEngine()
        val sent = mutableListOf<String>()
        val gc = coordinator(re, sendFrame = { id, _ -> sent.add(id.toHex()) })
        gc.broadcastKeepalive()
        assertEquals(0, sent.size)
    }

    @Test
    fun keepalive_withPeers_sendsToAll() = kotlinx.coroutines.test.runTest {
        val re = routingEngine()
        re.peerSeen(peerA.toKey())

        val sentPeers = mutableListOf<String>()
        val gc = coordinator(re, sendFrame = { id, _ -> sentPeers.add(id.toHex()) })
        gc.broadcastKeepalive()
        assertEquals(1, sentPeers.size)
        assertEquals(peerAHex, sentPeers[0])
    }

    @Test
    fun keepalive_encodesCorrectType() = kotlinx.coroutines.test.runTest {
        val re = routingEngine()
        re.peerSeen(peerA.toKey())

        val sentData = mutableListOf<ByteArray>()
        val gc = coordinator(re, clock = { 5000L }, sendFrame = { _, data -> sentData.add(data) })
        gc.broadcastKeepalive()
        assertEquals(1, sentData.size)
        // TYPE_KEEPALIVE is 0x08
        assertEquals(0x08, sentData[0][0].toInt())
    }

    // ── triggerUpdate ───────────────────────────────────────────

    @Test
    fun triggerUpdate_doesNotThrow() {
        val re = routingEngine()
        val gc = coordinator(re)
        gc.triggerUpdate()
    }
}
