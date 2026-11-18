package ch.trancee.meshlink.internal

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppleRadioCentralTest {
    private val bluetooth = FakeCoreBluetooth()
    private val radio = AppleRadio(bluetooth).also { bluetooth.events = FakeEvents(it.delegates) }
    private val peripheral = FakePeripheral("A")
    private val peer = ApplePeer(peripheral)
    private val characteristics = listOf(AppleGatt.CAPABILITY, AppleGatt.TO_PERIPHERAL, AppleGatt.TO_CENTRAL)
    private val record = byteArrayOf(1, 2, 3)

    private fun TestScope.events(): List<RadioEvent> {
        val seen = mutableListOf<RadioEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { radio.events.toList(seen) }
        return seen
    }

    private fun <T> TestScope.start(block: suspend () -> T): Deferred<T> {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return async(dispatcher) { block() }
    }

    private fun TestScope.connecting(): Deferred<RadioResult<CentralLink>> = start { radio.connect(peer) }

    private fun discover(services: List<ByteArray>? = listOf(AppleGatt.SERVICE)) {
        bluetooth.events.connected(peripheral)
        bluetooth.events.servicesDiscovered(peripheral, services)
    }

    private suspend fun TestScope.linked(): CentralLink {
        val connecting = connecting()
        discover()
        bluetooth.events.characteristicsDiscovered(peripheral, characteristics)
        return assertIs<RadioResult.Ok<CentralLink>>(connecting.await()).value
    }

    private fun failure(result: RadioResult<*>): RadioFailure = assertIs<RadioResult.Failed>(result).reason

    @Test
    fun connectingConnectsThenDiscoversTheServiceAndItsCharacteristics() =
        runTest {
            // Act
            val link = linked()

            // Assert
            assertEquals(
                listOf(
                    "connect A",
                    "discoverServices A 4d4553484d4c4e4b8000000000000000",
                    "discoverCharacteristics A 3",
                ),
                bluetooth.calls,
            )
            assertEquals(peer, link.peer)
            assertEquals(bluetooth.writeLength + RadioPort.ATT_HEADER, link.attMtu)
        }

    @Test
    fun theRadioClaimsNoLe2MPhyAndTheLinkReadsNoRssi() =
        runTest {
            // Arrange
            val link = linked()

            // Act
            val rssi = link.readRssi()

            // Assert
            assertFalse(radio.supportsLe2MPhy)
            assertEquals(RadioFailure.UNSUPPORTED, failure(rssi))
        }

    @Test
    fun aPeerThatServesOnlyTheAdvertisedUuidLacksTheCapability() =
        runTest {
            // Arrange
            val connecting = connecting()

            // Act
            discover(listOf(AppleGatt.MESH_SHORT, AppleGatt.MESH_FULL))

            // Assert
            assertEquals(RadioFailure.CAPABILITY_MISSING, failure(connecting.await()))
        }

    @Test
    fun aPeerOfAnotherRadioIsAnInvalidArgument() =
        runTest {
            // Act
            val result = radio.connect(AppleCentralPeer("C"))

            // Assert
            assertEquals(RadioFailure.INVALID_ARGUMENT, failure(result))
            assertEquals(emptyList(), bluetooth.calls)
        }

    @Test
    fun aFailedConnectionIsAnUnreachablePeer() =
        runTest {
            // Arrange
            val connecting = connecting()

            // Act
            bluetooth.events.connectFailed(peripheral)

            // Assert
            assertEquals(RadioFailure.PEER_UNREACHABLE, failure(connecting.await()))
        }

    @Test
    fun aConnectionThatTakesThirtySecondsIsCancelledAsAnUnreachablePeer() =
        runTest {
            // Arrange
            val connecting = connecting()

            // Act
            advanceTimeBy(30_000)
            val waiting = connecting.isActive
            advanceTimeBy(1)
            runCurrent()
            val given = connecting.isCompleted

            // Assert
            assertTrue(waiting)
            assertTrue(given)
            assertEquals(RadioFailure.PEER_UNREACHABLE, failure(connecting.await()))
            assertEquals("cancel A", bluetooth.calls.last())
        }

    @Test
    fun aFailedOrClosedDiscoveryIsAClosedLink() =
        runTest {
            // Arrange
            val seen = events()
            val failed = connecting()
            discover(services = null)
            val closed = connecting()
            bluetooth.events.connected(peripheral)

            // Act
            bluetooth.events.disconnected(peripheral)

            // Assert
            assertEquals(RadioFailure.LINK_CLOSED, failure(failed.await()))
            assertEquals(RadioFailure.LINK_CLOSED, failure(closed.await()))
            assertEquals(emptyList(), seen)
        }

    @Test
    fun aPeripheralWithoutTheServiceHasNoCapability() =
        runTest {
            // Arrange
            val connecting = connecting()

            // Act
            discover(listOf(AppleGatt.TO_CENTRAL))

            // Assert
            assertEquals(RadioFailure.CAPABILITY_MISSING, failure(connecting.await()))
            assertEquals("cancel A", bluetooth.calls.last())
        }

    @Test
    fun aFailedCharacteristicDiscoveryIsAClosedLink() =
        runTest {
            // Arrange
            val connecting = connecting()
            discover()

            // Act
            bluetooth.events.characteristicsDiscovered(peripheral, null)

            // Assert
            assertEquals(RadioFailure.LINK_CLOSED, failure(connecting.await()))
        }

    @Test
    fun aServiceWithoutEveryCharacteristicHasNoCapability() =
        runTest {
            // Arrange
            val connecting = connecting()
            discover()

            // Act
            bluetooth.events.characteristicsDiscovered(peripheral, characteristics.drop(1))

            // Assert
            assertEquals(RadioFailure.CAPABILITY_MISSING, failure(connecting.await()))
        }

    @Test
    fun cancellingTheConnectionCancelsItAtTheRadioAndFreesThePeer() =
        runTest {
            // Arrange
            val connecting = connecting()

            // Act
            connecting.cancel()
            val again = connecting()

            // Assert
            assertEquals(listOf("connect A", "cancel A", "connect A"), bluetooth.calls)
            assertTrue(again.isActive)
        }

    @Test
    fun aSecondConnectionToAConnectedPeerIsARadioError() =
        runTest {
            // Arrange
            linked()

            // Act
            val result = radio.connect(peer)

            // Assert
            assertEquals(RadioFailure.RADIO_ERROR, failure(result))
        }

    @Test
    fun readingTheCapabilityReadsItThenSubscribes() =
        runTest {
            // Arrange
            val link = linked()
            val reading = start { link.readCapability() }

            // Act
            bluetooth.events.valueUpdated(peripheral, AppleGatt.CAPABILITY, record)
            bluetooth.events.subscribeAnswered(peripheral, ok = true)

            // Assert
            assertContentEquals(record, assertIs<RadioResult.Ok<ByteArray>>(reading.await()).value)
            assertEquals(
                listOf(
                    "read A ${AppleGatt.CAPABILITY.toHexString()}",
                    "subscribe A ${AppleGatt.TO_CENTRAL.toHexString()}",
                ),
                bluetooth.calls.drop(3),
            )
        }

    @Test
    fun aFailedReadOrSubscriptionIsARadioError() =
        runTest {
            // Arrange
            val link = linked()
            val unread = start { link.readCapability() }
            bluetooth.events.valueUpdated(peripheral, AppleGatt.CAPABILITY, null)
            val unsubscribed = start { link.readCapability() }

            // Act
            bluetooth.events.valueUpdated(peripheral, AppleGatt.CAPABILITY, record)
            bluetooth.events.subscribeAnswered(peripheral, ok = false)

            // Assert
            assertEquals(RadioFailure.RADIO_ERROR, failure(unread.await()))
            assertEquals(RadioFailure.RADIO_ERROR, failure(unsubscribed.await()))
        }

    @Test
    fun readingTheCapabilityOfAClosedLinkIsAClosedLink() =
        runTest {
            // Arrange
            val link = linked()
            link.disconnect()

            // Act
            val result = link.readCapability()

            // Assert
            assertEquals(RadioFailure.LINK_CLOSED, failure(result))
        }

    @Test
    fun notificationsOfTheStreamArriveOnTheLink() =
        runTest {
            // Arrange
            val link = linked()
            val incoming = start { link.incoming.toList() }

            // Act
            bluetooth.events.valueUpdated(peripheral, AppleGatt.TO_CENTRAL, byteArrayOf(7))
            bluetooth.events.valueUpdated(peripheral, AppleGatt.TO_CENTRAL, null)
            bluetooth.events.valueUpdated(peripheral, AppleGatt.TO_PERIPHERAL, byteArrayOf(8))
            bluetooth.events.valueUpdated(FakePeripheral("B"), AppleGatt.TO_CENTRAL, byteArrayOf(9))
            bluetooth.events.disconnected(peripheral)

            // Assert
            assertEquals(listOf(listOf<Byte>(7)), incoming.await().map { it.toList() })
        }

    @Test
    fun aWriteIsWrittenWithoutResponseInChunksOfTheMtu() =
        runTest {
            // Arrange
            bluetooth.writeLength = 2
            val link = linked()

            // Act
            val result = link.write(byteArrayOf(1, 2, 3))

            // Assert
            assertIs<RadioResult.Ok<Unit>>(result)
            assertEquals(listOf("write A 0102", "write A 03"), bluetooth.calls.drop(3))
        }

    @Test
    fun aWriteWaitsUntilThePeripheralIsReadyForMore() =
        runTest {
            // Arrange
            val link = linked()
            bluetooth.writeQueueFull = true
            val writing = start { link.write(byteArrayOf(1)) }

            // Act
            bluetooth.events.readyToWrite(FakePeripheral("B"))
            bluetooth.events.readyToWrite(peripheral)
            val waiting = writing.isActive
            bluetooth.writeQueueFull = false
            bluetooth.events.readyToWrite(peripheral)

            // Assert
            assertTrue(waiting)
            assertIs<RadioResult.Ok<Unit>>(writing.await())
            assertEquals(listOf("01"), bluetooth.written.map { it.toHexString() })
        }

    @Test
    fun aWriteWaitingForThePeripheralFailsWhenTheLinkCloses() =
        runTest {
            // Arrange
            val link = linked()
            bluetooth.writeQueueFull = true
            val writing = start { link.write(byteArrayOf(1)) }

            // Act
            bluetooth.events.disconnected(peripheral)

            // Assert
            assertEquals(RadioFailure.LINK_CLOSED, failure(writing.await()))
        }

    @Test
    fun aDisconnectionClosesTheOpenLinkOnce() =
        runTest {
            // Arrange
            val seen = events()
            val link = linked()

            // Act
            bluetooth.events.disconnected(peripheral)
            bluetooth.events.disconnected(peripheral)

            // Assert
            assertSame(link, assertIs<RadioEvent.Disconnected>(seen.single()).link)
        }

    @Test
    fun disconnectingCancelsTheConnectionAndFreesThePeer() =
        runTest {
            // Arrange
            val seen = events()
            val link = linked()

            // Act
            link.disconnect()
            val again = connecting()

            // Assert
            assertIs<RadioEvent.Disconnected>(seen.single())
            assertEquals(listOf("cancel A", "connect A"), bluetooth.calls.drop(3))
            assertTrue(again.isActive)
        }

    @Test
    fun invalidatingTheMeshServiceClosesTheLink() =
        runTest {
            // Arrange
            val seen = events()
            linked()

            // Act
            bluetooth.events.servicesInvalidated(peripheral, listOf(AppleGatt.TO_CENTRAL, AppleGatt.MESH_SHORT))
            val kept = seen.isEmpty()
            bluetooth.events.servicesInvalidated(peripheral, listOf(AppleGatt.SERVICE))

            // Assert
            assertTrue(kept)
            assertIs<RadioEvent.Disconnected>(seen.single())
            assertEquals("cancel A", bluetooth.calls.last())
        }

    @Test
    fun aCentralManagerThatStopsClosesEveryLinkAndConnection() =
        runTest {
            // Arrange
            val seen = events()
            val link = linked()
            val other = FakePeripheral("B")
            val connecting = start { radio.connect(ApplePeer(other)) }

            // Act
            bluetooth.events.centralState(CbState.POWERED_ON)
            val kept = seen.isEmpty()
            bluetooth.events.centralState(CbState.POWERED_OFF)

            // Assert
            assertTrue(kept)
            assertSame(link, assertIs<RadioEvent.Disconnected>(seen.single()).link)
            assertEquals(RadioFailure.PEER_UNREACHABLE, failure(connecting.await()))
        }

    @Test
    fun eventsOfAPeripheralWithoutALinkChangeNothing() =
        runTest {
            // Arrange
            val seen = events()
            val stranger = FakePeripheral("B")

            // Act
            bluetooth.events.connected(stranger)
            bluetooth.events.connectFailed(stranger)
            bluetooth.events.servicesDiscovered(stranger, emptyList())
            bluetooth.events.servicesDiscovered(stranger, null)
            bluetooth.events.characteristicsDiscovered(stranger, emptyList())
            bluetooth.events.characteristicsDiscovered(stranger, null)
            bluetooth.events.valueUpdated(stranger, AppleGatt.CAPABILITY, byteArrayOf(1))
            bluetooth.events.valueUpdated(stranger, AppleGatt.CAPABILITY, null)
            bluetooth.events.valueUpdated(stranger, AppleGatt.TO_CENTRAL, byteArrayOf(2))
            bluetooth.events.subscribeAnswered(stranger, ok = true)
            bluetooth.events.subscribeAnswered(stranger, ok = false)
            bluetooth.events.readyToWrite(stranger)
            bluetooth.events.servicesInvalidated(stranger, listOf(AppleGatt.SERVICE))
            bluetooth.events.disconnected(stranger)

            // Assert
            assertEquals(emptyList(), seen)
            assertEquals(emptyList(), bluetooth.calls)
        }

    @Test
    fun anAnswerNobodyAskedForChangesNothing() =
        runTest {
            // Arrange
            val link = linked()

            // Act
            bluetooth.events.valueUpdated(peripheral, AppleGatt.CAPABILITY, record)
            bluetooth.events.subscribeAnswered(peripheral, ok = true)
            val reading = start { link.readCapability() }

            // Assert
            assertTrue(reading.isActive)
            reading.cancel()
        }
}
