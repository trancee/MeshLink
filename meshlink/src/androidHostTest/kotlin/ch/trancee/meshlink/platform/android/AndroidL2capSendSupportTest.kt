package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class L2capSendSupportTest {
    @Test
    fun sendViaAndroidL2capWhenReadyDropsGattOnlyPeers(): Unit = runBlocking {
        // Arrange
        val fixture = L2capSendFixture(transportMode = TransportMode.GATT)
        val frame = OutboundFrame(peerId = fixture.context.hintPeerId, payload = byteArrayOf(1))

        // Act
        val result = fixture.run(frame = frame, link = null)

        // Assert
        assertEquals(
            "Android BLE GATT fallback transport is not implemented",
            (result as TransportSendResult.Dropped).reason,
        )
        assertEquals(0, fixture.connectCalls)
    }

    @Test
    fun sendViaAndroidL2capWhenReadyWaitsForInboundLinksWhenNoPsmIsAdvertised(): Unit =
        runBlocking {
            // Arrange
            val fixture = L2capSendFixture(advertisedL2capPsm = 0)
            val frame = OutboundFrame(peerId = fixture.context.hintPeerId, payload = byteArrayOf(1))

            // Act
            val result = fixture.run(frame = frame, link = null)

            // Assert
            assertEquals(
                "Android BLE L2CAP connection is not ready",
                (result as TransportSendResult.Dropped).reason,
            )
            assertEquals(0, fixture.connectCalls)
        }

    @Test
    fun sendViaAndroidL2capWhenReadyTriggersConnectWhenTheLocalPeerShouldInitiate(): Unit =
        runBlocking {
            // Arrange
            val fixture = L2capSendFixture(shouldInitiateL2cap = true)
            val frame = OutboundFrame(peerId = fixture.context.hintPeerId, payload = byteArrayOf(1))

            // Act
            val result = fixture.run(frame = frame, link = null)

            // Assert
            assertEquals(
                "Android BLE L2CAP connection is not ready",
                (result as TransportSendResult.Dropped).reason,
            )
            assertEquals(1, fixture.connectCalls)
        }

    @Test
    fun sendViaAndroidL2capWhenReadyDoesNotTriggerConnectWhenWaitingForInboundLink(): Unit =
        runBlocking {
            // Arrange
            val fixture = L2capSendFixture(shouldInitiateL2cap = false)
            val frame = OutboundFrame(peerId = fixture.context.hintPeerId, payload = byteArrayOf(1))

            // Act
            val result = fixture.run(frame = frame, link = null)

            // Assert
            assertEquals(
                "Android BLE L2CAP connection is not ready",
                (result as TransportSendResult.Dropped).reason,
            )
            assertEquals(0, fixture.connectCalls)
        }

    @Test
    fun sendViaAndroidL2capWhenReadyDelegatesToTheActiveLink(): Unit = runBlocking {
        // Arrange
        val fixture = L2capSendFixture()
        val frame = OutboundFrame(peerId = fixture.context.hintPeerId, payload = byteArrayOf(1))
        val link = FakeAndroidL2capSendLink(TransportSendResult.Delivered)

        // Act
        val result = fixture.run(frame = frame, link = link)

        // Assert
        assertEquals(TransportSendResult.Delivered, result)
        assertEquals(1, link.sendCalls)
    }
}

private class L2capSendFixture(
    transportMode: TransportMode = TransportMode.L2CAP,
    advertisedL2capPsm: Int = 192,
    private val shouldInitiateL2cap: Boolean = true,
) {
    val context =
        L2capSendContext(
            hintPeerId = PeerId("peer-android"),
            transportMode = transportMode,
            advertisedL2capPsm = advertisedL2capPsm,
        )
    var connectCalls: Int = 0

    suspend fun run(frame: OutboundFrame, link: FakeAndroidL2capSendLink?): TransportSendResult {
        return sendViaAndroidL2capWhenReady(
            frame = frame,
            context = context,
            dependencies =
                L2capSendDependencies(
                    currentLink = { link },
                    shouldInitiateL2cap = { shouldInitiateL2cap },
                    triggerConnectIfNeeded = { connectCalls += 1 },
                    log = {},
                ),
        )
    }
}

private class FakeAndroidL2capSendLink(private val result: TransportSendResult) : L2capSendLink {
    var sendCalls: Int = 0

    override suspend fun send(frame: OutboundFrame): TransportSendResult {
        sendCalls += 1
        return result
    }
}
