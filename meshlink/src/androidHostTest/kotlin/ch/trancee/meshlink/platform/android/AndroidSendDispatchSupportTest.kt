package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportSendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class SendDispatchSupportTest {
    @Test
    fun dispatchAndroidSendDropsImmediatelyWhenTransportIsStopped(): Unit = runBlocking {
        // Arrange
        val fixture = SendDispatchFixture(transportStarted = false)
        val frame = OutboundFrame(peerId = PeerId("peer-android"), payload = byteArrayOf(1))

        // Act
        val result = fixture.run(frame = frame)

        // Assert
        assertEquals(
            "Android BLE transport is not started",
            (result as TransportSendResult.Dropped).reason,
        )
        assertEquals(0, fixture.resolvedCalls)
        assertEquals(0, fixture.temporaryCalls)
    }

    @Test
    fun dispatchAndroidSendPrefersResolvedPeerResults(): Unit = runBlocking {
        // Arrange
        val fixture =
            SendDispatchFixture(
                resolvedResult = TransportSendResult.Delivered,
                temporaryResult = TransportSendResult.Dropped("temporary"),
            )
        val frame = OutboundFrame(peerId = PeerId("peer-android"), payload = byteArrayOf(1))

        // Act
        val result = fixture.run(frame = frame)

        // Assert
        assertEquals(TransportSendResult.Delivered, result)
        assertEquals(1, fixture.resolvedCalls)
        assertEquals(0, fixture.temporaryCalls)
    }

    @Test
    fun dispatchAndroidSendFallsBackToTemporaryLinkWhenNoResolvedPeerExists(): Unit = runBlocking {
        // Arrange
        val fixture =
            SendDispatchFixture(
                resolvedResult = null,
                temporaryResult = TransportSendResult.Delivered,
            )
        val frame = OutboundFrame(peerId = PeerId("peer-android"), payload = byteArrayOf(1))

        // Act
        val result = fixture.run(frame = frame)

        // Assert
        assertEquals(TransportSendResult.Delivered, result)
        assertEquals(1, fixture.resolvedCalls)
        assertEquals(1, fixture.temporaryCalls)
    }

    @Test
    fun dispatchAndroidSendDropsWhenNeitherResolvedNorTemporaryPathsExist(): Unit = runBlocking {
        // Arrange
        val fixture = SendDispatchFixture(resolvedResult = null, temporaryResult = null)
        val frame = OutboundFrame(peerId = PeerId("peer-android"), payload = byteArrayOf(1))

        // Act
        val result = fixture.run(frame = frame)

        // Assert
        assertEquals(
            "Android BLE peer has not been discovered",
            (result as TransportSendResult.Dropped).reason,
        )
        assertEquals(1, fixture.resolvedCalls)
        assertEquals(1, fixture.temporaryCalls)
    }
}

private class SendDispatchFixture(
    private val transportStarted: Boolean = true,
    private val resolvedResult: TransportSendResult? = null,
    private val temporaryResult: TransportSendResult? = null,
) {
    var resolvedCalls: Int = 0
    var temporaryCalls: Int = 0

    suspend fun run(frame: OutboundFrame): TransportSendResult {
        return dispatchAndroidSend(
            frame = frame,
            context = SendDispatchContext(transportStarted = transportStarted),
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
                    log = {},
                ),
        )
    }
}
