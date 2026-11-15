package ch.trancee.meshlink.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
    fun advertisingThatFailsToStartIsAFailure() =
        runTest {
            // Arrange
            val seen = events()
            radio.startAdvertising(payload)

            // Act
            bluetooth.events.advertisingStarted(ok = false)

            // Assert
            assertEquals(RadioFailure.RADIO_ERROR, assertIs<RadioEvent.Failed>(seen.single()).reason)
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
            bluetooth.events.discovered(FakePeripheral("A"), listOf(AppleGatt.SERVICE_SHORT, payload), rssi = -60)

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
            bluetooth.events.discovered(other, listOf(AppleGatt.SERVICE_FULL, payload), rssi = -60)

            // Assert
            assertContentEquals(payload, assertIs<RadioEvent.Sighted>(seen.single()).sighting.payload)
        }

    @Test
    fun anAdvertisementWithoutAPayloadIsNoSighting() =
        runTest {
            // Arrange
            val seen = events()
            radio.startScanning()

            // Act
            bluetooth.events.discovered(other, listOf(AppleGatt.SERVICE_SHORT), rssi = -60)
            bluetooth.events.discovered(other, listOf(AppleGatt.SERVICE_FULL), rssi = -60)

            // Assert
            assertEquals(emptyList(), seen)
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
}
