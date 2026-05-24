package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportSendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class IosL2capSendSupportTest {
    @Test
    fun sendViaIosL2capWhenReadyRequestsConnectWhenNoLinkExists(): Unit = runBlocking {
        // Arrange
        val fixture = IosL2capSendFixture(shouldInitiateL2cap = true)
        val frame = OutboundFrame(peerId = fixture.context.hintPeerId, payload = byteArrayOf(1))

        // Act
        val result = fixture.run(frame = frame, link = null)

        // Assert
        assertEquals(
            "iOS BLE L2CAP connection is not ready",
            (result as TransportSendResult.Dropped).reason,
        )
        assertEquals(1, fixture.connectCalls)
        assertEquals(emptyList(), fixture.closedLinks)
    }

    @Test
    fun sendViaIosL2capWhenReadyDoesNotRequestConnectWhenWaitingForInboundLink(): Unit =
        runBlocking {
            // Arrange
            val fixture = IosL2capSendFixture(shouldInitiateL2cap = false)
            val frame = OutboundFrame(peerId = fixture.context.hintPeerId, payload = byteArrayOf(1))

            // Act
            val result = fixture.run(frame = frame, link = null)

            // Assert
            assertEquals(
                "iOS BLE L2CAP connection is not ready",
                (result as TransportSendResult.Dropped).reason,
            )
            assertEquals(0, fixture.connectCalls)
            assertEquals(emptyList(), fixture.closedLinks)
        }

    @Test
    fun sendViaIosL2capWhenReadyReturnsDeliveredWhenTheLinkAcceptsTheFrame(): Unit = runBlocking {
        // Arrange
        val fixture = IosL2capSendFixture()
        val frame = OutboundFrame(peerId = fixture.context.hintPeerId, payload = byteArrayOf(1, 2))
        val link = FakeIosL2capSendLink(hintPeerId = PeerId("link-peer"), enqueueResult = true)

        // Act
        val result = fixture.run(frame = frame, link = link)

        // Assert
        assertEquals(TransportSendResult.Delivered, result)
        assertEquals(1, link.enqueueCalls)
        assertEquals(emptyList(), fixture.closedLinks)
    }

    @Test
    fun sendViaIosL2capWhenReadyClosesTheLinkWhenTheQueueRejectsTheFrame(): Unit = runBlocking {
        // Arrange
        val fixture = IosL2capSendFixture()
        val frame = OutboundFrame(peerId = fixture.context.hintPeerId, payload = byteArrayOf(1, 2))
        val link = FakeIosL2capSendLink(hintPeerId = PeerId("link-peer"), enqueueResult = false)

        // Act
        val result = fixture.run(frame = frame, link = link)

        // Assert
        assertEquals(
            "iOS BLE send queue is not accepting frames",
            (result as TransportSendResult.Dropped).reason,
        )
        assertEquals(listOf("link-peer|send queue closed"), fixture.closedLinks)
    }

    @Test
    fun sendViaIosL2capWhenReadyClosesTheLinkWhenEnqueueThrows(): Unit = runBlocking {
        // Arrange
        val fixture = IosL2capSendFixture()
        val frame = OutboundFrame(peerId = fixture.context.hintPeerId, payload = byteArrayOf(1, 2))
        val link =
            FakeIosL2capSendLink(
                hintPeerId = PeerId("link-peer"),
                enqueueFailure = IllegalStateException("boom"),
            )

        // Act
        val result = fixture.run(frame = frame, link = link)

        // Assert
        assertEquals("iOS BLE send failed: boom", (result as TransportSendResult.Dropped).reason)
        assertEquals(listOf("link-peer|send failed: boom"), fixture.closedLinks)
    }
}

private class IosL2capSendFixture(private val shouldInitiateL2cap: Boolean = true) {
    val context = IosL2capSendContext(hintPeerId = PeerId("peer-ios"))
    var connectCalls: Int = 0
    val closedLinks: MutableList<String> = mutableListOf()

    suspend fun run(frame: OutboundFrame, link: FakeIosL2capSendLink?): TransportSendResult {
        return sendViaIosL2capWhenReady(
            frame = frame,
            context = context,
            dependencies =
                IosL2capSendDependencies(
                    currentLink = { link },
                    ensureConnectAttempt = { connectCalls += 1 },
                    shouldInitiateL2cap = { shouldInitiateL2cap },
                    closeLink = { hintPeer, reason -> closedLinks += "$hintPeer|$reason" },
                    log = {},
                ),
        )
    }
}

private class FakeIosL2capSendLink(
    override val hintPeerId: PeerId,
    private val enqueueResult: Boolean = true,
    private val enqueueFailure: Throwable? = null,
) : IosL2capSendLink {
    var enqueueCalls: Int = 0

    override suspend fun enqueue(payload: ByteArray): Boolean {
        enqueueCalls += 1
        enqueueFailure?.let { throw it }
        return enqueueResult
    }
}
