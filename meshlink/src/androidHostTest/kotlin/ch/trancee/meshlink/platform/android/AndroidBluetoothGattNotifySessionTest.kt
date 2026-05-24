package ch.trancee.meshlink.platform.android

import android.os.Build
import ch.trancee.meshlink.transport.BleDiscoveryContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidBluetoothGattNotifySessionTest {
    @Test
    fun requestFastPhyIfSupportedSkipsTheAdapterBelowApi26(): Unit {
        // Arrange
        val connection = FakeAndroidGattConnectionAdapter()
        val session = AndroidBluetoothGattNotifySession(connection = connection, sdkInt = 25)

        // Act
        session.requestFastPhyIfSupported()

        // Assert
        assertEquals(0, connection.fastPhyRequests)
    }

    @Test
    fun requestFastPhyIfSupportedDelegatesToTheAdapterOnApi26AndAbove(): Unit {
        // Arrange
        val connection = FakeAndroidGattConnectionAdapter()
        val session =
            AndroidBluetoothGattNotifySession(
                connection = connection,
                sdkInt = Build.VERSION_CODES.O,
            )

        // Act
        session.requestFastPhyIfSupported()

        // Assert
        assertEquals(1, connection.fastPhyRequests)
    }

    @Test
    fun resolveFallbackCharacteristicsReturnsMissingServiceWhenTheServiceIsAbsent(): Unit {
        // Arrange
        val connection = FakeAndroidGattConnectionAdapter(service = null)
        val session = AndroidBluetoothGattNotifySession(connection = connection, sdkInt = 25)

        // Act
        val resolution = session.resolveFallbackCharacteristics()

        // Assert
        assertEquals(AndroidGattNotifyCharacteristicResolution.MISSING_SERVICE, resolution)
    }

    @Test
    fun enableNotificationsReturnsMissingCccdWhenTheDescriptorIsAbsent(): Unit {
        // Arrange
        val notifyCharacteristic = FakeAndroidGattCharacteristicAdapter(descriptor = null)
        val writeCharacteristic = FakeAndroidGattCharacteristicAdapter()
        val connection =
            FakeAndroidGattConnectionAdapter(
                service =
                    FakeAndroidGattServiceAdapter(
                        notifyCharacteristic = notifyCharacteristic,
                        writeCharacteristic = writeCharacteristic,
                    )
            )
        val session = AndroidBluetoothGattNotifySession(connection = connection, sdkInt = 25)
        session.resolveFallbackCharacteristics()

        // Act
        val result = session.enableNotifications()

        // Assert
        assertEquals(AndroidGattNotifyEnableNotificationsResult.MISSING_CCCD, result)
        assertEquals(1, connection.notificationRequests.size)
        assertEquals(0, connection.writeDescriptorCalls)
    }

    @Test
    fun enableNotificationsWritesTheCccdWhenPresent(): Unit {
        // Arrange
        val descriptor = FakeAndroidGattDescriptorAdapter()
        val notifyCharacteristic = FakeAndroidGattCharacteristicAdapter(descriptor = descriptor)
        val writeCharacteristic = FakeAndroidGattCharacteristicAdapter()
        val connection =
            FakeAndroidGattConnectionAdapter(
                service =
                    FakeAndroidGattServiceAdapter(
                        notifyCharacteristic = notifyCharacteristic,
                        writeCharacteristic = writeCharacteristic,
                    ),
                writeDescriptorResult = true,
            )
        val session = AndroidBluetoothGattNotifySession(connection = connection, sdkInt = 25)
        session.resolveFallbackCharacteristics()

        // Act
        val result = session.enableNotifications()

        // Assert
        assertEquals(AndroidGattNotifyEnableNotificationsResult.REQUESTED, result)
        assertEquals(1, connection.writeDescriptorCalls)
        assertTrue(descriptor.enableNotificationValueSet)
    }

    @Test
    fun writeChunkConfiguresTheWriteCharacteristicBeforeDelegating(): Unit {
        // Arrange
        val writeCharacteristic = FakeAndroidGattCharacteristicAdapter()
        val connection =
            FakeAndroidGattConnectionAdapter(
                service =
                    FakeAndroidGattServiceAdapter(
                        notifyCharacteristic = FakeAndroidGattCharacteristicAdapter(),
                        writeCharacteristic = writeCharacteristic,
                    ),
                writeCharacteristicResult = true,
            )
        val session = AndroidBluetoothGattNotifySession(connection = connection, sdkInt = 25)
        session.resolveFallbackCharacteristics()
        val chunk = byteArrayOf(0x01, 0x02, 0x03)

        // Act
        val written = session.writeChunk(chunk)

        // Assert
        assertTrue(written)
        assertEquals(1, writeCharacteristic.writeTypeDefaultCalls)
        assertEquals(chunk.toList(), writeCharacteristic.recordedValue.toList())
        assertEquals(1, connection.writeCharacteristicCalls)
    }

    @Test
    fun writeChunkReturnsFalseWhenTheWriteCharacteristicWasNeverResolved(): Unit {
        // Arrange
        val session =
            AndroidBluetoothGattNotifySession(
                connection = FakeAndroidGattConnectionAdapter(),
                sdkInt = 25,
            )

        // Act
        val written = session.writeChunk(byteArrayOf(0x01))

        // Assert
        assertFalse(written)
    }
}

private class FakeAndroidGattConnectionAdapter(
    private val service: AndroidGattServiceAdapter? = null,
    private val writeDescriptorResult: Boolean = true,
    private val writeCharacteristicResult: Boolean = true,
) : AndroidGattConnectionAdapter {
    override val address: String = "AA:BB:CC:DD"

    var highPriorityRequests: Int = 0
    var fastPhyRequests: Int = 0
    val requestedMtus: MutableList<Int> = mutableListOf()
    var discoverServicesCalls: Int = 0
    val notificationRequests: MutableList<Pair<String, Boolean>> = mutableListOf()
    var writeDescriptorCalls: Int = 0
    var writeCharacteristicCalls: Int = 0
    var closeCalls: Int = 0

    override fun requestHighConnectionPriority(): Unit {
        highPriorityRequests += 1
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

    override fun findService(uuid: String): AndroidGattServiceAdapter? {
        return if (uuid == BleDiscoveryContract.GATT_FALLBACK_SERVICE_UUID) service else null
    }

    override fun setCharacteristicNotification(
        characteristic: AndroidGattCharacteristicAdapter,
        enabled: Boolean,
    ): Unit {
        notificationRequests += characteristic.uuid to enabled
    }

    override fun writeDescriptor(descriptor: AndroidGattDescriptorAdapter): Boolean {
        writeDescriptorCalls += 1
        return writeDescriptorResult
    }

    override fun writeCharacteristic(characteristic: AndroidGattCharacteristicAdapter): Boolean {
        writeCharacteristicCalls += 1
        return writeCharacteristicResult
    }

    override fun close(): Unit {
        closeCalls += 1
    }
}

private class FakeAndroidGattServiceAdapter(
    private val notifyCharacteristic: AndroidGattCharacteristicAdapter?,
    private val writeCharacteristic: AndroidGattCharacteristicAdapter?,
) : AndroidGattServiceAdapter {
    override fun findCharacteristic(uuid: String): AndroidGattCharacteristicAdapter? {
        return when (uuid) {
            BleDiscoveryContract.GATT_NOTIFY_CHARACTERISTIC_UUID -> notifyCharacteristic
            BleDiscoveryContract.GATT_WRITE_CHARACTERISTIC_UUID -> writeCharacteristic
            else -> null
        }
    }
}

private class FakeAndroidGattCharacteristicAdapter(
    private val descriptor: AndroidGattDescriptorAdapter? = FakeAndroidGattDescriptorAdapter()
) : AndroidGattCharacteristicAdapter {
    override val uuid: String = BleDiscoveryContract.GATT_NOTIFY_CHARACTERISTIC_UUID
    var writeTypeDefaultCalls: Int = 0
    var recordedValue: ByteArray = byteArrayOf()

    override fun findDescriptor(uuid: String): AndroidGattDescriptorAdapter? {
        return if (uuid == "00002902-0000-1000-8000-00805f9b34fb") descriptor else null
    }

    override fun setWriteTypeDefault(): Unit {
        writeTypeDefaultCalls += 1
    }

    override fun setValue(value: ByteArray): Unit {
        recordedValue = value.copyOf()
    }
}

private class FakeAndroidGattDescriptorAdapter : AndroidGattDescriptorAdapter {
    override val uuid: String = "00002902-0000-1000-8000-00805f9b34fb"
    var enableNotificationValueSet: Boolean = false

    override fun setEnableNotificationValue(): Unit {
        enableNotificationValueSet = true
    }
}
