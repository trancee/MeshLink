package ch.trancee.meshlink.internal

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [26, 37],
    shadows = [
        ShadowMeshAdvertiser::class,
        ShadowMeshManager::class,
        ShadowMeshGattServer::class,
        ShadowMeshDevice::class,
        ShadowMeshGatt::class,
    ],
)
class AndroidRadioCentralTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(60)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter = manager.adapter
    private val device = adapter.getRemoteDevice("00:11:22:33:44:55")
    private val peer = AndroidPeer(device)
    private val record = ByteArray(RECORD_SIZE) { it.toByte() }

    @Before
    fun arrangeAPeripheral() {
        resetRadioShadows()
        ShadowMeshDevice.discoverable += MeshGatt.service()
        ShadowMeshGatt.record = record
    }

    @Test
    fun connectsDirectlyOverLeAndAsksForTheLargestMtu() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)

            // Act
            val result = radio.connect(peer)

            // Assert
            val link = assertIs<RadioResult.Ok<CentralLink>>(result).value
            assertEquals(peer, link.peer)
            assertEquals(MeshGatt.REQUESTED_ATT_MTU, link.attMtu)
            assertEquals(listOf(MeshGatt.REQUESTED_ATT_MTU), ShadowMeshGatt.requestedMtus)
            val connection = ShadowMeshDevice.connections.single()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                val settings = assertNotNull(connection.settings)
                assertEquals(BluetoothDevice.TRANSPORT_LE, settings.transport)
                assertFalse(settings.isAutoConnectEnabled)
                val ran = mutableListOf<String>()
                assertNotNull(connection.executor).execute { ran += Thread.currentThread().name }
                assertEquals(listOf(Thread.currentThread().name), ran)
            } else {
                assertEquals(false, connection.autoConnect)
                assertEquals(BluetoothDevice.TRANSPORT_LE, connection.transport)
                assertEquals(BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_2M_MASK, connection.phy)
            }
        }

    @Test
    fun theLinkUsesTheMtuThePeripheralGranted() =
        runTest {
            // Arrange
            ShadowMeshGatt.grantedMtu = GRANTED_MTU
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)

            // Act
            val link = connected(radio)

            // Assert
            assertEquals(GRANTED_MTU, link.attMtu)
        }

    @Test
    fun theRadioClaimsNoLe2MPhyAndTheLinkReadsNoRssi() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val link = connected(radio)

            // Act
            val rssi = link.readRssi()

            // Assert
            assertFalse(radio.supportsLe2MPhy)
            assertEquals(RadioFailure.UNSUPPORTED, assertIs<RadioResult.Failed>(rssi).reason)
        }

    @Test
    fun aFailedMtuExchangeKeepsTheDefaultMtu() =
        runTest {
            // Arrange
            ShadowMeshGatt.grantedMtu = GRANTED_MTU
            ShadowMeshGatt.mtuStatus = BluetoothGatt.GATT_FAILURE
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)

            // Act
            val link = connected(radio)

            // Assert
            assertEquals(MeshGatt.DEFAULT_ATT_MTU, link.attMtu)
        }

    @Test
    fun aConnectionTheStackRefusesLeavesThePeerUnreachable() =
        runTest {
            // Arrange
            ShadowMeshDevice.refuseConnect = true
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)

            // Act
            val result = radio.connect(peer)

            // Assert
            assertEquals(RadioFailure.PEER_UNREACHABLE, assertIs<RadioResult.Failed>(result).reason)
            assertTrue(events.isEmpty())
        }

    @Test
    fun aConnectionThatFailsLaterLeavesThePeerUnreachableAndClosesTheClient() =
        runTest {
            // Arrange
            ShadowMeshDevice.connectOnRequest = false
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)
            val result = async { radio.connect(peer) }
            runCurrent()

            // Act
            callback().onConnectionStateChange(gatt(), FAILED_ESTABLISHMENT, BluetoothProfile.STATE_DISCONNECTED)

            // Assert
            assertEquals(RadioFailure.PEER_UNREACHABLE, assertIs<RadioResult.Failed>(result.await()).reason)
            assertTrue(shadow().isClosed)
            assertTrue(events.isEmpty())
        }

    @Test
    fun aConnectionThatFailsBeforeConnectGattReturnsClosesTheClient() =
        runTest {
            // Arrange
            ShadowMeshDevice.connectStatus = FAILED_ESTABLISHMENT
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)

            // Act
            val result = radio.connect(peer)

            // Assert
            assertEquals(RadioFailure.PEER_UNREACHABLE, assertIs<RadioResult.Failed>(result).reason)
            assertTrue(shadow().isClosed)
        }

    @Test
    fun aConnectionThatEndsWithStatus133Or147IsTransient() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)

            // Act
            val results =
                listOf(GATT_ERROR, GATT_CONNECTION_TIMEOUT).map { status ->
                    ShadowMeshDevice.connectStatus = status
                    radio.connect(peer)
                }

            // Assert
            assertEquals(
                listOf(RadioFailure.TRANSIENT, RadioFailure.TRANSIENT),
                results.map { assertIs<RadioResult.Failed>(it).reason },
            )
            assertTrue(events.isEmpty())
        }

    @Test
    fun aStatus133DuringServiceDiscoveryIsTransient() =
        runTest {
            // Arrange
            ShadowMeshGatt.answerDiscovery = false
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val result = async { radio.connect(peer) }
            runCurrent()

            // Act
            callback().onConnectionStateChange(gatt(), GATT_ERROR, BluetoothProfile.STATE_DISCONNECTED)

            // Assert
            assertEquals(RadioFailure.TRANSIENT, assertIs<RadioResult.Failed>(result.await()).reason)
        }

    @Test
    fun theStackClosingAnOpenLinkWith133Or147Or22IsATransientLoss() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)
            val statuses = listOf(GATT_ERROR, GATT_CONNECTION_TIMEOUT, TERMINATED_BY_LOCAL_HOST)

            // Act
            statuses.forEach { status ->
                connected(radio)
                callback().onConnectionStateChange(gatt(), status, BluetoothProfile.STATE_DISCONNECTED)
            }

            // Assert
            assertEquals(listOf(true, true, true), events.map { assertIs<RadioEvent.Disconnected>(it).transient })
        }

    @Test
    fun aPeerOrSupervisionCloseAndOurOwnDisconnectAreNotTransient() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)

            // Act
            listOf(TERMINATED_BY_PEER, SUPERVISION_TIMEOUT).forEach { status ->
                connected(radio)
                callback().onConnectionStateChange(gatt(), status, BluetoothProfile.STATE_DISCONNECTED)
            }
            connected(radio).disconnect()
            callback().onConnectionStateChange(gatt(), GATT_ERROR, BluetoothProfile.STATE_DISCONNECTED)

            // Assert
            assertEquals(listOf(false, false, false), events.map { assertIs<RadioEvent.Disconnected>(it).transient })
        }

    @Test
    fun aRefusedMtuRequestKeepsTheDefaultMtu() =
        runTest {
            // Arrange
            ShadowMeshGatt.refuseMtu = true
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)

            // Act
            val result = radio.connect(peer)

            // Assert
            val link = assertIs<RadioResult.Ok<CentralLink>>(result).value
            assertEquals(MeshGatt.DEFAULT_ATT_MTU, link.attMtu)
        }

    @Test
    fun aDisconnectDuringTheMtuExchangeClosesTheLink() =
        runTest {
            // Arrange
            ShadowMeshGatt.answerMtu = false
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val result = async { radio.connect(peer) }
            runCurrent()

            // Act
            callback().onConnectionStateChange(gatt(), BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)

            // Assert
            assertEquals(RadioFailure.LINK_CLOSED, assertIs<RadioResult.Failed>(result.await()).reason)
            assertTrue(shadow().isClosed)
        }

    @Test
    fun cancellingTheConnectionClosesTheClient() =
        runTest {
            // Arrange
            ShadowMeshDevice.connectOnRequest = false
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val result = async { radio.connect(peer) }
            runCurrent()

            // Act
            result.cancel()
            runCurrent()

            // Assert
            assertTrue(result.isCancelled)
            assertTrue(shadow().isClosed)
        }

    @Test
    fun aDisconnectDuringServiceDiscoveryClosesTheLink() =
        runTest {
            // Arrange
            ShadowMeshGatt.answerDiscovery = false
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val result = async { radio.connect(peer) }
            runCurrent()

            // Act
            callback().onConnectionStateChange(gatt(), BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)

            // Assert
            assertEquals(RadioFailure.LINK_CLOSED, assertIs<RadioResult.Failed>(result.await()).reason)
            assertTrue(shadow().isClosed)
        }

    @Test
    fun aFailedServiceDiscoveryClosesTheLink() =
        runTest {
            // Arrange
            ShadowMeshGatt.discoverStatus = BluetoothGatt.GATT_FAILURE
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)

            // Act
            val result = radio.connect(peer)

            // Assert
            assertEquals(RadioFailure.LINK_CLOSED, assertIs<RadioResult.Failed>(result).reason)
            assertTrue(shadow().isClosed)
            assertTrue(events.isEmpty())
        }

    @Test
    fun aPeripheralWithoutTheCompleteMeshServiceLacksTheCapability() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val services =
                listOf(
                    BluetoothGattService(UUID.randomUUID(), BluetoothGattService.SERVICE_TYPE_PRIMARY),
                    meshService(uuid = MeshGatt.MESH),
                    meshService(missing = MeshGatt.CAPABILITY),
                    meshService(missing = MeshGatt.TO_PERIPHERAL),
                    meshService(missing = MeshGatt.TO_CENTRAL),
                    meshService(missing = MeshGatt.CCCD),
                )

            // Act
            val results =
                services.map {
                    ShadowMeshDevice.discoverable.clear()
                    ShadowMeshDevice.discoverable += it
                    radio.connect(peer)
                }

            // Assert
            val reasons = results.map { assertIs<RadioResult.Failed>(it).reason }
            assertEquals(List(services.size) { RadioFailure.CAPABILITY_MISSING }, reasons)
            assertTrue(ShadowMeshDevice.gatts.all { Shadow.extract<ShadowMeshGatt>(it).isClosed })
        }

    @Test
    fun readingTheCapabilitySubscribesToThePeripheralsStream() =
        runTest {
            // Arrange
            val link = connected(AndroidRadio(context, manager, adapter, backgroundScope))

            // Act
            val result = link.readCapability()

            // Assert
            assertContentEquals(record, assertIs<RadioResult.Ok<ByteArray>>(result).value)
            assertEquals(listOf(ENABLE.toList()), ShadowMeshGatt.descriptorWrites.map { it.toList() })
        }

    @Test
    fun aFailedReadDoesNotSubscribe() =
        runTest {
            // Arrange
            ShadowMeshGatt.readStatus = BluetoothGatt.GATT_READ_NOT_PERMITTED
            val link = connected(AndroidRadio(context, manager, adapter, backgroundScope))

            // Act
            val result = link.readCapability()

            // Assert
            assertEquals(RadioFailure.RADIO_ERROR, assertIs<RadioResult.Failed>(result).reason)
            assertTrue(ShadowMeshGatt.descriptorWrites.isEmpty())
        }

    @Test
    fun aSubscriptionTheStackRefusesFailsTheRead() =
        runTest {
            // Arrange
            ShadowMeshGatt.notificationsAllowed = false
            val link = connected(AndroidRadio(context, manager, adapter, backgroundScope))

            // Act
            val result = link.readCapability()

            // Assert
            assertEquals(RadioFailure.RADIO_ERROR, assertIs<RadioResult.Failed>(result).reason)
            assertTrue(ShadowMeshGatt.descriptorWrites.isEmpty())
        }

    @Test
    fun aSubscriptionThePeripheralRefusesFailsTheRead() =
        runTest {
            // Arrange
            ShadowMeshGatt.descriptorStatus = BluetoothGatt.GATT_WRITE_NOT_PERMITTED
            val link = connected(AndroidRadio(context, manager, adapter, backgroundScope))

            // Act
            val result = link.readCapability()

            // Assert
            assertEquals(RadioFailure.RADIO_ERROR, assertIs<RadioResult.Failed>(result).reason)
        }

    @Test
    fun readingAClosedLinkFailsAsClosed() =
        runTest {
            // Arrange
            val link = connected(AndroidRadio(context, manager, adapter, backgroundScope))
            link.disconnect()

            // Act
            val result = link.readCapability()

            // Assert
            assertEquals(RadioFailure.LINK_CLOSED, assertIs<RadioResult.Failed>(result).reason)
        }

    @Test
    fun aLinkThatNeverOpenedCanNeitherReadNorWrite() =
        runTest {
            // Arrange
            val link = GattCentralLink(context, device, Dispatchers.IO) {}

            // Act
            val read = link.readCapability()
            val write = link.write(byteArrayOf(1))

            // Assert
            assertEquals(RadioFailure.RADIO_ERROR, assertIs<RadioResult.Failed>(read).reason)
            assertEquals(RadioFailure.LINK_CLOSED, assertIs<RadioResult.Failed>(write).reason)
        }

    @Test
    fun aWriteGoesWithoutResponseInChunksOfTheMtuMinus3() =
        runTest {
            // Arrange
            ShadowMeshGatt.grantedMtu = SMALL_MTU
            val link = connected(AndroidRadio(context, manager, adapter, backgroundScope))

            // Act
            val result = link.write(record)

            // Assert
            assertIs<RadioResult.Ok<Unit>>(result)
            assertEquals(listOf(5, 5, 2), ShadowMeshGatt.written.map { it.size })
            assertContentEquals(record, ShadowMeshGatt.written.flatMap { it.toList() }.toByteArray())
            val noResponse = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            assertEquals(List(3) { noResponse }, ShadowMeshGatt.writeTypes)
        }

    @Test
    fun aBusyWriteIsRetried() =
        runTest {
            // Arrange
            val link = connected(AndroidRadio(context, manager, adapter, backgroundScope))
            ShadowMeshGatt.writeResults += listOf(GattExchange.BUSY, GattExchange.BUSY)

            // Act
            val result = link.write(byteArrayOf(1))

            // Assert
            assertIs<RadioResult.Ok<Unit>>(result)
            assertEquals(1, ShadowMeshGatt.written.size)
            assertEquals(2 * GattExchange.BUSY_RETRY_MILLIS, testScheduler.currentTime)
        }

    @Test
    fun aFailedWriteDisconnectsTheLinkOnce() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)
            val link = connected(radio)
            ShadowMeshGatt.writeStatuses += BluetoothGatt.GATT_FAILURE

            // Act
            val result = link.write(byteArrayOf(1, 2))
            val again = link.write(byteArrayOf(3))

            // Assert
            assertEquals(RadioFailure.LINK_CLOSED, assertIs<RadioResult.Failed>(result).reason)
            assertIs<RadioResult.Failed>(again)
            assertEquals(1, ShadowMeshGatt.written.size)
            assertTrue(shadow().isClosed)
            assertSame(link, assertIs<RadioEvent.Disconnected>(events.single()).link)
        }

    @Test
    fun aDisconnectFailsTheQueuedWrite() =
        runTest {
            // Arrange
            val link = connected(AndroidRadio(context, manager, adapter, backgroundScope))
            ShadowMeshGatt.writeResults += GattExchange.BUSY
            val pending = async { link.write(byteArrayOf(1)) }
            val queued = async { link.write(byteArrayOf(2)) }
            runCurrent()

            // Act
            remoteDisconnect()

            // Assert
            assertIs<RadioResult.Failed>(pending.await())
            assertIs<RadioResult.Failed>(queued.await())
            assertTrue(ShadowMeshGatt.written.isEmpty())
        }

    @Test
    fun notificationsOnThePeripheralsStreamArriveInOrder() =
        runTest {
            // Arrange
            val link = connected(AndroidRadio(context, manager, adapter, backgroundScope))
            val received = async { link.incoming.toList() }
            val service = gatt().getService(MeshGatt.SERVICE)
            val toCentral = service.getCharacteristic(MeshGatt.TO_CENTRAL)
            val capability = service.getCharacteristic(MeshGatt.CAPABILITY)

            // Act
            notify(toCentral, byteArrayOf(1))
            notify(capability, byteArrayOf(9))
            notify(toCentral, null)
            notify(toCentral, byteArrayOf(2))
            remoteDisconnect()

            // Assert
            assertEquals(listOf(listOf<Byte>(1), listOf(), listOf<Byte>(2)), received.await().map { it.toList() })
        }

    @Test
    fun aRemoteDisconnectClosesTheLinkOnce() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)
            val link = connected(radio)

            // Act
            remoteDisconnect()
            link.disconnect()

            // Assert
            assertSame(link, assertIs<RadioEvent.Disconnected>(events.single()).link)
            assertTrue(shadow().isClosed)
        }

    private suspend fun connected(radio: AndroidRadio): CentralLink {
        val result = radio.connect(peer)
        return assertIs<RadioResult.Ok<CentralLink>>(result).value
    }

    private fun gatt(): BluetoothGatt = ShadowMeshDevice.gatts.last()

    private fun shadow(): ShadowMeshGatt = Shadow.extract(gatt())

    private fun callback(): BluetoothGattCallback = shadow().gattCallback

    private fun remoteDisconnect() =
        callback().onConnectionStateChange(gatt(), BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)

    /** Delivers a notification as the running SDK does: SDK 26-32 carry [value] in the characteristic. */
    @Suppress("DEPRECATION") // SDK 26-32 call only the deprecated callback, which reads the characteristic.
    private fun notify(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray?,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            callback().onCharacteristicChanged(gatt(), characteristic, value ?: ByteArray(0))
        } else {
            characteristic.value = value
            callback().onCharacteristicChanged(gatt(), characteristic)
        }
    }

    /** The MeshLink service under [uuid], without the characteristic or descriptor [missing]. */
    private fun meshService(
        uuid: UUID = MeshGatt.SERVICE,
        missing: UUID? = null,
    ): BluetoothGattService {
        val complete = MeshGatt.service()
        val service = BluetoothGattService(uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        complete.characteristics.filter { it.uuid != missing }.forEach { original ->
            val copy = BluetoothGattCharacteristic(original.uuid, original.properties, original.permissions)
            original.descriptors.filter { it.uuid != missing }.forEach {
                copy.addDescriptor(BluetoothGattDescriptor(it.uuid, it.permissions))
            }
            service.addCharacteristic(copy)
        }
        return service
    }

    private companion object {
        const val RECORD_SIZE = 12
        const val GRANTED_MTU = 185
        const val SMALL_MTU = 8
        const val GATT_ERROR = 133
        const val GATT_CONNECTION_TIMEOUT = 147
        const val TERMINATED_BY_LOCAL_HOST = 22
        const val TERMINATED_BY_PEER = 19
        const val SUPERVISION_TIMEOUT = 8
        const val FAILED_ESTABLISHMENT = 62
        val ENABLE: ByteArray = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
    }
}
