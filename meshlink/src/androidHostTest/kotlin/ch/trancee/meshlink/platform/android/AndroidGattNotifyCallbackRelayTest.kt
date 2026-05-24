package ch.trancee.meshlink.platform.android

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidGattNotifyCallbackRelayTest {
    @Test
    fun onConnectionStateChangeForwardsTheAddressStatusAndState(): Unit {
        // Arrange
        val listener = RecordingAndroidGattNotifySessionListener()
        val relay = AndroidGattNotifyCallbackRelay(listener)

        // Act
        relay.onConnectionStateChange(address = "AA:BB", status = 7, newState = 2)

        // Assert
        assertEquals(
            ConnectionStateChange(address = "AA:BB", status = 7, newState = 2),
            listener.connectionStateChange,
        )
    }

    @Test
    fun onCharacteristicChangedCopiesThePayloadBeforeForwarding(): Unit {
        // Arrange
        val listener = RecordingAndroidGattNotifySessionListener()
        val relay = AndroidGattNotifyCallbackRelay(listener)
        val value = byteArrayOf(0x01, 0x02)

        // Act
        relay.onCharacteristicChanged(characteristicUuid = "uuid", value = value)
        value[0] = 0x09

        // Assert
        assertContentEquals(byteArrayOf(0x01, 0x02), listener.characteristicValue)
        assertEquals("uuid", listener.characteristicUuid)
    }

    @Test
    fun onCharacteristicChangedIgnoresNullValues(): Unit {
        // Arrange
        val listener = RecordingAndroidGattNotifySessionListener()
        val relay = AndroidGattNotifyCallbackRelay(listener)

        // Act
        relay.onCharacteristicChanged(characteristicUuid = "uuid", value = null)

        // Assert
        assertNull(listener.characteristicUuid)
        assertNull(listener.characteristicValue)
    }
}

private class RecordingAndroidGattNotifySessionListener : AndroidGattNotifySessionListener {
    var connectionStateChange: ConnectionStateChange? = null
    var characteristicUuid: String? = null
    var characteristicValue: ByteArray? = null

    override fun onConnectionStateChange(address: String, status: Int, newState: Int): Unit {
        connectionStateChange =
            ConnectionStateChange(address = address, status = status, newState = newState)
    }

    override fun onMtuChanged(mtu: Int, status: Int): Unit = Unit

    override fun onPhyUpdate(txPhy: Int, rxPhy: Int, status: Int): Unit = Unit

    override fun onServicesDiscovered(status: Int): Unit = Unit

    override fun onDescriptorWrite(descriptorUuid: String, status: Int): Unit = Unit

    override fun onCharacteristicChanged(characteristicUuid: String, value: ByteArray): Unit {
        this.characteristicUuid = characteristicUuid
        this.characteristicValue = value
    }

    override fun onCharacteristicWrite(characteristicUuid: String, status: Int): Unit = Unit
}

private data class ConnectionStateChange(val address: String, val status: Int, val newState: Int)
