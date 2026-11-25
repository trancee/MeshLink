package ch.trancee.meshlink.internal

import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

@OptIn(ExperimentalCoroutinesApi::class)
class AppleRadioDiscoveryTest {
    private val bluetooth = FakeCoreBluetooth()
    private val radio = AppleRadio(bluetooth).also { bluetooth.events = FakeEvents(it.delegates) }
    private val payload = ByteArray(RadioPort.ADVERTISEMENT_LENGTH) { it.toByte() }
    private val payloadHex = payload.toHexString()
    private val other = FakePeripheral("B")

    private fun TestScope.events(): List<RadioEvent> {
        val seen = mutableListOf<RadioEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { radio.events.toList(seen) }
        return seen
    }

    @Test
    fun advertisingStopsAnyEarlierAdvertisementAndAdvertisesTheMeshServiceAndThePayload() {
        // Act
        val result = radio.startAdvertising(payload)

        // Assert
        assertIs<RadioResult.Ok<Unit>>(result)
        assertEquals(listOf("stopAdvertising", "advertise 4d455348,$payloadHex"), bluetooth.calls)
    }

    @Test
    fun advertisingRefusesAPayloadOfAnyOtherLength() {
        // Act
        val result = radio.startAdvertising(ByteArray(RadioPort.ADVERTISEMENT_LENGTH - 1))

        // Assert
        assertEquals(RadioFailure.INVALID_ARGUMENT, assertIs<RadioResult.Failed>(result).reason)
        assertEquals(emptyList(), bluetooth.calls)
    }

    @Test
    fun advertisingThatFailsToStartIsAnAdvertisingFailure() =
        runTest {
            // Arrange
            val seen = events()
            radio.startAdvertising(payload)

            // Act
            bluetooth.events.advertisingStarted(ok = false)

            // Assert
            assertEquals(RadioFailure.RADIO_ERROR, assertIs<RadioEvent.AdvertisingFailed>(seen.single()).reason)
        }

    @Test
    fun advertisingThatStartsOrFailsAfterItStoppedReportsNothing() =
        runTest {
            // Arrange
            val seen = events()
            radio.startAdvertising(payload)

            // Act
            bluetooth.events.advertisingStarted(ok = true)
            radio.stopAdvertising()
            bluetooth.events.advertisingStarted(ok = false)

            // Assert
            assertEquals(emptyList(), seen)
            assertEquals("stopAdvertising", bluetooth.calls.last())
        }

    @Test
    fun scanningLooksForTheMeshService() {
        // Act
        val result = radio.startScanning()

        // Assert
        assertIs<RadioResult.Ok<Unit>>(result)
        assertEquals(listOf("scan 4d455348"), bluetooth.calls)
    }

    @Test
    fun aSightingCarriesThePayloadUuidThePeerAndTheSignalStrength() =
        runTest {
            // Arrange
            val seen = events()
            radio.startScanning()

            // Act
            bluetooth.events.discovered(FakePeripheral("A"), listOf(AppleGatt.MESH_SHORT, payload), rssi = -60)

            // Assert
            val sighting = assertIs<RadioEvent.Sighted>(seen.single()).sighting
            assertContentEquals(payload, sighting.payload)
            assertEquals(ApplePeer(FakePeripheral("A")), sighting.peer)
            assertEquals(-60, sighting.rssi)
        }

    @Test
    fun theFullFormOfTheMeshServiceIsNotAPayload() =
        runTest {
            // Arrange
            val seen = events()
            radio.startScanning()

            // Act
            bluetooth.events.discovered(other, listOf(AppleGatt.MESH_FULL, payload), rssi = -60)

            // Assert
            assertContentEquals(payload, assertIs<RadioEvent.Sighted>(seen.single()).sighting.payload)
        }

    @Test
    fun aMeshSightingWithoutAPayloadIsASightingWithoutPayload() =
        runTest {
            // Arrange
            val seen = events()
            radio.startScanning()

            // Act
            bluetooth.events.discovered(other, listOf(AppleGatt.MESH_SHORT), rssi = -60)
            bluetooth.events.discovered(other, listOf(AppleGatt.MESH_FULL), rssi = -61)
            bluetooth.events.discovered(other, emptyList(), rssi = -62)

            // Assert
            val sightings = seen.map { assertIs<RadioEvent.Sighted>(it).sighting }
            assertEquals(listOf(null, null, null), sightings.map { it.payload })
            assertEquals(listOf(-60, -61, -62), sightings.map { it.rssi })
            assertEquals(setOf(ApplePeer(other)), sightings.map { it.peer }.toSet())
        }

    @Test
    fun aCentralScannerRestartsTheScanWithinTenSeconds() =
        runTest {
            // Arrange
            val discovery = Discovery(SodiumCryptoProvider, MESH_ID, WireProfile.UNSECURED)
            val scanner = Scanner(radio, SystemClock, SeededRandom(1L), discovery)
            backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
                val recognition = Recognition(null, NeighborLists(SystemClock, backgroundScope) {}) { emptyMap() }
                scanner.run(LinkRole.CENTRAL, recognition, coc = false, onLinkEvent = {}, onSighting = { _, _, _ -> })
            }
            runCurrent()

            // Act
            advanceTimeBy(BACKGROUND_RESTART_MILLIS)

            // Assert
            assertEquals(listOf("scan 4d455348", "stopScan", "scan 4d455348"), bluetooth.calls)
        }

    @Test
    fun anAdvertisementThatArrivesAfterScanningStoppedIsNoSighting() =
        runTest {
            // Arrange
            val seen = events()
            radio.startScanning()

            // Act
            radio.stopScanning()
            bluetooth.events.discovered(other, listOf(payload), rssi = -60)

            // Assert
            assertEquals(emptyList(), seen)
            assertEquals("stopScan", bluetooth.calls.last())
        }

    @Test
    fun peersAreTheSameWhenTheirPeripheralIdentifiersAre() {
        // Assert
        assertEquals(ApplePeer(FakePeripheral("A")).hashCode(), ApplePeer(FakePeripheral("A")).hashCode())
        assertNotEquals(ApplePeer(FakePeripheral("A")), ApplePeer(other))
        assertNotEquals<Any>(ApplePeer(other), other)
    }

    private companion object {
        val MESH_ID = "Festival".encodeToByteArray()

        /** ADR 0010 decision 6: background iOS scans restart at least this often. */
        const val BACKGROUND_RESTART_MILLIS = 10_000L
    }
}
