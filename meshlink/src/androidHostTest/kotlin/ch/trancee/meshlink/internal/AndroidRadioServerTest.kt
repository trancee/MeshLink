package ch.trancee.meshlink.internal

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
class AndroidRadioServerTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(60)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter = manager.adapter
    private val central = adapter.getRemoteDevice("00:11:22:33:44:55")
    private val other = adapter.getRemoteDevice("66:77:88:99:00:11")
    private val record = ByteArray(RECORD_SIZE) { it.toByte() }
    private val service = MeshGatt.service()
    private val capability = service.getCharacteristic(MeshGatt.CAPABILITY)
    private val toPeripheral = service.getCharacteristic(MeshGatt.TO_PERIPHERAL)
    private val toCentral = service.getCharacteristic(MeshGatt.TO_CENTRAL)
    private val cccd = toCentral.getDescriptor(MeshGatt.CCCD)

    @Before
    fun clearShadows() = resetRadioShadows()

    @Test
    fun servingOpensAServerWithTheMeshService() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)

            // Act
            val result = radio.serveCapability(record)

            // Assert
            assertIs<RadioResult.Ok<Unit>>(result)
            val served =
                ShadowMeshManager.servers
                    .single()
                    .services
                    .single()
            assertEquals(MeshGatt.SERVICE, served.uuid)
            val uuids = served.characteristics.map { it.uuid }
            assertEquals(listOf(MeshGatt.CAPABILITY, MeshGatt.TO_PERIPHERAL, MeshGatt.TO_CENTRAL), uuids)
        }

    @Test
    fun aRecordOver512BytesIsRefusedAndOpensNoServer() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)

            // Act
            val result = radio.serveCapability(ByteArray(RadioPort.CAPABILITY_RECORD_MAX + 1))

            // Assert
            assertEquals(RadioFailure.INVALID_ARGUMENT, assertIs<RadioResult.Failed>(result).reason)
            assertEquals(0, ShadowMeshManager.opens)
        }

    @Test
    fun servingAgainReplacesTheRecordOnTheOpenServer() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            radio.serveCapability(ByteArray(1))

            // Act
            radio.serveCapability(record)

            // Assert
            assertEquals(1, ShadowMeshManager.opens)
            callback().onCharacteristicReadRequest(central, REQUEST, 0, capability)
            assertContentEquals(record, responses().single().value)
        }

    @Test
    fun aServerThatWillNotOpenIsRetriedAfter250And500Ms() =
        runTest {
            // Arrange
            ShadowMeshManager.openFailures = 2
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)

            // Act
            radio.serveCapability(record)
            advanceTimeBy(FIRST_RETRY_MILLIS)
            runCurrent()
            val beforeSecondRetry = ShadowMeshManager.opens
            radio.serveCapability(record)
            advanceTimeBy(SECOND_RETRY_MILLIS)
            runCurrent()

            // Assert
            assertEquals(2, beforeSecondRetry)
            assertEquals(3, ShadowMeshManager.opens)
            assertEquals(1, ShadowMeshManager.servers.size)
            assertTrue(events.isEmpty())
        }

    @Test
    fun aServerThatNeverOpensFailsAfterTheFourthRetry() =
        runTest {
            // Arrange
            ShadowMeshManager.openFailures = Int.MAX_VALUE
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)

            // Act
            radio.serveCapability(record)
            advanceTimeBy(ALL_RETRIES_MILLIS - 1)
            runCurrent()
            val beforeLastRetry = events.toList()
            advanceTimeBy(1)
            runCurrent()

            // Assert
            assertTrue(beforeLastRetry.isEmpty())
            assertEquals(5, ShadowMeshManager.opens)
            assertEquals(RadioFailure.RADIO_ERROR, assertIs<RadioEvent.Failed>(events.single()).reason)
        }

    @Test
    fun aServiceTheStackCouldNotAddFailsTheRadio() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)
            radio.serveCapability(record)

            // Act
            callback().onServiceAdded(BluetoothGatt.GATT_SUCCESS, service)
            callback().onServiceAdded(BluetoothGatt.GATT_FAILURE, service)

            // Assert
            assertEquals(RadioFailure.RADIO_ERROR, assertIs<RadioEvent.Failed>(events.single()).reason)
        }

    @Test
    fun capabilityReadsAnswerTheRecordFromTheRequestedOffset() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            radio.serveCapability(record)

            // Act
            callback().onCharacteristicReadRequest(central, REQUEST, 0, capability)
            callback().onCharacteristicReadRequest(central, REQUEST + 1, OFFSET, capability)
            callback().onCharacteristicReadRequest(central, REQUEST + 2, RECORD_SIZE, capability)

            // Assert
            val (whole, tail, end) = responses()
            assertEquals(listOf(REQUEST, REQUEST + 1, REQUEST + 2), responses().map { it.requestId })
            assertEquals(listOf(BluetoothGatt.GATT_SUCCESS), responses().map { it.status }.distinct())
            assertContentEquals(record, whole.value)
            assertEquals(OFFSET, tail.offset)
            assertContentEquals(record.copyOfRange(OFFSET, RECORD_SIZE), tail.value)
            assertContentEquals(ByteArray(0), end.value)
        }

    @Test
    fun readsPastTheRecordOrOfOtherCharacteristicsAreRefused() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            radio.serveCapability(record)

            // Act
            callback().onCharacteristicReadRequest(central, REQUEST, RECORD_SIZE + 1, capability)
            callback().onCharacteristicReadRequest(central, REQUEST + 1, 0, toCentral)

            // Assert
            assertEquals(listOf(BluetoothGatt.GATT_INVALID_OFFSET), responses().map { it.status }.distinct())
            assertEquals(listOf(null, null), responses().map { it.value })
        }

    @Test
    fun enablingNotificationsConnectsTheCentralOnce() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)
            radio.serveCapability(record)

            // Act
            enable(central)
            enable(central)

            // Assert
            val link = assertIs<RadioEvent.Connected>(events.single()).link
            assertEquals(AndroidPeer(central), link.peer)
            assertEquals(MeshGatt.DEFAULT_ATT_MTU, link.attMtu)
            assertEquals(listOf(REQUEST, REQUEST), responses().map { it.requestId })
        }

    @Test
    fun otherDescriptorWritesConnectNothing() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)
            radio.serveCapability(record)
            val foreign = BluetoothGattDescriptor(MeshGatt.TO_CENTRAL, BluetoothGattDescriptor.PERMISSION_WRITE)
            val disable = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE

            // Act
            callback().onDescriptorWriteRequest(central, REQUEST, cccd, false, false, 0, disable)
            callback().onDescriptorWriteRequest(central, REQUEST, foreign, false, true, 0, ENABLE)

            // Assert
            assertTrue(events.isEmpty())
            assertEquals(listOf(REQUEST), responses().map { it.requestId })
        }

    @Test
    fun writesToThePeripheralCharacteristicArriveOnTheLinkInOrder() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)
            radio.serveCapability(record)
            val link = connect(central, events)
            val received = async { link.incoming.toList() }

            // Act
            callback().onCharacteristicWriteRequest(central, REQUEST, toPeripheral, false, false, 0, byteArrayOf(1))
            callback().onCharacteristicWriteRequest(central, REQUEST, capability, false, false, 0, byteArrayOf(9))
            callback().onCharacteristicWriteRequest(other, REQUEST, toPeripheral, false, false, 0, byteArrayOf(9))
            callback().onCharacteristicWriteRequest(central, REQUEST + 1, toPeripheral, false, true, 0, byteArrayOf(2))
            remoteDisconnect(central)

            // Assert
            assertEquals(listOf(listOf<Byte>(1), listOf<Byte>(2)), received.await().map { it.toList() })
            assertEquals(listOf(REQUEST, REQUEST + 1), responses().map { it.requestId })
        }

    @Test
    fun aWriteIsNotifiedInChunksOfTheNegotiatedMtuMinus3() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)
            radio.serveCapability(record)
            callback().onMtuChanged(central, SMALL_MTU)
            val link = connect(central, events)
            repeat(3) { ShadowMeshGattServer.sentStatuses += BluetoothGatt.GATT_SUCCESS }

            // Act
            val result = link.write(record)

            // Assert
            assertIs<RadioResult.Ok<Unit>>(result)
            assertEquals(SMALL_MTU, link.attMtu)
            val notified = ShadowMeshGattServer.notified
            assertEquals(listOf(5, 5, 2), notified.map { it.value.size })
            assertContentEquals(record, notified.flatMap { it.value.toList() }.toByteArray())
            assertEquals(setOf(central), notified.map { it.device }.toSet())
            assertEquals(setOf(MeshGatt.TO_CENTRAL), notified.map { it.characteristic.uuid }.toSet())
            assertFalse(notified.any { it.confirm })
        }

    @Test
    fun aBusyNotificationIsRetried() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)
            radio.serveCapability(record)
            val link = connect(central, events)
            ShadowMeshGattServer.notifyResults += listOf(GattExchange.BUSY, GattExchange.BUSY)
            ShadowMeshGattServer.sentStatuses += BluetoothGatt.GATT_SUCCESS

            // Act
            val result = link.write(byteArrayOf(1))

            // Assert
            assertIs<RadioResult.Ok<Unit>>(result)
            assertEquals(1, ShadowMeshGattServer.notified.size)
            assertEquals(2 * GattExchange.BUSY_RETRY_MILLIS, testScheduler.currentTime)
        }

    @Test
    fun aRefusedNotificationClosesTheLink() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)
            radio.serveCapability(record)
            val link = connect(central, events)
            // SDK 26-32 report a refusal only as false, which reads as busy until the retries run out.
            ShadowMeshGattServer.notifyResults += List(GattExchange.BUSY_RETRIES) { BluetoothGatt.GATT_FAILURE }

            // Act
            val result = link.write(byteArrayOf(1))

            // Assert
            assertEquals(RadioFailure.LINK_CLOSED, assertIs<RadioResult.Failed>(result).reason)
            assertTrue(serverShadow().isConnectionCancelled(central))
            assertTrue(ShadowMeshGattServer.notified.isEmpty())
            assertSame(link, assertIs<RadioEvent.Disconnected>(events.last()).link)
        }

    @Test
    fun aNotificationTheStackFailedClosesTheLink() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)
            radio.serveCapability(record)
            val link = connect(central, events)
            ShadowMeshGattServer.sentStatuses += BluetoothGatt.GATT_FAILURE

            // Act
            val result = link.write(byteArrayOf(1))
            val again = link.write(byteArrayOf(2))

            // Assert
            assertIs<RadioResult.Failed>(result)
            assertEquals(RadioFailure.LINK_CLOSED, assertIs<RadioResult.Failed>(again).reason)
            assertEquals(1, ShadowMeshGattServer.notified.size)
            assertEquals(1, events.count { it is RadioEvent.Disconnected })
        }

    @Test
    fun aRemoteDisconnectFailsTheLinksPendingAndQueuedWrites() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)
            radio.serveCapability(record)
            val link = connect(central, events)
            val pending = async { link.write(byteArrayOf(1)) }
            val queued = async { link.write(byteArrayOf(2)) }
            runCurrent()

            // Act
            callback().onConnectionStateChange(central, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            remoteDisconnect(central)

            // Assert
            assertIs<RadioResult.Failed>(pending.await())
            assertIs<RadioResult.Failed>(queued.await())
            assertEquals(1, ShadowMeshGattServer.notified.size)
            assertSame(link, assertIs<RadioEvent.Disconnected>(events.last()).link)
            assertFalse(serverShadow().isConnectionCancelled(central))
        }

    @Test
    fun anotherCentralsDisconnectLeavesAPendingNotificationWaiting() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)
            radio.serveCapability(record)
            callback().onMtuChanged(central, SMALL_MTU)
            val link = connect(central, events)
            connect(other, events)
            val pending = async { link.write(byteArrayOf(1)) }
            runCurrent()

            // Act
            remoteDisconnect(other)
            runCurrent()
            val waiting = pending.isActive
            callback().onNotificationSent(central, BluetoothGatt.GATT_SUCCESS)

            // Assert
            assertTrue(waiting)
            assertIs<RadioResult.Ok<Unit>>(pending.await())
            assertEquals(SMALL_MTU, link.attMtu)
        }

    @Test
    fun disconnectingCancelsTheConnectionOnce() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)
            radio.serveCapability(record)
            val link = connect(central, events)

            // Act
            link.disconnect()
            link.disconnect()
            remoteDisconnect(central)

            // Assert
            assertTrue(serverShadow().isConnectionCancelled(central))
            assertEquals(1, events.count { it is RadioEvent.Disconnected })
            assertTrue(link.incoming.toList().isEmpty())
        }

    @Test
    fun aDisconnectForgetsTheCentralsMtuAndLink() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)
            radio.serveCapability(record)
            callback().onMtuChanged(central, SMALL_MTU)
            connect(central, events)

            // Act
            remoteDisconnect(central)
            val again = connect(central, events)

            // Assert
            assertEquals(MeshGatt.DEFAULT_ATT_MTU, again.attMtu)
            assertEquals(2, events.count { it is RadioEvent.Connected })
        }

    @Test
    fun bluetoothOffClosesTheServerAndEveryLink() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)
            radio.serveCapability(record)
            callback().onMtuChanged(central, SMALL_MTU)
            val link = connect(central, events)
            val pending = async { link.write(byteArrayOf(1)) }
            runCurrent()
            val stale = callback()

            // Act
            radio.bluetoothOff()

            // Assert
            assertIs<RadioResult.Failed>(pending.await())
            assertTrue(serverShadow().isClosed)
            assertSame(link, assertIs<RadioEvent.Disconnected>(events.last()).link)
            stale.onNotificationSent(central, BluetoothGatt.GATT_SUCCESS)
            stale.onCharacteristicReadRequest(central, REQUEST, 0, capability)
            enable(central, stale)
            assertEquals(1, responses().size)
            assertEquals(2, events.size)
        }

    @Test
    fun servingAfterBluetoothOffOpensANewServer() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)
            radio.bluetoothOff()
            radio.serveCapability(record)
            callback().onMtuChanged(central, SMALL_MTU)
            radio.bluetoothOff()

            // Act
            radio.serveCapability(record)
            val link = connect(central, events)

            // Assert
            assertEquals(2, ShadowMeshManager.servers.size)
            assertEquals(MeshGatt.DEFAULT_ATT_MTU, link.attMtu)
        }

    @Test
    fun bluetoothOffStopsRetryingAndServingAgainReopens() =
        runTest {
            // Arrange
            ShadowMeshManager.openFailures = 1
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            radio.serveCapability(record)
            radio.bluetoothOff()
            advanceTimeBy(ALL_RETRIES_MILLIS)
            runCurrent()
            val afterOff = ShadowMeshManager.opens

            // Act
            radio.serveCapability(record)

            // Assert
            assertEquals(1, afterOff)
            assertEquals(1, ShadowMeshManager.servers.size)
        }

    @Test
    fun servingAfterASuccessfulRetryAndBluetoothOffOpensAtOnce() =
        runTest {
            // Arrange
            ShadowMeshManager.openFailures = 1
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            radio.serveCapability(record)
            advanceTimeBy(FIRST_RETRY_MILLIS)
            runCurrent()
            radio.bluetoothOff()

            // Act
            radio.serveCapability(record)

            // Assert
            assertEquals(2, ShadowMeshManager.servers.size)
        }

    private fun serverShadow(): ShadowMeshGattServer = Shadow.extract(ShadowMeshManager.servers.last())

    private fun callback(): BluetoothGattServerCallback = serverShadow().gattServerCallback

    private fun responses(): List<ShadowMeshGattServer.Response> =
        ShadowMeshManager.servers.flatMap { Shadow.extract<ShadowMeshGattServer>(it).sent }

    private fun enable(
        device: BluetoothDevice,
        callback: BluetoothGattServerCallback = callback(),
    ) = callback.onDescriptorWriteRequest(device, REQUEST, cccd, false, true, 0, ENABLE)

    private fun TestScope.connect(
        device: BluetoothDevice,
        events: List<RadioEvent>,
    ): RadioLink {
        enable(device)
        runCurrent()
        return assertIs<RadioEvent.Connected>(events.last()).link
    }

    private fun remoteDisconnect(device: BluetoothDevice) =
        callback().onConnectionStateChange(device, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)

    private companion object {
        const val RECORD_SIZE = 12
        const val OFFSET = 5
        const val REQUEST = 7
        const val SMALL_MTU = 8
        const val FIRST_RETRY_MILLIS = 250L
        const val SECOND_RETRY_MILLIS = 500L
        const val ALL_RETRIES_MILLIS = 3_750L
        val ENABLE: ByteArray = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
    }
}
