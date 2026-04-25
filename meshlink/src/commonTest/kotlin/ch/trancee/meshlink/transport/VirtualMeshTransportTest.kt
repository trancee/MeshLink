package ch.trancee.meshlink.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * Validates [VirtualMeshTransport] topology, event injection, send/receive, error injection, and
 * step-mode behavior.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VirtualMeshTransportTest {

    // ── Topology tests ─────────────────────────────────────────────────────────

    @Test
    fun linkToCreatesBidirectionalLink() = runTest {
        val transportA = VirtualMeshTransport(byteArrayOf(0x01), testScheduler)
        val transportB = VirtualMeshTransport(byteArrayOf(0x02), testScheduler)
        transportA.linkTo(transportB)

        // A can send to B
        val resultAB = transportA.sendToPeer(byteArrayOf(0x02), byteArrayOf(0xAA.toByte()))
        assertIs<SendResult.Success>(resultAB)

        // B can send to A (bidirectional)
        val resultBA = transportB.sendToPeer(byteArrayOf(0x01), byteArrayOf(0xBB.toByte()))
        assertIs<SendResult.Success>(resultBA)
    }

    @Test
    fun sendToUnlinkedPeerReturnsFailure() = runTest {
        val transportA = VirtualMeshTransport(byteArrayOf(0x01), testScheduler)
        val result = transportA.sendToPeer(byteArrayOf(0x99.toByte()), byteArrayOf(0xAA.toByte()))
        assertIs<SendResult.Failure>(result)
    }

    // ── Event injection tests ─────────────────────────────────────────────────

    @Test
    fun simulateDiscoveryEmitsAdvertisementEvent() = runTest {
        val transport = VirtualMeshTransport(byteArrayOf(0x01), testScheduler)
        val peerId = byteArrayOf(0x02)
        val serviceData = byteArrayOf(0x03, 0x04)
        val rssi = -70

        val events = mutableListOf<AdvertisementEvent>()
        val job = launch { transport.advertisementEvents.collect { events.add(it) } }
        testScheduler.runCurrent() // Let collector register and reach suspension point

        transport.simulateDiscovery(peerId, serviceData, rssi)
        testScheduler.runCurrent() // Let collector process the event

        assertEquals(1, events.size)
        assertEquals(AdvertisementEvent(peerId, serviceData, rssi), events[0])
        job.cancel()
    }

    @Test
    fun simulatePeerLostEmitsPeerLostEvent() = runTest {
        val transport = VirtualMeshTransport(byteArrayOf(0x01), testScheduler)
        val peerId = byteArrayOf(0x02)
        val reason = PeerLostReason.TIMEOUT

        val events = mutableListOf<PeerLostEvent>()
        val job = launch { transport.peerLostEvents.collect { events.add(it) } }
        testScheduler.runCurrent() // Let collector register and reach suspension point

        transport.simulatePeerLost(peerId, reason)
        testScheduler.runCurrent() // Let collector process the event

        assertEquals(1, events.size)
        assertEquals(PeerLostEvent(peerId, reason), events[0])
        job.cancel()
    }

    // ── Send/receive tests ─────────────────────────────────────────────────────

    @Test
    fun sendToPeerDeliversDataToTargetIncomingData() = runTest {
        val transportA = VirtualMeshTransport(byteArrayOf(0x01), testScheduler)
        val transportB = VirtualMeshTransport(byteArrayOf(0x02), testScheduler)
        transportA.linkTo(transportB)

        val received = mutableListOf<IncomingData>()
        val job = launch { transportB.incomingData.collect { received.add(it) } }
        testScheduler.runCurrent() // Let collector register and reach suspension point

        val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        transportA.sendToPeer(byteArrayOf(0x02), payload)
        testScheduler.runCurrent() // Let collector process the event

        assertEquals(1, received.size)
        assertContentEquals(byteArrayOf(0x01), received[0].peerId)
        assertContentEquals(payload, received[0].data)
        job.cancel()
    }

    @Test
    fun sentFramesCapturesL2CAPEncodedFrame() = runTest {
        val transportA = VirtualMeshTransport(byteArrayOf(0x01), testScheduler)
        val transportB = VirtualMeshTransport(byteArrayOf(0x02), testScheduler)
        transportA.linkTo(transportB)

        val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        transportA.sendToPeer(byteArrayOf(0x02), payload)

        assertEquals(1, transportA.sentFrames.size)
        val sentFrame = transportA.sentFrames[0]
        assertContentEquals(byteArrayOf(0x02), sentFrame.destination)

        // Verify L2CAP header: type=DATA, little-endian length, then payload
        val data = sentFrame.data
        assertEquals(FrameType.DATA.code, data[0])
        assertEquals(payload.size.toByte(), data[1]) // low byte
        assertEquals(0.toByte(), data[2]) // high byte
        assertContentEquals(payload, data.copyOfRange(3, data.size))

        // Cross-verify with codec
        val expectedFrame = L2capFrameCodec.encode(FrameType.DATA, payload)
        assertContentEquals(expectedFrame, sentFrame.data)
    }

    // ── Error injection tests ─────────────────────────────────────────────────

    @Test
    fun simulateWriteFailureCausesSendToReturnFailureThenClearRestoresSuccess() = runTest {
        val transportA = VirtualMeshTransport(byteArrayOf(0x01), testScheduler)
        val transportB = VirtualMeshTransport(byteArrayOf(0x02), testScheduler)
        transportA.linkTo(transportB)

        // Inject failure → send returns Failure
        transportA.simulateWriteFailure(byteArrayOf(0x02))
        val failResult = transportA.sendToPeer(byteArrayOf(0x02), byteArrayOf(0xAA.toByte()))
        assertIs<SendResult.Failure>(failResult)

        // Clear failure → send returns Success
        transportA.clearWriteFailure(byteArrayOf(0x02))
        val successResult = transportA.sendToPeer(byteArrayOf(0x02), byteArrayOf(0xAA.toByte()))
        assertIs<SendResult.Success>(successResult)
    }

    // ── Step mode tests ────────────────────────────────────────────────────────

    @Test
    fun advanceToAdvancesSchedulerTime() = runTest {
        val transport = VirtualMeshTransport(byteArrayOf(0x01), testScheduler)
        assertEquals(0L, testScheduler.currentTime)
        transport.advanceTo(1000L)
        assertEquals(1000L, testScheduler.currentTime)
    }

    @Test
    fun advanceToRejectsPastTime() = runTest {
        val transport = VirtualMeshTransport(byteArrayOf(0x01), testScheduler)
        transport.advanceTo(1000L)
        assertFailsWith<IllegalArgumentException> { transport.advanceTo(500L) }
    }

    // ── SentFrame equals/hashCode ──────────────────────────────────────────────

    @Test
    fun sentFrameEqualsAndHashCode() {
        val dest = byteArrayOf(0x01)
        val data = byteArrayOf(0x02, 0x03)
        val frame = SentFrame(dest, data)

        // Identity check
        assertTrue(frame.equals(frame))

        // All fields equal (different references)
        val frame2 = SentFrame(byteArrayOf(0x01), byteArrayOf(0x02, 0x03))
        assertTrue(frame.equals(frame2))
        assertEquals(frame.hashCode(), frame2.hashCode())

        // Wrong type
        assertFalse(frame.equals("x"))

        // destination differs → false (short-circuits data)
        assertFalse(frame.equals(SentFrame(byteArrayOf(0xFF.toByte()), data)))

        // data differs → false (destination matches)
        assertFalse(frame.equals(SentFrame(dest, byteArrayOf(0xFF.toByte()))))
    }
}
