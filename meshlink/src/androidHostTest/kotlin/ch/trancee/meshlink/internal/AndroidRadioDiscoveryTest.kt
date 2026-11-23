package ch.trancee.meshlink.internal

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
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
class AndroidRadioDiscoveryTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(60)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter = manager.adapter
    private val payload = ByteArray(RadioPort.ADVERTISEMENT_LENGTH) { (it + 1).toByte() }

    @Before
    fun clearShadows() = resetRadioShadows()

    @Test
    fun advertisesThePayloadAsALegacyConnectableSetOn1M() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)

            // Act
            val result = radio.startAdvertising(payload)

            // Assert
            assertIs<RadioResult.Ok<Unit>>(result)
            val started = ShadowMeshAdvertiser.started.single()
            assertTrue(started.parameters.isLegacy)
            assertTrue(started.parameters.isConnectable)
            assertTrue(started.parameters.isScannable)
            assertEquals(BluetoothDevice.PHY_LE_1M, started.parameters.primaryPhy)
            assertEquals(BluetoothDevice.PHY_LE_1M, started.parameters.secondaryPhy)
            val payloadUuid = ParcelUuid(MeshGatt.uuidOf(payload))
            assertEquals(listOf(MeshGatt.MESH_PARCEL, payloadUuid), started.data.serviceUuids)
            assertEquals(false, started.data.includeDeviceName)
            assertEquals(false, started.data.includeTxPowerLevel)
            assertNull(started.scanResponse)
        }

    @Test
    fun advertisingAgainStopsTheEarlierSetFirst() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            radio.startAdvertising(payload)

            // Act
            radio.startAdvertising(payload.reversedArray())

            // Assert
            assertEquals(2, ShadowMeshAdvertiser.started.size)
            assertEquals(listOf(ShadowMeshAdvertiser.started.first().callback), ShadowMeshAdvertiser.stopped)
        }

    @Test
    fun stoppingAdvertisingStopsTheSetOnce() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            radio.startAdvertising(payload)

            // Act
            radio.stopAdvertising()
            radio.stopAdvertising()

            // Assert
            assertEquals(listOf(ShadowMeshAdvertiser.started.single().callback), ShadowMeshAdvertiser.stopped)
        }

    @Test
    fun aPayloadThatIsNot16BytesIsRefused() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)

            // Act
            val result = radio.startAdvertising(ByteArray(RadioPort.ADVERTISEMENT_LENGTH - 1))

            // Assert
            assertEquals(RadioFailure.INVALID_ARGUMENT, assertIs<RadioResult.Failed>(result).reason)
            assertTrue(ShadowMeshAdvertiser.started.isEmpty())
        }

    @Test
    fun advertisingWithBluetoothOffFails() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            shadowOf(adapter).apply {
                setEnabled(false)
                setBleScanAlwaysAvailable(false)
            }

            // Act
            val result = radio.startAdvertising(payload)

            // Assert
            assertEquals(RadioFailure.RADIO_ERROR, assertIs<RadioResult.Failed>(result).reason)
        }

    @Test
    fun onlyAFailedStartOfTheCurrentSetIsReported() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)
            radio.startAdvertising(payload)
            radio.startAdvertising(payload)
            val (stale, current) = ShadowMeshAdvertiser.started.map { it.callback }

            // Act
            stale.onAdvertisingSetStarted(null, 0, AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR)
            current.onAdvertisingSetStarted(null, 0, AdvertisingSetCallback.ADVERTISE_SUCCESS)
            current.onAdvertisingSetStarted(null, 0, AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS)

            // Assert
            assertEquals(RadioFailure.RADIO_ERROR, assertIs<RadioEvent.Failed>(events.single()).reason)
        }

    @Test
    fun scanningFiltersOnTheMeshServiceAndAppleDataAndStartsOnce() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)

            // Act
            val first = radio.startScanning()
            val second = radio.startScanning()

            // Assert
            assertIs<RadioResult.Ok<Unit>>(first)
            assertIs<RadioResult.Ok<Unit>>(second)
            val scan = shadowOf(adapter.bluetoothLeScanner).activeScans.single()
            val (service, apple) = scan.scanFilters()
            assertEquals(MeshGatt.MESH_PARCEL, service.serviceUuid)
            assertEquals(APPLE, apple.manufacturerId)
            assertNull(apple.serviceUuid)
            assertContentEquals(ByteArray(0), apple.manufacturerData)
            assertNull(apple.manufacturerDataMask)
            assertEquals(ScanSettings.SCAN_MODE_BALANCED, scan.scanSettings()?.scanMode)
        }

    @Test
    fun eachWindowIsOneStartAndOneStop() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val scanner = shadowOf(adapter.bluetoothLeScanner)

            // Act
            radio.startScanning()
            val during = scanner.activeScans.size
            radio.stopScanning()

            // Assert
            assertEquals(1, during)
            assertTrue(scanner.activeScans.isEmpty())
        }

    @Test
    fun aSixthStartWithin30SecondsWaitsUntilTheFirstIs30SecondsOld() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope, time = testScheduler.timeSource)
            val scanner = shadowOf(adapter.bluetoothLeScanner)
            repeat(BUDGET) {
                radio.startScanning()
                radio.stopScanning()
                idle(SPACING)
            }

            // Act
            val sixth = radio.startScanning()
            val again = radio.startScanning()
            idle(BUDGET_WINDOW - BUDGET * SPACING - 1)
            val early = scanner.activeScans.size
            idle(1)

            // Assert
            assertIs<RadioResult.Ok<Unit>>(sixth)
            assertIs<RadioResult.Ok<Unit>>(again)
            assertEquals(0, early)
            assertEquals(1, scanner.activeScans.size)
        }

    @Test
    fun aStartBeyondTheBudgetBeginsAtOnceWhenTheOldestStartIs30SecondsOld() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope, time = testScheduler.timeSource)
            repeat(BUDGET) {
                radio.startScanning()
                radio.stopScanning()
            }
            idle(BUDGET_WINDOW)

            // Act
            radio.startScanning()

            // Assert
            assertEquals(1, shadowOf(adapter.bluetoothLeScanner).activeScans.size)
        }

    @Test
    fun stoppingBeforeADeferredStartCancelsIt() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope, time = testScheduler.timeSource)
            repeat(BUDGET) {
                radio.startScanning()
                radio.stopScanning()
            }
            radio.startScanning()

            // Act
            radio.stopScanning()
            idle(BUDGET_WINDOW)

            // Assert
            assertTrue(shadowOf(adapter.bluetoothLeScanner).activeScans.isEmpty())
            assertTrue(shadowOf(adapter.bluetoothLeScanner).scanCallbacks.isEmpty())
        }

    @Test
    fun aDeferredStartAfterBluetoothTurnedOffIsReported() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope, time = testScheduler.timeSource)
            val events = collect(radio)
            repeat(BUDGET) {
                radio.startScanning()
                radio.stopScanning()
            }
            radio.startScanning()

            // Act
            shadowOf(adapter).apply {
                setEnabled(false)
                setBleScanAlwaysAvailable(false)
            }
            idle(BUDGET_WINDOW)

            // Assert
            assertEquals(RadioFailure.RADIO_ERROR, assertIs<RadioEvent.Failed>(events.single()).reason)
        }

    @Test
    fun anAdvertisementWithAPayloadIsSighted() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)
            radio.startScanning()
            val device = adapter.getRemoteDevice(ADDRESS)

            // Act
            scanCallback().onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result(device, advertisement(payload)))

            // Assert
            val sighting = assertIs<RadioEvent.Sighted>(events.single()).sighting
            assertContentEquals(payload, sighting.payload)
            assertEquals(AndroidPeer(device), sighting.peer)
            assertEquals(RSSI, sighting.rssi)
        }

    @Test
    fun anAdvertisementWithoutPayloadOrMeshServiceIsIgnored() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)
            radio.startScanning()
            val device = adapter.getRemoteDevice(ADDRESS)
            val callback = scanCallback()
            val withoutMesh = advertisement(payload, mesh = false)

            // Act
            callback.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result(device, advertisement(null)))
            callback.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result(device, withoutMesh))
            callback.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result(device, null))

            // Assert
            assertTrue(events.isEmpty())
        }

    @Test
    fun stoppingTheScanIgnoresItsLateCallbacks() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)
            radio.startScanning()
            val callback = scanCallback()
            val device = adapter.getRemoteDevice(ADDRESS)

            // Act
            radio.stopScanning()
            radio.stopScanning()
            callback.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result(device, advertisement(payload)))
            callback.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)

            // Assert
            assertTrue(shadowOf(adapter.bluetoothLeScanner).activeScans.isEmpty())
            assertTrue(events.isEmpty())
        }

    @Test
    fun aScanFailureIsReportedAndStoppingStillStopsTheScan() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)
            radio.startScanning()

            // Act
            scanCallback().onScanFailed(ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED)
            radio.stopScanning()

            // Assert
            assertEquals(RadioFailure.RADIO_ERROR, assertIs<RadioEvent.Failed>(events.single()).reason)
            assertTrue(shadowOf(adapter.bluetoothLeScanner).activeScans.isEmpty())
        }

    @Test
    fun anOverflowMeshBitAnywhereInTheAdvertisementIsSightedWithoutPayload() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)
            radio.startScanning()
            val device = adapter.getRemoteDevice(ADDRESS)
            val callback = scanCallback()
            val flags = byteArrayOf(2, 1, 0x1A)
            val nearbyInfo = byteArrayOf(10, -1, 0x4C, 0, 0x10, 5, 1, 2, 3, 4, 5)
            val overflow = overflowArea(meshBit = true)
            val packed = byteArrayOf(27, -1, 0x4C, 0, 0x10, 5, 1, 2, 3, 4, 5) + overflow.copyOfRange(4, overflow.size)

            // Act
            listOf(flags + nearbyInfo + overflow, flags + overflow + nearbyInfo, overflow, flags + packed).forEach {
                callback.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result(device, record(it)))
            }

            // Assert
            assertEquals(4, events.size)
            events.forEach {
                val sighting = assertIs<RadioEvent.Sighted>(it).sighting
                assertNull(sighting.payload)
                assertEquals(AndroidPeer(device), sighting.peer)
                assertEquals(RSSI, sighting.rssi)
            }
        }

    @Test
    fun appleDataWithoutTheOverflowMeshBitIsIgnored() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)
            radio.startScanning()
            val device = adapter.getRemoteDevice(ADDRESS)
            val callback = scanCallback()
            val nearbyInfo = byteArrayOf(10, -1, 0x4C, 0, 0x10, 5, 1, 2, 3, 4, 5)
            val short = byteArrayOf(4, -1, 0x4C, 0, 1)
            val otherCompany = overflowArea(meshBit = true).also { it[2] = 0x4D }
            val afterTheEnd = byteArrayOf(0) + overflowArea(meshBit = true)
            val truncated = overflowArea(meshBit = true).copyOf(OVERFLOW_SIZE)
            val allButMesh =
                overflowArea(meshBit = false).also {
                    it.fill(-1, 5)
                    it[it.size - 1] = 0xF7.toByte()
                }
            val noMeshArea = overflowArea(meshBit = false)
            val records = listOf(nearbyInfo, short, otherCompany, noMeshArea, afterTheEnd, truncated, allButMesh)

            // Act
            records.forEach {
                callback.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result(device, record(it)))
            }

            // Assert
            assertTrue(events.isEmpty())
        }

    @Test
    fun scanningWithBluetoothOffFails() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            shadowOf(adapter).apply {
                setEnabled(false)
                setBleScanAlwaysAvailable(false)
            }

            // Act
            val result = radio.startScanning()

            // Assert
            assertEquals(RadioFailure.RADIO_ERROR, assertIs<RadioResult.Failed>(result).reason)
        }

    @Test
    fun aPeerOfAnotherRadioCannotBeConnected() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)

            // Act
            val result = radio.connect(object : PeerHandle {})

            // Assert
            assertEquals(RadioFailure.INVALID_ARGUMENT, assertIs<RadioResult.Failed>(result).reason)
            assertSame(null, ShadowMeshDevice.connections.firstOrNull())
        }

    private fun scanCallback(): ScanCallback = shadowOf(adapter.bluetoothLeScanner).scanCallbacks.single()

    private fun result(
        device: BluetoothDevice,
        record: ScanRecord?,
    ) = ScanResult(device, 1, BluetoothDevice.PHY_LE_1M, 0, 0xFF, 127, RSSI, 0, record, 0L)

    /** An advertisement with the MESH UUID unless not [mesh] and [payload] as a 128-bit UUID if any. */
    private fun advertisement(
        payload: ByteArray?,
        mesh: Boolean = true,
    ): ScanRecord {
        val flags = byteArrayOf(2, 1, 6)
        val meshUuid = if (mesh) byteArrayOf(5, 5, 0x48, 0x53, 0x45, 0x4D) else ByteArray(0)
        val payloadUuid = payload?.let { byteArrayOf(17, 7) + it.reversedArray() } ?: ByteArray(0)
        return record(flags + meshUuid + payloadUuid)
    }

    /** Apple's overflow area: manufacturer data of type 1 with a 16-byte bitmap, MESH being the last byte's `0x08`. */
    private fun overflowArea(meshBit: Boolean): ByteArray {
        val area = ByteArray(OVERFLOW_SIZE + 4)
        area[0] = (OVERFLOW_SIZE + 3).toByte()
        area[1] = -1
        area[2] = 0x4C
        area[4] = 1
        area[area.size - 1] = if (meshBit) 0x0A else 0x02
        return area
    }

    private fun record(bytes: ByteArray): ScanRecord =
        ReflectionHelpers.callStaticMethod(
            ScanRecord::class.java,
            "parseFromBytes",
            ClassParameter.from(ByteArray::class.java, bytes),
        )

    private fun TestScope.idle(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    private companion object {
        const val ADDRESS = "00:11:22:33:44:55"
        const val RSSI = -60
        const val APPLE = 0x004C
        const val OVERFLOW_SIZE = 17
        const val BUDGET = 5
        const val BUDGET_WINDOW = 30_000L
        const val SPACING = 1_000L
    }
}

/** Every event [radio] emits from now on, as it emits them. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.collect(radio: RadioPort): List<RadioEvent> {
    val events = mutableListOf<RadioEvent>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { radio.events.collect { events += it } }
    return events
}
