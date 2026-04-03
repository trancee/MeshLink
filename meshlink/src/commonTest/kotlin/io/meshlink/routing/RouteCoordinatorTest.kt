package io.meshlink.routing

import io.meshlink.diagnostics.DiagnosticSink
import io.meshlink.util.toHex
import io.meshlink.util.toKey
import io.meshlink.wire.WireCodec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteCoordinatorTest {

    private val localPeerId = ByteArray(12) { it.toByte() }
    private val peerA = ByteArray(12) { (it + 0x10).toByte() }
    private val peerAHex = peerA.toHex()

    private fun routingEngine(
        clock: () -> Long = { 0L },
    ) = RoutingEngine(
        localPeerId = localPeerId.toKey(),
        dedupCapacity = 100,
        clock = clock,
    )

    private fun coordinator(
        routingEngine: RoutingEngine,
        keepaliveIntervalMillis: Long = 10_000L,
        sendFrame: suspend (ByteArray, ByteArray) -> Unit = { _, _ -> },
        clock: () -> Long = { 0L },
    ) = RouteCoordinator(
        routingEngine = routingEngine,
        diagnosticSink = DiagnosticSink(),
        keepaliveIntervalMillis = keepaliveIntervalMillis,
        sendFrame = sendFrame,
        clock = clock,
    )

    // ── broadcastKeepalive ──────────────────────────────────────

    @Test
    fun keepalive_noPeers_sendsNothing() = runTest {
        val re = routingEngine()
        val sent = mutableListOf<String>()
        val rc = coordinator(re, sendFrame = { id, _ -> sent.add(id.toHex()) })
        rc.broadcastKeepalive()
        assertEquals(0, sent.size)
    }

    @Test
    fun keepalive_withPeers_sendsToAll() = runTest {
        val re = routingEngine()
        re.peerSeen(peerA.toKey())

        val sentPeers = mutableListOf<String>()
        val rc = coordinator(re, sendFrame = { id, _ -> sentPeers.add(id.toHex()) })
        rc.broadcastKeepalive()
        assertEquals(1, sentPeers.size)
        assertEquals(peerAHex, sentPeers[0])
    }

    @Test
    fun keepalive_encodesCorrectType() = runTest {
        val re = routingEngine()
        re.peerSeen(peerA.toKey())

        val sentData = mutableListOf<ByteArray>()
        val rc = coordinator(re, clock = { 5000L }, sendFrame = { _, data -> sentData.add(data) })
        rc.broadcastKeepalive()
        assertEquals(1, sentData.size)
        assertEquals(0x01, sentData[0][0].toInt(), "TYPE_KEEPALIVE is 0x01")
    }

    @Test
    fun keepalive_multiplePeers_sendsToEach() = runTest {
        val re = routingEngine()
        re.peerSeen(peerA.toKey())
        val peerB = ByteArray(12) { (it + 0x20).toByte() }
        re.peerSeen(peerB.toKey())

        val sentPeers = mutableListOf<String>()
        val rc = coordinator(re, sendFrame = { id, _ -> sentPeers.add(id.toHex()) })
        rc.broadcastKeepalive()
        assertEquals(2, sentPeers.size)
        assertTrue(sentPeers.contains(peerAHex))
        assertTrue(sentPeers.contains(peerB.toHex()))
    }

    // ── floodRouteRequest ───────────────────────────────────────

    @Test
    fun floodRouteRequest_noPeers_sendsNothing() = runTest {
        val re = routingEngine()
        val sent = mutableListOf<String>()
        val rc = coordinator(re, sendFrame = { id, _ -> sent.add(id.toHex()) })
        val rreqFrame = WireCodec.encodeRouteRequest(
            origin = localPeerId,
            destination = peerA,
            requestId = 1u,
        )
        rc.floodRouteRequest(rreqFrame)
        assertEquals(0, sent.size)
    }

    @Test
    fun floodRouteRequest_withPeers_sendsToAll() = runTest {
        val re = routingEngine()
        re.peerSeen(peerA.toKey())
        val peerB = ByteArray(12) { (it + 0x20).toByte() }
        re.peerSeen(peerB.toKey())

        val sentPeers = mutableListOf<String>()
        val sentData = mutableListOf<ByteArray>()
        val rc = coordinator(re, sendFrame = { id, data ->
            sentPeers.add(id.toHex())
            sentData.add(data)
        })

        val rreqFrame = WireCodec.encodeRouteRequest(
            origin = localPeerId,
            destination = ByteArray(12) { 0xFF.toByte() },
            requestId = 1u,
        )
        rc.floodRouteRequest(rreqFrame)

        assertEquals(2, sentPeers.size)
        assertTrue(sentPeers.contains(peerAHex))
        assertTrue(sentPeers.contains(peerB.toHex()))
        // All peers receive the same RREQ frame
        for (data in sentData) {
            assertEquals(WireCodec.TYPE_ROUTE_REQUEST, data[0], "Frame should be ROUTE_REQUEST")
        }
    }

    // ── runKeepaliveLoop ────────────────────────────────────────

    @Test
    fun runKeepaliveLoop_stopsWhenInactive() = runTest {
        val re = routingEngine()
        re.peerSeen(peerA.toKey())
        var callCount = 0
        val rc = coordinator(
            re,
            keepaliveIntervalMillis = 100L,
            sendFrame = { _, _ -> callCount++ },
            clock = { 0L },
        )
        rc.runKeepaliveLoop { false }
        assertEquals(0, callCount, "Loop should exit immediately when isActive returns false")
    }
}
