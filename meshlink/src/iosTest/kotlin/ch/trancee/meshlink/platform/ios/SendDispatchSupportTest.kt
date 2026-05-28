package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportSendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class SendDispatchSupportTest {
    @Test
    fun dispatchSendReturnsTheResolvedPeerResultWhenAvailable(): Unit = runBlocking {
        // Arrange
        val fixture = SendDispatchFixture(resolvedResult = TransportSendResult.Delivered)
        val frame = OutboundFrame(peerId = PeerId("peer-ios"), payload = byteArrayOf(1))

        // Act
        val result = fixture.run(frame = frame)

        // Assert
        assertEquals(TransportSendResult.Delivered, result)
        assertEquals(1, fixture.resolvedCalls)
        assertEquals(0, fixture.dropCalls)
    }

    @Test
    fun dispatchSendDropsWhenThePeerIsMissing(): Unit = runBlocking {
        // Arrange
        val fixture = SendDispatchFixture(resolvedResult = null)
        val frame = OutboundFrame(peerId = PeerId("peer-ios"), payload = byteArrayOf(1))

        // Act
        val result = fixture.run(frame = frame)

        // Assert
        assertEquals(
            "iOS BLE peer has not been discovered",
            (result as TransportSendResult.Dropped).reason,
        )
        assertEquals(1, fixture.resolvedCalls)
        assertEquals(1, fixture.dropCalls)
    }
}

private class SendDispatchFixture(private val resolvedResult: TransportSendResult?) {
    var resolvedCalls: Int = 0
    var dropCalls: Int = 0

    suspend fun run(frame: OutboundFrame): TransportSendResult {
        return dispatchSend(
            frame = frame,
            dependencies =
                SendDispatchDependencies(
                    sendToResolvedPeerOrNull = {
                        resolvedCalls += 1
                        resolvedResult
                    },
                    dropWhenPeerIsMissing = {
                        dropCalls += 1
                        TransportSendResult.Dropped("iOS BLE peer has not been discovered")
                    },
                ),
        )
    }
}
