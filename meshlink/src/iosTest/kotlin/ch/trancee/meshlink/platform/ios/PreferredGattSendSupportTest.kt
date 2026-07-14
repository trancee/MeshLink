package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.engine.transport.DirectWireFrame
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class PreferredGattSendSupportTest {
    @Test
    fun sendViaPreferredGattNotifyLinkOrNullHandlesHandshakeFrames(): Unit = runBlocking {
        // Arrange
        val fixture = PreferredGattSendFixture()
        val frame =
            OutboundFrame(
                peerId = fixture.context.hintPeerId,
                payload = DirectWireFrame.HandshakeMessage1(byteArrayOf(0x01)).encode(),
            )

        // Act
        val result =
            fixture.run(frame = frame, link = FakePreferredGattSendLink(enqueueResult = true))

        // Assert
        assertEquals(TransportSendResult.Delivered, result)
        assertEquals(1, fixture.currentLinkCalls)
    }

    @Test
    fun sendViaPreferredGattNotifyLinkOrNullUsesGattFallbackForSamePlatformDataFrames(): Unit =
        runBlocking {
            // Arrange
            val fixture =
                PreferredGattSendFixture(remotePlatformFamily = BleDiscoveryPlatformFamily.IOS)
            val frame =
                OutboundFrame(
                    peerId = fixture.context.hintPeerId,
                    payload = DirectWireFrame.Data(ByteArray(8)).encode(),
                )

            // Act
            val result =
                fixture.run(frame = frame, link = FakePreferredGattSendLink(enqueueResult = true))

            // Assert
            assertEquals(TransportSendResult.Delivered, result)
            assertEquals(1, fixture.currentLinkCalls)
        }

    @Test
    fun sendViaPreferredGattNotifyLinkOrNullAllowsSamePlatformDataFramesWhenGattIsPreferred():
        Unit = runBlocking {
        // Arrange
        val fixture =
            PreferredGattSendFixture(remotePlatformFamily = BleDiscoveryPlatformFamily.IOS)
        val frame =
            OutboundFrame(
                peerId = fixture.context.hintPeerId,
                payload = DirectWireFrame.Data(ByteArray(8)).encode(),
                preferredMode = TransportMode.GATT,
            )

        // Act
        val result =
            fixture.run(frame = frame, link = FakePreferredGattSendLink(enqueueResult = true))

        // Assert
        assertEquals(TransportSendResult.Delivered, result)
        assertEquals(1, fixture.currentLinkCalls)
    }

    @Test
    fun sendViaPreferredGattNotifyLinkOrNullFallsBackWhenTheSideLinkIsUnavailable(): Unit =
        runBlocking {
            // Arrange
            val fixture = PreferredGattSendFixture()
            val frame =
                OutboundFrame(
                    peerId = fixture.context.hintPeerId,
                    payload = DirectWireFrame.Data(ByteArray(8)).encode(),
                )

            // Act
            val result = fixture.run(frame = frame, link = null)

            // Assert
            assertNull(result)
            assertEquals(1, fixture.currentLinkCalls)
        }

    @Test
    fun sendViaPreferredGattNotifyLinkOrNullFallsBackWhenEnqueueReturnsFalse(): Unit = runBlocking {
        // Arrange
        val fixture = PreferredGattSendFixture()
        val frame =
            OutboundFrame(
                peerId = fixture.context.hintPeerId,
                payload = DirectWireFrame.Data(ByteArray(8)).encode(),
            )

        // Act
        val result =
            fixture.run(frame = frame, link = FakePreferredGattSendLink(enqueueResult = false))

        // Assert
        assertNull(result)
        assertEquals(1, fixture.currentLinkCalls)
    }

    @Test
    fun sendViaPreferredGattNotifyLinkOrNullFallsBackWhenEnqueueThrows(): Unit = runBlocking {
        // Arrange
        val fixture = PreferredGattSendFixture()
        val frame =
            OutboundFrame(
                peerId = fixture.context.hintPeerId,
                payload = DirectWireFrame.Data(ByteArray(8)).encode(),
            )

        // Act
        val result =
            fixture.run(
                frame = frame,
                link = FakePreferredGattSendLink(enqueueFailure = IllegalStateException("boom")),
            )

        // Assert
        assertNull(result)
        assertEquals(1, fixture.currentLinkCalls)
    }

    @Test
    fun sendViaPreferredGattNotifyLinkOrNullDoesNotPollForReadinessBeforeEnqueueing(): Unit =
        runBlocking {
            // Arrange -- unlike Android's PreferredGattSendSupport (which polls a readiness flag
            // in a loop for up to 10s before writing, since its GATT side-link handshake has no
            // completion callback), GattNotifyLink.enqueue already owns an internal pump queue
            // that holds frames and drains them once the remote central subscribes. This test
            // documents and protects that intentional platform difference: the fake link's
            // enqueue() is invoked exactly once, with no readiness check queried beforehand --
            // there is no `isReady()`-shaped dependency slot in PreferredGattSendDependencies at
            // all on iOS (unlike Android's PreferredGattSendClient), so a link becoming available
            // is sufficient by itself for the send to proceed immediately.
            val fixture = PreferredGattSendFixture()
            val frame =
                OutboundFrame(
                    peerId = fixture.context.hintPeerId,
                    payload = DirectWireFrame.Data(ByteArray(8)).encode(),
                )
            val link = FakePreferredGattSendLink(enqueueResult = true)

            // Act
            val result = fixture.run(frame = frame, link = link)

            // Assert
            assertEquals(TransportSendResult.Delivered, result)
            assertEquals(1, link.enqueueCalls)
        }
}

private class PreferredGattSendFixture(
    remotePlatformFamily: BleDiscoveryPlatformFamily = BleDiscoveryPlatformFamily.ANDROID
) {
    val context =
        PreferredGattSendContext(
            hintPeerId = PeerId("peer-ios"),
            localPlatformFamily = BleDiscoveryPlatformFamily.IOS,
            remotePlatformFamily = remotePlatformFamily,
        )
    var currentLinkCalls: Int = 0

    suspend fun run(frame: OutboundFrame, link: FakePreferredGattSendLink?): TransportSendResult? {
        return sendViaPreferredGattNotifyLinkOrNull(
            frame = frame,
            context = context,
            dependencies =
                PreferredGattSendDependencies(
                    currentLink = {
                        currentLinkCalls += 1
                        link
                    },
                    log = {},
                ),
        )
    }
}

private class FakePreferredGattSendLink(
    private val enqueueResult: Boolean = true,
    private val enqueueFailure: Throwable? = null,
) : PreferredGattSendLink {
    var enqueueCalls: Int = 0

    override suspend fun enqueue(payload: ByteArray): Boolean {
        enqueueCalls += 1
        enqueueFailure?.let { throw it }
        return enqueueResult
    }
}
