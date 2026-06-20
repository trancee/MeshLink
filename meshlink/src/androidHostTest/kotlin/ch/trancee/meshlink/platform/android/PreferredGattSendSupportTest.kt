package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.engine.DirectWireFrame
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
    fun sendViaPreferredGattSideLinkOrNullHandlesHandshakeFrames(): Unit = runBlocking {
        // Arrange
        val fixture = PreferredGattSendFixture()
        val frame =
            OutboundFrame(
                peerId = fixture.context.hintPeerId,
                payload = DirectWireFrame.HandshakeMessage1(byteArrayOf(0x01)).encode(),
            )

        // Act
        val result =
            fixture.run(
                frame = frame,
                client = FakePreferredGattSendClient(ready = true, writeResult = true),
            )

        // Assert
        assertEquals(TransportSendResult.Delivered, result)
        assertEquals(1, fixture.ensureSideLinkCalls)
        assertEquals(0, fixture.restartReasons.size)
    }

    @Test
    fun sendViaPreferredGattSideLinkOrNullSkipsSamePlatformDataFramesWithoutGattPreference(): Unit =
        runBlocking {
            // Arrange
            val fixture =
                PreferredGattSendFixture(remotePlatformFamily = BleDiscoveryPlatformFamily.ANDROID)
            val frame =
                OutboundFrame(
                    peerId = fixture.context.hintPeerId,
                    payload = DirectWireFrame.Data(ByteArray(8)).encode(),
                )

            // Act
            val result =
                fixture.run(
                    frame = frame,
                    client = FakePreferredGattSendClient(ready = true, writeResult = true),
                )

            // Assert
            assertNull(result)
            assertEquals(0, fixture.ensureSideLinkCalls)
        }

    @Test
    fun sendViaPreferredGattSideLinkOrNullAllowsSamePlatformDataFramesWhenGattIsPreferred(): Unit =
        runBlocking {
            // Arrange
            val fixture =
                PreferredGattSendFixture(remotePlatformFamily = BleDiscoveryPlatformFamily.ANDROID)
            val frame =
                OutboundFrame(
                    peerId = fixture.context.hintPeerId,
                    payload = DirectWireFrame.Data(ByteArray(8)).encode(),
                    preferredMode = TransportMode.GATT,
                )

            // Act
            val result =
                fixture.run(
                    frame = frame,
                    client = FakePreferredGattSendClient(ready = true, writeResult = true),
                )

            // Assert
            assertEquals(TransportSendResult.Delivered, result)
            assertEquals(1, fixture.ensureSideLinkCalls)
            assertEquals(0, fixture.restartReasons.size)
        }

    @Test
    fun sendViaPreferredGattSideLinkOrNullFallsBackWhenTheSideLinkIsUnavailable(): Unit =
        runBlocking {
            // Arrange
            val fixture = PreferredGattSendFixture()
            val frame =
                OutboundFrame(
                    peerId = fixture.context.hintPeerId,
                    payload = DirectWireFrame.Data(ByteArray(8)).encode(),
                )

            // Act
            val result = fixture.run(frame = frame, client = null)

            // Assert
            assertNull(result)
            assertEquals(1, fixture.ensureSideLinkCalls)
            assertEquals(0, fixture.restartReasons.size)
        }

    @Test
    fun sendViaPreferredGattSideLinkOrNullFallsBackWhenTheSideLinkIsNotReady(): Unit = runBlocking {
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
                client = FakePreferredGattSendClient(ready = false, writeResult = true),
            )

        // Assert
        assertNull(result)
        assertEquals(1, fixture.ensureSideLinkCalls)
        assertEquals(0, fixture.restartReasons.size)
    }

    @Test
    fun sendViaPreferredGattSideLinkOrNullRestartsTheSideLinkWhenWriteReturnsFalse(): Unit =
        runBlocking {
            // Arrange
            val fixture = PreferredGattSendFixture()
            val frame =
                OutboundFrame(
                    peerId = fixture.context.hintPeerId,
                    payload = DirectWireFrame.Data(ByteArray(16)).encode(),
                )

            // Act
            val result =
                fixture.run(
                    frame = frame,
                    client = FakePreferredGattSendClient(ready = true, writeResult = false),
                )

            // Assert
            assertNull(result)
            assertEquals(1, fixture.ensureSideLinkCalls)
            assertEquals(
                listOf("write failed for ${frame.payload.size} bytes"),
                fixture.restartReasons,
            )
        }

    @Test
    fun sendViaPreferredGattSideLinkOrNullRestartsTheSideLinkWhenWriteThrows(): Unit = runBlocking {
        // Arrange
        val fixture = PreferredGattSendFixture()
        val frame =
            OutboundFrame(
                peerId = fixture.context.hintPeerId,
                payload = DirectWireFrame.Data(ByteArray(16)).encode(),
            )

        // Act
        val result =
            fixture.run(
                frame = frame,
                client =
                    FakePreferredGattSendClient(
                        ready = true,
                        writeResult = true,
                        writeFailure = IllegalStateException("boom"),
                    ),
            )

        // Assert
        assertNull(result)
        assertEquals(1, fixture.ensureSideLinkCalls)
        assertEquals(listOf("write failed for ${frame.payload.size} bytes"), fixture.restartReasons)
    }
}

private class PreferredGattSendFixture(
    remotePlatformFamily: BleDiscoveryPlatformFamily = BleDiscoveryPlatformFamily.IOS
) {
    val context =
        PreferredGattSendContext(
            hintPeerId = PeerId("peer-android"),
            localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
            remotePlatformFamily = remotePlatformFamily,
        )
    var ensureSideLinkCalls: Int = 0
    val restartReasons: MutableList<String> = mutableListOf()

    suspend fun run(
        frame: OutboundFrame,
        client: FakePreferredGattSendClient?,
    ): TransportSendResult? {
        return sendViaPreferredGattSideLinkOrNull(
            frame = frame,
            context = context,
            dependencies =
                PreferredGattSendDependencies(
                    ensureSideLink = { ensureSideLinkCalls += 1 },
                    currentClient = { client },
                    restartSideLink = { reason -> restartReasons += reason },
                    log = {},
                ),
        )
    }
}

private class FakePreferredGattSendClient(
    private val ready: Boolean,
    private val writeResult: Boolean,
    private val writeFailure: Throwable? = null,
) : PreferredGattSendClient {
    override fun isReady(): Boolean {
        return ready
    }

    override suspend fun write(payload: ByteArray): Boolean {
        writeFailure?.let { throw it }
        return writeResult
    }
}
