package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.engine.transport.DirectWireFrame
import kotlin.test.Test
import kotlin.test.assertEquals

class L2capWritePumpTelemetryTest {
    @Test
    fun classifyL2capFrameLabelsHandshakePayloadsAsHandshake(): Unit {
        // Arrange
        val encodedFrame = DirectWireFrame.HandshakeMessage1(byteArrayOf(0x01, 0x02)).encode()

        // Act
        val telemetry = classifyL2capFrame(encodedFrame)

        // Assert
        assertEquals("HANDSHAKE_MESSAGE_1", telemetry.directType)
        assertEquals("handshake", telemetry.dataClass)
        assertEquals(2, telemetry.innerBytes)
    }

    @Test
    fun classifyL2capFrameTreatsSmallDataFramesAsAckLikely(): Unit {
        // Arrange
        val encodedFrame = DirectWireFrame.Data(ByteArray(32)).encode()

        // Act
        val telemetry = classifyL2capFrame(encodedFrame)

        // Assert
        assertEquals("DATA", telemetry.directType)
        assertEquals("ackLikely", telemetry.dataClass)
        assertEquals(32, telemetry.innerBytes)
    }

    @Test
    fun classifyL2capFrameTreatsLargeDataFramesAsBulkLikely(): Unit {
        // Arrange
        val encodedFrame = DirectWireFrame.Data(ByteArray(256)).encode()

        // Act
        val telemetry = classifyL2capFrame(encodedFrame)

        // Assert
        assertEquals("DATA", telemetry.directType)
        assertEquals("bulkLikely", telemetry.dataClass)
        assertEquals(256, telemetry.innerBytes)
    }
}
