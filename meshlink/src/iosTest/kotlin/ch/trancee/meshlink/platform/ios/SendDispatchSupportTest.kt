package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportSendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class SendDispatchSupportTest {
    @Test
    fun dispatchSendPrefersResolvedPeerResults(): Unit = runBlocking {
        // Arrange
        val fixture =
            SendDispatchFixture(
                resolvedResult = TransportSendResult.Delivered,
                temporaryResult = TransportSendResult.Dropped("temporary"),
            )
        val frame = OutboundFrame(peerId = PeerId("peer-ios"), payload = byteArrayOf(1))

        // Act
        val result = fixture.run(frame = frame)

        // Assert
        assertEquals(TransportSendResult.Delivered, result)
        assertEquals(1, fixture.resolvedCalls)
        assertEquals(0, fixture.temporaryCalls)
    }

    @Test
    fun dispatchSendFallsBackToTemporaryLinkWhenNoResolvedPeerExists(): Unit = runBlocking {
        // Arrange -- mirrors Android's identical fallback (see
        // platform.android.SendDispatchSupportTest.dispatchSendFallsBackToTemporaryLinkWhenNoResolvedPeerExists):
        // no DiscoveredPeer record exists yet for frame.peerId, but a link is already active under
        // the raw hint id (see SendDispatchDependencies.sendToTemporaryLinkOrNull).
        val fixture =
            SendDispatchFixture(
                resolvedResult = null,
                temporaryResult = TransportSendResult.Delivered,
            )
        val frame = OutboundFrame(peerId = PeerId("peer-ios"), payload = byteArrayOf(1))

        // Act
        val result = fixture.run(frame = frame)

        // Assert
        assertEquals(TransportSendResult.Delivered, result)
        assertEquals(1, fixture.resolvedCalls)
        assertEquals(1, fixture.temporaryCalls)
    }

    @Test
    fun dispatchSendDropsWhenNeitherResolvedNorTemporaryPathsExist(): Unit = runBlocking {
        // Arrange
        val fixture = SendDispatchFixture(resolvedResult = null, temporaryResult = null)
        val frame = OutboundFrame(peerId = PeerId("peer-ios"), payload = byteArrayOf(1))

        // Act
        val result = fixture.run(frame = frame)

        // Assert
        assertEquals(
            "iOS BLE peer has not been discovered",
            (result as TransportSendResult.Dropped).reason,
        )
        assertEquals(1, fixture.resolvedCalls)
        assertEquals(1, fixture.temporaryCalls)
    }
}

private class SendDispatchFixture(
    private val resolvedResult: TransportSendResult? = null,
    private val temporaryResult: TransportSendResult? = null,
) {
    var resolvedCalls: Int = 0
    var temporaryCalls: Int = 0

    suspend fun run(frame: OutboundFrame): TransportSendResult {
        return dispatchSend(
            frame = frame,
            dependencies =
                SendDispatchDependencies(
                    sendToResolvedPeerOrNull = {
                        resolvedCalls += 1
                        resolvedResult
                    },
                    sendToTemporaryLinkOrNull = {
                        temporaryCalls += 1
                        temporaryResult
                    },
                    dropWhenPeerIsMissing = {
                        TransportSendResult.Dropped("iOS BLE peer has not been discovered")
                    },
                ),
        )
    }
}
