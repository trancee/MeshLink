package ch.trancee.meshlink.platform.android

import android.bluetooth.BluetoothGattCharacteristic
import android.os.Build
import ch.trancee.meshlink.transport.BleDiscoveryContract
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BluetoothGattNotifySessionTest {
    @Test
    fun requestFastPhyIfSupportedSkipsTheAdapterBelowApi26(): Unit {
        // Arrange
        val connection = FakeGattConnectionAdapter()
        val session = BluetoothGattNotifySession(connection = connection, sdkInt = 25)

        // Act
        session.requestFastPhyIfSupported()

        // Assert
        assertEquals(0, connection.fastPhyRequests)
    }

    @Test
    fun requestFastPhyIfSupportedDelegatesToTheAdapterOnApi26AndAbove(): Unit {
        // Arrange
        val connection = FakeGattConnectionAdapter()
        val session =
            BluetoothGattNotifySession(connection = connection, sdkInt = Build.VERSION_CODES.O)

        // Act
        session.requestFastPhyIfSupported()

        // Assert
        assertEquals(1, connection.fastPhyRequests)
    }

    @Test
    fun resolveFallbackCharacteristicsReturnsMissingServiceWhenTheServiceIsAbsent(): Unit {
        // Arrange
        val connection = FakeGattConnectionAdapter(service = null)
        val session = BluetoothGattNotifySession(connection = connection, sdkInt = 25)

        // Act
        val resolution = session.resolveFallbackCharacteristics()

        // Assert
        assertEquals(GattNotifyCharacteristicResolution.MISSING_SERVICE, resolution)
    }

    @Test
    fun refreshServiceCacheDelegatesToTheAdapter(): Unit {
        // Arrange
        val connection = FakeGattConnectionAdapter(refreshServiceCacheResult = true)
        val session = BluetoothGattNotifySession(connection = connection, sdkInt = 25)

        // Act
        val refreshed = session.refreshServiceCache()

        // Assert
        assertTrue(refreshed)
        assertEquals(1, connection.refreshServiceCacheCalls)
    }

    @Test
    fun enableNotificationsReturnsMissingCccdWhenTheDescriptorIsAbsent(): Unit {
        // Arrange
        val notifyCharacteristic = FakeGattCharacteristicAdapter(descriptor = null)
        val writeCharacteristic =
            FakeGattCharacteristicAdapter(
                uuid = BleDiscoveryContract.GATT_WRITE_CHARACTERISTIC_UUID
            )
        val connection =
            FakeGattConnectionAdapter(
                service =
                    FakeGattServiceAdapter(
                        notifyCharacteristic = notifyCharacteristic,
                        writeCharacteristic = writeCharacteristic,
                    )
            )
        val session = BluetoothGattNotifySession(connection = connection, sdkInt = 25)
        session.resolveFallbackCharacteristics()

        // Act
        val result = session.enableNotifications()

        // Assert
        assertEquals(GattNotifyEnableNotificationsResult.MISSING_CCCD, result)
        assertEquals(1, connection.notificationRequests.size)
        assertEquals(0, connection.writeDescriptorCalls)
    }

    @Test
    fun enableNotificationsWritesTheCccdWhenPresent(): Unit {
        // Arrange
        val descriptor = FakeGattDescriptorAdapter()
        val notifyCharacteristic = FakeGattCharacteristicAdapter(descriptor = descriptor)
        val writeCharacteristic =
            FakeGattCharacteristicAdapter(
                uuid = BleDiscoveryContract.GATT_WRITE_CHARACTERISTIC_UUID
            )
        val connection =
            FakeGattConnectionAdapter(
                service =
                    FakeGattServiceAdapter(
                        notifyCharacteristic = notifyCharacteristic,
                        writeCharacteristic = writeCharacteristic,
                    ),
                writeDescriptorResult = true,
            )
        val session = BluetoothGattNotifySession(connection = connection, sdkInt = 25)
        session.resolveFallbackCharacteristics()

        // Act
        val result = session.enableNotifications()

        // Assert
        assertEquals(GattNotifyEnableNotificationsResult.REQUESTED, result)
        assertEquals(1, connection.writeDescriptorCalls)
        assertSame(descriptor, connection.lastDescriptor)
        assertContentEquals(byteArrayOf(0x01, 0x00), connection.lastDescriptorValue)
    }

    @Test
    fun writeChunkPassesTheValueAndWriteTypeToTheConnection(): Unit {
        // Arrange
        val writeCharacteristic =
            FakeGattCharacteristicAdapter(
                uuid = BleDiscoveryContract.GATT_WRITE_CHARACTERISTIC_UUID
            )
        val connection =
            FakeGattConnectionAdapter(
                service =
                    FakeGattServiceAdapter(
                        notifyCharacteristic = FakeGattCharacteristicAdapter(),
                        writeCharacteristic = writeCharacteristic,
                    ),
                writeCharacteristicResult = true,
            )
        val session = BluetoothGattNotifySession(connection = connection, sdkInt = 25)
        session.resolveFallbackCharacteristics()
        val chunk = byteArrayOf(0x01, 0x02, 0x03)

        // Act
        val written = session.writeChunk(chunk)

        // Assert
        assertTrue(written)
        assertSame(writeCharacteristic, connection.lastWriteCharacteristic)
        assertContentEquals(chunk, connection.lastWriteValue)
        // WRITE_TYPE_NO_RESPONSE avoids waiting for a full ATT round trip per chunk, which lets
        // GattNotifyClient pipeline multiple chunks instead of a stop-and-wait design.
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE, connection.lastWriteType)
        assertEquals(1, connection.writeCharacteristicCalls)
    }

    @Test
    fun writeChunkReturnsFalseWhenTheWriteCharacteristicWasNeverResolved(): Unit {
        // Arrange
        val session =
            BluetoothGattNotifySession(connection = FakeGattConnectionAdapter(), sdkInt = 25)

        // Act
        val written = session.writeChunk(byteArrayOf(0x01))

        // Assert
        assertFalse(written)
    }
}

private class FakeGattConnectionAdapter(
    private val service: GattServiceAdapter? = null,
    private val writeDescriptorResult: Boolean = true,
    private val writeCharacteristicResult: Boolean = true,
    private val refreshServiceCacheResult: Boolean = true,
) : GattConnectionAdapter {
    override val address: String = "AA:BB:CC:DD"

    var highPriorityRequests: Int = 0
    var lastRequestedPriority: Int? = null
    var fastPhyRequests: Int = 0
    val requestedMtus: MutableList<Int> = mutableListOf()
    var discoverServicesCalls: Int = 0
    var refreshServiceCacheCalls: Int = 0
    val notificationRequests: MutableList<Pair<String, Boolean>> = mutableListOf()
    var writeDescriptorCalls: Int = 0
    var writeCharacteristicCalls: Int = 0
    var closeCalls: Int = 0
    var lastDescriptor: GattDescriptorAdapter? = null
    var lastDescriptorValue: ByteArray? = null
    var lastWriteCharacteristic: GattCharacteristicAdapter? = null
    var lastWriteValue: ByteArray? = null
    var lastWriteType: Int? = null

    override fun requestConnectionPriority(priority: Int): Unit {
        highPriorityRequests += 1
        lastRequestedPriority = priority
    }

    override fun requestFastPhy(): Unit {
        fastPhyRequests += 1
    }

    override fun requestMtu(mtu: Int): Boolean {
        requestedMtus += mtu
        return true
    }

    override fun discoverServices(): Unit {
        discoverServicesCalls += 1
    }

    override fun refreshServiceCache(): Boolean {
        refreshServiceCacheCalls += 1
        return refreshServiceCacheResult
    }

    override fun findService(uuid: String): GattServiceAdapter? {
        return if (uuid == BleDiscoveryContract.GATT_FALLBACK_SERVICE_UUID) service else null
    }

    override fun setCharacteristicNotification(
        characteristic: GattCharacteristicAdapter,
        enabled: Boolean,
    ): Unit {
        notificationRequests += characteristic.uuid to enabled
    }

    override fun writeDescriptor(descriptor: GattDescriptorAdapter, value: ByteArray): Boolean {
        writeDescriptorCalls += 1
        lastDescriptor = descriptor
        lastDescriptorValue = value.copyOf()
        return writeDescriptorResult
    }

    override fun writeCharacteristic(
        characteristic: GattCharacteristicAdapter,
        value: ByteArray,
        writeType: Int,
    ): Boolean {
        writeCharacteristicCalls += 1
        lastWriteCharacteristic = characteristic
        lastWriteValue = value.copyOf()
        lastWriteType = writeType
        return writeCharacteristicResult
    }

    override fun close(): Unit {
        closeCalls += 1
    }
}

private class FakeGattServiceAdapter(
    private val notifyCharacteristic: GattCharacteristicAdapter?,
    private val writeCharacteristic: GattCharacteristicAdapter?,
) : GattServiceAdapter {
    override fun findCharacteristic(uuid: String): GattCharacteristicAdapter? {
        return when (uuid) {
            BleDiscoveryContract.GATT_NOTIFY_CHARACTERISTIC_UUID -> notifyCharacteristic
            BleDiscoveryContract.GATT_WRITE_CHARACTERISTIC_UUID -> writeCharacteristic
            else -> null
        }
    }
}

private class FakeGattCharacteristicAdapter(
    override val uuid: String = BleDiscoveryContract.GATT_NOTIFY_CHARACTERISTIC_UUID,
    private val descriptor: GattDescriptorAdapter? = FakeGattDescriptorAdapter(),
) : GattCharacteristicAdapter {
    override fun findDescriptor(uuid: String): GattDescriptorAdapter? {
        return if (uuid == "00002902-0000-1000-8000-00805f9b34fb") descriptor else null
    }
}

private class FakeGattDescriptorAdapter : GattDescriptorAdapter {
    override val uuid: String = "00002902-0000-1000-8000-00805f9b34fb"
}
