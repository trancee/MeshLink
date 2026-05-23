package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.MeshLinkException
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.config.PowerMode
import ch.trancee.meshlink.config.RegulatoryRegion
import ch.trancee.meshlink.platform.PlatformPermissionDeniedException
import ch.trancee.meshlink.power.PowerPolicyController
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportSendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

class MeshEnginePlatformBridgeTest {
    @Test
    fun `start wraps permission exceptions as permission denied`() {
        // Arrange
        val bridge =
            MeshEnginePlatformBridge(
                bleTransport =
                    object : BleTransport {
                        override val events: Flow<TransportEvent> = emptyFlow()

                        override suspend fun start(): Unit {
                            throw PlatformPermissionDeniedException("permissions denied")
                        }

                        override suspend fun pause(): Unit = Unit

                        override suspend fun resume(): Unit = Unit

                        override suspend fun stop(): Unit = Unit

                        override suspend fun send(frame: OutboundFrame): TransportSendResult {
                            return TransportSendResult.Delivered
                        }
                    }
            )

        // Act / Assert
        assertFailsWith<MeshLinkException.PermissionDenied> { runBlocking { bridge.start() } }
    }

    @Test
    fun `send returns dropped when no transport is available`() = runBlocking {
        // Arrange
        val bridge = MeshEnginePlatformBridge(bleTransport = null)
        val frame = OutboundFrame(peerId = PeerId("peer-abcdef"), payload = byteArrayOf(0x01))

        // Act
        val result = bridge.send(frame = frame, action = "delivery.send")

        // Assert
        val dropped = result as TransportSendResult.Dropped
        assertEquals("BLE transport is unavailable", dropped.reason)
    }

    @Test
    fun `send wraps platform exceptions as platform failures`() {
        // Arrange
        val bridge =
            MeshEnginePlatformBridge(
                bleTransport =
                    object : BleTransport {
                        override val events: Flow<TransportEvent> = emptyFlow()

                        override suspend fun start(): Unit = Unit

                        override suspend fun pause(): Unit = Unit

                        override suspend fun resume(): Unit = Unit

                        override suspend fun stop(): Unit = Unit

                        override suspend fun send(frame: OutboundFrame): TransportSendResult {
                            error("boom")
                        }
                    }
            )
        val frame = OutboundFrame(peerId = PeerId("peer-abcdef"), payload = byteArrayOf(0x01))

        // Act / Assert
        assertFailsWith<MeshLinkException.PlatformFailure> {
            runBlocking { bridge.send(frame = frame, action = "delivery.send") }
        }
    }

    @Test
    fun `bridge exposes transport capabilities only when a transport is present`() {
        // Arrange
        val powerPolicy =
            PowerPolicyController(
                    configuredMode = PowerMode.Automatic,
                    region = RegulatoryRegion.DEFAULT,
                )
                .currentPolicy(nowMillis = 0L)
        val bridge =
            MeshEnginePlatformBridge(
                bleTransport =
                    object : BleTransport {
                        override val events: Flow<TransportEvent> = emptyFlow()

                        override suspend fun start(): Unit = Unit

                        override suspend fun pause(): Unit = Unit

                        override suspend fun resume(): Unit = Unit

                        override suspend fun stop(): Unit = Unit

                        override suspend fun updatePowerPolicy(
                            policy: ch.trancee.meshlink.power.PowerPolicy
                        ): Unit = Unit

                        override fun maximumPayloadBytesPerDelivery(peerId: PeerId): Int {
                            return 512
                        }

                        override suspend fun send(frame: OutboundFrame): TransportSendResult {
                            return TransportSendResult.Delivered
                        }
                    }
            )
        val absentBridge = MeshEnginePlatformBridge(bleTransport = null)

        // Act
        val availableMaximum = bridge.maximumPayloadBytesPerDelivery(PeerId("peer-abcdef"))
        runBlocking { bridge.updatePowerPolicy(powerPolicy) }
        val absentMaximum = absentBridge.maximumPayloadBytesPerDelivery(PeerId("peer-abcdef"))

        // Assert
        assertEquals(512, availableMaximum)
        assertNull(absentMaximum)
        assertEquals(true, bridge.hasTransport)
        assertEquals(false, absentBridge.hasTransport)
    }
}
