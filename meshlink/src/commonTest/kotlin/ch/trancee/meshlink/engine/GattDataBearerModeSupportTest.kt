package ch.trancee.meshlink.engine

import ch.trancee.meshlink.transport.GattDataBearerMode
import kotlin.test.Test
import kotlin.test.assertEquals

class GattDataBearerModeSupportTest {
    @Test
    fun resolveGattDataBearerModeUsesTheGattFallbackForDataFrames(): Unit {
        // Arrange
        val directFrame = DirectWireFrame.Data(ByteArray(32))

        // Act
        val bearerMode = resolveGattDataBearerMode(directFrame = directFrame)

        // Assert
        assertEquals(GattDataBearerMode.L2CAP_PREFERRED_WITH_GATT_FALLBACK, bearerMode)
    }

    @Test
    fun resolveGattDataBearerModeKeepsHandshakeMessage1FramesOnGatt(): Unit {
        // Arrange
        val directFrame = DirectWireFrame.HandshakeMessage1(byteArrayOf(0x01, 0x02))

        // Act
        val bearerMode = resolveGattDataBearerMode(directFrame = directFrame)

        // Assert
        assertEquals(GattDataBearerMode.GATT_ONLY, bearerMode)
    }

    @Test
    fun resolveGattDataBearerModeKeepsHandshakeMessage2FramesOnGatt(): Unit {
        // Arrange
        val directFrame = DirectWireFrame.HandshakeMessage2(byteArrayOf(0x01, 0x02))

        // Act
        val bearerMode = resolveGattDataBearerMode(directFrame = directFrame)

        // Assert
        assertEquals(GattDataBearerMode.GATT_ONLY, bearerMode)
    }

    @Test
    fun resolveGattDataBearerModeKeepsHandshakeMessage3FramesOnGatt(): Unit {
        // Arrange
        val directFrame = DirectWireFrame.HandshakeMessage3(byteArrayOf(0x01, 0x02))

        // Act
        val bearerMode = resolveGattDataBearerMode(directFrame = directFrame)

        // Assert
        assertEquals(GattDataBearerMode.GATT_ONLY, bearerMode)
    }

    @Test
    fun resolveGattDataBearerModeKeepsUndecodableFramesOnGatt(): Unit {
        // Act
        val bearerMode = resolveGattDataBearerMode(directFrame = null)

        // Assert
        assertEquals(GattDataBearerMode.GATT_ONLY, bearerMode)
    }
}
