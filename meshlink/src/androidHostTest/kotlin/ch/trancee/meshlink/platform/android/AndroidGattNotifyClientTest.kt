package ch.trancee.meshlink.platform.android

import android.bluetooth.BluetoothProfile
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.BleDiscoveryContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class GattNotifyClientTest {
    @Test
    fun startOpensTheSessionOnceAndConnectedEventRequestsDiscoveryWhenMtuRequestFails(): Unit {
        // Arrange
        val session = FakeAndroidGattNotifySession(requestMtuResult = false)
        val factory = FakeAndroidGattNotifySessionFactory(session)
        val client = createAndroidGattNotifyClient(factory = factory)

        client.start()
        client.start()

        // Act
        factory.listener.onConnectionStateChange(
            address = session.address,
            status = 0,
            newState = BluetoothProfile.STATE_CONNECTED,
        )

        // Assert
        assertEquals(1, factory.openCalls)
        assertEquals(1, session.highPriorityRequests)
        assertEquals(1, session.fastPhyRequests)
        assertEquals(listOf(517), session.requestedMtus)
        assertEquals(1, session.discoverServicesCalls)
    }

    @Test
    fun servicesDiscoveredWithMissingServiceClosesTheSession(): Unit {
        // Arrange
        val session =
            FakeAndroidGattNotifySession(
                characteristicResolution = GattNotifyCharacteristicResolution.MISSING_SERVICE
            )
        val factory = FakeAndroidGattNotifySessionFactory(session)
        val client = createAndroidGattNotifyClient(factory = factory)
        client.start()

        // Act
        factory.listener.onServicesDiscovered(status = 0)

        // Assert
        assertEquals(1, session.closeCalls)
        assertFalse(client.isReady())
    }

    @Test
    fun writeDelegatesToTheSessionAfterNotificationsAreEnabled(): Unit = runBlocking {
        // Arrange
        val expectedEncodedChunk = L2capFrameBuffer().encode(byteArrayOf(0x01, 0x02, 0x03))
        val session =
            FakeAndroidGattNotifySession(
                characteristicResolution = GattNotifyCharacteristicResolution.READY,
                hasWriteCharacteristicFlag = true,
                enableNotificationsResult = GattNotifyEnableNotificationsResult.REQUESTED,
            )
        val factory = FakeAndroidGattNotifySessionFactory(session)
        session.writeChunkHandler = { chunk ->
            factory.listener.onCharacteristicWrite(
                characteristicUuid = BleDiscoveryContract.GATT_WRITE_CHARACTERISTIC_UUID,
                status = 0,
            )
            true
        }
        val client = createAndroidGattNotifyClient(factory = factory)
        client.start()
        factory.listener.onServicesDiscovered(status = 0)
        factory.listener.onDescriptorWrite(
            descriptorUuid = "00002902-0000-1000-8000-00805f9b34fb",
            status = 0,
        )

        // Act
        val written = client.write(byteArrayOf(0x01, 0x02, 0x03))

        // Assert
        assertTrue(client.isReady())
        assertTrue(written)
        assertEquals(1, session.writeChunks.size)
        assertEquals(expectedEncodedChunk.toList(), session.writeChunks.single().toList())
    }
}

private fun createAndroidGattNotifyClient(factory: GattNotifySessionFactory): GattNotifyClient {
    return GattNotifyClient(
        context = Any(),
        appId = "app",
        peerHintId = PeerId("peer-android"),
        device = Any(),
        log = {},
        onFrameReceived = { _, _ -> true },
        onDisconnected = {},
        sessionFactory = factory,
    )
}

private class FakeAndroidGattNotifySessionFactory(
    private val session: FakeAndroidGattNotifySession
) : GattNotifySessionFactory {
    lateinit var listener: GattNotifySessionListener
    var openCalls: Int = 0

    override fun open(listener: GattNotifySessionListener): GattNotifySession {
        this.listener = listener
        openCalls += 1
        return session
    }
}

private class FakeAndroidGattNotifySession(
    private val requestMtuResult: Boolean = true,
    private val characteristicResolution: GattNotifyCharacteristicResolution =
        GattNotifyCharacteristicResolution.READY,
    private val enableNotificationsResult: GattNotifyEnableNotificationsResult =
        GattNotifyEnableNotificationsResult.REQUESTED,
    private val hasWriteCharacteristicFlag: Boolean = false,
) : GattNotifySession {
    override val address: String = "AA:BB:CC:DD"

    var highPriorityRequests: Int = 0
    var fastPhyRequests: Int = 0
    val requestedMtus: MutableList<Int> = mutableListOf()
    var discoverServicesCalls: Int = 0
    var closeCalls: Int = 0
    val writeChunks: MutableList<ByteArray> = mutableListOf()
    var writeChunkHandler: (ByteArray) -> Boolean = { true }

    override fun requestHighConnectionPriority(): Unit {
        highPriorityRequests += 1
    }

    override fun requestFastPhyIfSupported(): Unit {
        fastPhyRequests += 1
    }

    override fun requestMtu(mtu: Int): Boolean {
        requestedMtus += mtu
        return requestMtuResult
    }

    override fun discoverServices(): Unit {
        discoverServicesCalls += 1
    }

    override fun resolveFallbackCharacteristics(): GattNotifyCharacteristicResolution {
        return characteristicResolution
    }

    override fun hasWriteCharacteristic(): Boolean {
        return hasWriteCharacteristicFlag
    }

    override fun enableNotifications(): GattNotifyEnableNotificationsResult {
        return enableNotificationsResult
    }

    override fun writeChunk(chunk: ByteArray): Boolean {
        writeChunks += chunk.copyOf()
        return writeChunkHandler(chunk)
    }

    override fun close(): Unit {
        closeCalls += 1
    }
}
