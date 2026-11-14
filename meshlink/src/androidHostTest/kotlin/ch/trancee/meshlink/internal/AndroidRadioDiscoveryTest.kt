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
            assertEquals(listOf(MeshGatt.SERVICE_PARCEL, payloadUuid), started.data.serviceUuids)
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
    fun scanningFiltersOnTheMeshServiceAndStartsOnce() =
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
            assertEquals(listOf(MeshGatt.SERVICE_PARCEL), scan.scanFilters().map { it.serviceUuid })
            assertEquals(ScanSettings.SCAN_MODE_BALANCED, scan.scanSettings()?.scanMode)
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
    fun aScanFailureIsReported() =
        runTest {
            // Arrange
            val radio = AndroidRadio(context, manager, adapter, backgroundScope)
            val events = collect(radio)
            radio.startScanning()

            // Act
            scanCallback().onScanFailed(ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED)

            // Assert
            assertEquals(RadioFailure.RADIO_ERROR, assertIs<RadioEvent.Failed>(events.single()).reason)
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
        val bytes = flags + meshUuid + payloadUuid
        return ReflectionHelpers.callStaticMethod(
            ScanRecord::class.java,
            "parseFromBytes",
            ClassParameter.from(ByteArray::class.java, bytes),
        )
    }

    private companion object {
        const val ADDRESS = "00:11:22:33:44:55"
        const val RSSI = -60
    }
}

/** Every event [radio] emits from now on, as it emits them. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.collect(radio: RadioPort): List<RadioEvent> {
    val events = mutableListOf<RadioEvent>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { radio.events.collect { events += it } }
    return events
}
