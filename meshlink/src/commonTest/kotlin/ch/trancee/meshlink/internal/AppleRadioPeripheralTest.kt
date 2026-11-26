package ch.trancee.meshlink.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppleRadioPeripheralTest {
    private val bluetooth = FakeCoreBluetooth()
    private val radio = AppleRadio(bluetooth).also { bluetooth.events = FakeEvents(it.delegates) }
    private val record = byteArrayOf(1, 2, 3, 4)
    private val central = FakeCentral("C")

    private fun TestScope.events(): List<RadioEvent> {
        val seen = mutableListOf<RadioEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { radio.events.toList(seen) }
        return seen
    }

    private fun read(
        offset: Int,
        characteristic: ByteArray = AppleGatt.CAPABILITY,
    ): ByteArray? {
        bluetooth.events.readRequested(FakeReadRequest(central, characteristic, offset))
        return bluetooth.responses.last()
    }

    private fun subscribe() = bluetooth.events.subscribed(central, AppleGatt.TO_CENTRAL)

    private fun TestScope.linked(): Pair<RadioLink, List<RadioEvent>> {
        val seen = events()
        radio.serveCapability(record)
        subscribe()
        return assertIs<RadioEvent.Connected>(seen.single()).link to seen
    }

    @Test
    fun theRadioCapsPeripheralLinksAtThree() {
        // Act
        val cap = radio.peripheralLinkCap

        // Assert
        assertEquals(3, cap)
    }

    @Test
    fun servingPublishesTheMeshServiceOnce() {
        // Act
        val result = radio.serveCapability(record)
        radio.serveCapability(record)

        // Assert
        assertIs<RadioResult.Ok<Unit>>(result)
        assertEquals(listOf("addService 4d4553484d4c4e4b8000000000000000"), bluetooth.calls)
        val service = bluetooth.service!!
        assertContentEquals(AppleGatt.CAPABILITY, service.capability)
        assertContentEquals(AppleGatt.TO_PERIPHERAL, service.toPeripheral)
        assertContentEquals(AppleGatt.TO_CENTRAL, service.toCentral)
    }

    @Test
    fun servingRefusesARecordLargerThanTheCharacteristicHolds() {
        // Act
        val result = radio.serveCapability(ByteArray(RadioPort.CAPABILITY_RECORD_MAX + 1))

        // Assert
        assertEquals(RadioFailure.INVALID_ARGUMENT, assertIs<RadioResult.Failed>(result).reason)
        assertEquals(emptyList(), bluetooth.calls)
    }

    @Test
    fun aServiceThatFailsToPublishIsAFailureAndTheNextServePublishesItAgain() =
        runTest {
            // Arrange
            val seen = events()
            radio.serveCapability(record)

            // Act
            bluetooth.events.serviceAdded(CbError(code = 10))
            radio.serveCapability(record)

            // Assert
            val failed = assertIs<RadioEvent.ServeFailed>(seen.single())
            assertEquals(RadioFailure.RADIO_ERROR to 10, failed.reason to failed.platformCode)
            assertEquals(2, bluetooth.calls.count { it.startsWith("addService") })
        }

    @Test
    fun aPublishedServiceReportsNothing() =
        runTest {
            // Arrange
            val seen = events()
            radio.serveCapability(record)

            // Act
            bluetooth.events.serviceAdded(error = null)
            radio.serveCapability(record)

            // Assert
            assertEquals(emptyList(), seen)
            assertEquals(1, bluetooth.calls.count { it.startsWith("addService") })
        }

    @Test
    fun aCapabilityReadGetsTheLatestRecordFromItsOffset() {
        // Arrange
        radio.serveCapability(byteArrayOf(9))
        radio.serveCapability(record)

        // Act
        val whole = read(offset = 0)
        val tail = read(offset = 3)
        val end = read(offset = 4)

        // Assert
        assertContentEquals(record, whole)
        assertContentEquals(byteArrayOf(4), tail)
        assertContentEquals(byteArrayOf(), end)
    }

    @Test
    fun aReadPastTheRecordOrOfAnotherCharacteristicIsAnInvalidOffset() {
        // Arrange
        radio.serveCapability(record)

        // Act
        val past = read(offset = 5)
        val other = read(offset = 0, characteristic = AppleGatt.TO_CENTRAL)

        // Assert
        assertNull(past)
        assertNull(other)
    }

    @Test
    fun aSubscriptionOpensOneLinkWhoseMtuIsTheNotificationLengthPlusTheHeader() =
        runTest {
            // Arrange
            val (link, seen) = linked()

            // Act
            subscribe()
            bluetooth.events.subscribed(central, AppleGatt.CAPABILITY)

            // Assert
            assertEquals(1, seen.size)
            assertEquals(AppleCentralPeer("C"), link.peer)
            assertEquals(bluetooth.notifyLength + RadioPort.ATT_HEADER, link.attMtu)
        }

    @Test
    fun writesToTheStreamCharacteristicArriveOnTheLink() =
        runTest {
            // Arrange
            val (link, _) = linked()
            val incoming = async(UnconfinedTestDispatcher(testScheduler)) { link.incoming.toList() }

            // Act
            bluetooth.events.written(central, AppleGatt.TO_PERIPHERAL, byteArrayOf(7))
            bluetooth.events.written(central, AppleGatt.CAPABILITY, byteArrayOf(8))
            bluetooth.events.written(FakeCentral("D"), AppleGatt.TO_PERIPHERAL, byteArrayOf(9))
            link.disconnect()

            // Assert
            assertEquals(listOf(listOf<Byte>(7)), incoming.await().map { it.toList() })
        }

    @Test
    fun unsubscribingClosesTheLink() =
        runTest {
            // Arrange
            val (link, seen) = linked()

            // Act
            bluetooth.events.unsubscribed(central, AppleGatt.CAPABILITY)
            bluetooth.events.unsubscribed(central, AppleGatt.TO_CENTRAL)
            bluetooth.events.written(central, AppleGatt.TO_PERIPHERAL, byteArrayOf(7))

            // Assert
            assertSame(link, assertIs<RadioEvent.Disconnected>(seen.last()).link)
            assertEquals(emptyList(), link.incoming.toList())
        }

    @Test
    fun anUnsubscribeAfterTheLinkClosedReportsNothing() =
        runTest {
            // Arrange
            val (link, seen) = linked()
            link.disconnect()
            val before = seen.toList()

            // Act
            bluetooth.events.unsubscribed(central, AppleGatt.TO_CENTRAL)

            // Assert
            assertEquals(before, seen)
        }

    @Test
    fun aCentralThatSubscribesAgainAfterItsLinkClosedGetsANewLink() =
        runTest {
            // Arrange
            val (link, seen) = linked()
            link.disconnect()
            bluetooth.events.unsubscribed(central, AppleGatt.TO_CENTRAL)

            // Act
            bluetooth.events.subscribed(central, AppleGatt.TO_CENTRAL)

            // Assert
            assertNotSame(link, assertIs<RadioEvent.Connected>(seen.last()).link)
        }

    @Test
    fun disconnectingClosesTheLinkWithoutTouchingTheRadioBecauseAPeripheralCannot() =
        runTest {
            // Arrange
            val (link, seen) = linked()
            val before = bluetooth.calls.toList()

            // Act
            link.disconnect()

            // Assert
            assertIs<RadioEvent.Disconnected>(seen.last())
            assertEquals(before, bluetooth.calls)
        }

    @Test
    fun aCapabilityReadFromASubscribedCentralReplacesItsLink() =
        runTest {
            // Arrange
            val (old, seen) = linked()

            // Act
            read(offset = 0)

            // Assert
            assertSame(old, assertIs<RadioEvent.Disconnected>(seen[1]).link)
            val new = assertIs<RadioEvent.Connected>(seen[2]).link
            assertEquals(old.peer, new.peer)
            assertEquals(3, seen.size)
        }

    @Test
    fun aCapabilityReadFromASubscribedCentralReopensItsClosedLink() =
        runTest {
            // Arrange
            val (old, seen) = linked()
            old.disconnect()

            // Act
            read(offset = 0)

            // Assert
            assertIs<RadioEvent.Connected>(seen.last())
            assertEquals(3, seen.size)
        }

    @Test
    fun onlyAWholeReadFromASubscribedCentralOpensALink() =
        runTest {
            // Arrange
            val seen = events()
            radio.serveCapability(record)

            // Act
            read(offset = 0)
            subscribe()
            read(offset = 1)

            // Assert
            assertEquals(1, seen.size)
        }

    @Test
    fun aWriteIsNotifiedInChunksOfTheMtu() =
        runTest {
            // Arrange
            bluetooth.notifyLength = 2
            val (link, _) = linked()

            // Act
            val result = link.write(byteArrayOf(1, 2, 3, 4, 5))

            // Assert
            assertIs<RadioResult.Ok<Unit>>(result)
            assertEquals(listOf("0102", "0304", "05"), bluetooth.notified.map { it.toHexString() })
            assertTrue(bluetooth.calls.all { !it.startsWith("notify") || it.startsWith("notify C ") })
        }

    @Test
    fun aWriteWaitsWhileTheNotificationQueueIsFull() =
        runTest {
            // Arrange
            val (link, _) = linked()
            bluetooth.notifyQueueFull = true
            val writing = async(UnconfinedTestDispatcher(testScheduler)) { link.write(byteArrayOf(1)) }

            // Act
            bluetooth.events.readyToNotify()
            val waiting = writing.isActive
            bluetooth.notifyQueueFull = false
            bluetooth.events.readyToNotify()

            // Assert
            assertTrue(waiting)
            assertIs<RadioResult.Ok<Unit>>(writing.await())
            assertEquals(listOf("01"), bluetooth.notified.map { it.toHexString() })
        }

    @Test
    fun aWriteWaitingForTheQueueFailsWhenTheLinkCloses() =
        runTest {
            // Arrange
            val (link, _) = linked()
            bluetooth.notifyQueueFull = true
            val writing = async(UnconfinedTestDispatcher(testScheduler)) { link.write(byteArrayOf(1)) }

            // Act
            bluetooth.events.unsubscribed(central, AppleGatt.TO_CENTRAL)

            // Assert
            assertEquals(RadioFailure.LINK_CLOSED, assertIs<RadioResult.Failed>(writing.await()).reason)
        }

    @Test
    fun aPeripheralManagerThatStopsClosesEveryLinkAndForgetsTheService() =
        runTest {
            // Arrange
            val (link, seen) = linked()
            bluetooth.events.serviceAdded(error = null)

            // Act
            bluetooth.events.peripheralState(CbState.POWERED_OFF)
            read(offset = 0)
            radio.serveCapability(record)

            // Assert
            assertSame(link, assertIs<RadioEvent.Disconnected>(seen.last()).link)
            assertEquals(2, seen.size)
            assertEquals(2, bluetooth.calls.count { it.startsWith("addService") })
        }

    @Test
    fun aPeripheralManagerThatPowersOnKeepsItsLinks() =
        runTest {
            // Arrange
            val (_, seen) = linked()

            // Act
            bluetooth.events.peripheralState(CbState.POWERED_ON)

            // Assert
            assertEquals(1, seen.size)
            assertFalse(bluetooth.calls.any { it.startsWith("cancel") })
        }
}
