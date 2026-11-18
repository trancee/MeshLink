package ch.trancee.meshlink.internal

import kotlinx.coroutines.Deferred
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppleRadioChannelTest {
    private val bluetooth = FakeCoreBluetooth()
    private val radio = AppleRadio(bluetooth).also { bluetooth.events = FakeEvents(it.delegates) }
    private val channel = FakeChannel("C")

    private fun TestScope.events(): List<RadioEvent> {
        val seen = mutableListOf<RadioEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { radio.events.toList(seen) }
        return seen
    }

    private fun <T> TestScope.start(block: suspend () -> T): Deferred<T> {
        val now = UnconfinedTestDispatcher(testScheduler)
        return async(now) { block() }
    }

    private fun TestScope.chunks(stream: RadioStream): Deferred<List<ByteArray>> = start { stream.incoming.toList() }

    private fun failure(result: RadioResult<*>): RadioFailure = assertIs<RadioResult.Failed>(result).reason

    private suspend fun TestScope.listening(): Int {
        bluetooth.events.peripheralState(CbState.POWERED_ON)
        val listening = start { radio.listenForChannels() }
        bluetooth.events.channelPublished(PSM)
        return assertIs<RadioResult.Ok<Int>>(listening.await()).value
    }

    private suspend fun TestScope.accepted(): RadioStream {
        val seen = events()
        listening()
        bluetooth.events.channelOpened(channel)
        return assertIs<RadioEvent.ChannelOpened>(seen.single()).channel
    }

    @Test
    fun listeningPublishesAnUnencryptedChannelAndReturnsItsPsm() =
        runTest {
            // Act
            val psm = listening()

            // Assert
            assertEquals(PSM, psm)
            assertEquals(listOf("publishChannel"), bluetooth.calls)
        }

    @Test
    fun listeningAgainReturnsTheSamePsmWithoutPublishingAgain() =
        runTest {
            // Arrange
            listening()

            // Act
            val again = radio.listenForChannels()

            // Assert
            assertEquals(PSM, assertIs<RadioResult.Ok<Int>>(again).value)
            assertEquals(listOf("publishChannel"), bluetooth.calls)
        }

    @Test
    fun listeningBeforeThePeripheralManagerReportsAStateFailsAtOnce() =
        runTest {
            // Act
            val result = radio.listenForChannels()

            // Assert
            assertEquals(RadioFailure.RADIO_ERROR, failure(result))
            assertTrue(bluetooth.calls.isEmpty())
        }

    @Test
    fun listeningBeforeThePeripheralManagerIsOnFailsAtOnce() =
        runTest {
            // Arrange
            bluetooth.events.peripheralState(CbState.POWERED_OFF)

            // Act
            val result = radio.listenForChannels()

            // Assert
            assertEquals(RadioFailure.RADIO_ERROR, failure(result))
            assertTrue(bluetooth.calls.isEmpty())
        }

    @Test
    fun aFailedPublishFailsWithARadioErrorAndTheNextListenPublishesAgain() =
        runTest {
            // Arrange
            bluetooth.events.peripheralState(CbState.POWERED_ON)
            val listening = start { radio.listenForChannels() }

            // Act
            bluetooth.events.channelPublished(null)
            val psm = listening()

            // Assert
            assertEquals(RadioFailure.RADIO_ERROR, failure(listening.await()))
            assertEquals(PSM, psm)
            assertEquals(listOf("publishChannel", "publishChannel"), bluetooth.calls)
        }

    @Test
    fun stoppingUnpublishesTheChannelOnce() =
        runTest {
            // Arrange
            listening()

            // Act
            radio.stopListening()
            radio.stopListening()

            // Assert
            assertEquals(listOf("publishChannel", "unpublishChannel $PSM"), bluetooth.calls)
        }

    @Test
    fun aChannelPublishedAfterItsListenerWasCancelledIsUnpublished() =
        runTest {
            // Arrange
            bluetooth.events.peripheralState(CbState.POWERED_ON)
            start { radio.listenForChannels() }.cancel()

            // Act
            bluetooth.events.channelPublished(null)
            bluetooth.events.channelPublished(PSM)

            // Assert
            assertEquals(listOf("publishChannel", "unpublishChannel $PSM"), bluetooth.calls)
        }

    @Test
    fun aChannelFromACentralArrivesWithThePeerOfItsGattLink() =
        runTest {
            // Arrange
            val seen = events()
            listening()
            radio.serveCapability(byteArrayOf(1))
            bluetooth.events.subscribed(FakeCentral("C"), AppleGatt.TO_CENTRAL)

            // Act
            bluetooth.events.channelOpened(null)
            bluetooth.events.channelOpened(channel)

            // Assert
            val link = assertIs<RadioEvent.Connected>(seen.first()).link
            val opened = assertIs<RadioEvent.ChannelOpened>(seen.single { it is RadioEvent.ChannelOpened }).channel
            assertEquals(link.peer, opened.peer)
            assertEquals(2, seen.size)
            assertTrue(channel.events != null)
        }

    @Test
    fun bluetoothTurningOffForgetsThePsmAndClosesAcceptedChannels() =
        runTest {
            // Arrange
            val stream = accepted()
            val chunks = chunks(stream)

            // Act
            bluetooth.events.peripheralState(CbState.POWERED_OFF)
            radio.stopListening()
            val psm = listening()

            // Assert
            assertTrue(chunks.await().isEmpty())
            assertEquals(1, channel.closed)
            assertEquals(PSM, psm)
            assertEquals(listOf("publishChannel", "publishChannel"), bluetooth.calls)
        }

    @Test
    fun bluetoothTurningOffWhilePublishingFailsTheListen() =
        runTest {
            // Arrange
            bluetooth.events.peripheralState(CbState.POWERED_ON)
            val listening = start { radio.listenForChannels() }

            // Act
            bluetooth.events.peripheralState(CbState.POWERED_OFF)

            // Assert
            assertEquals(RadioFailure.RADIO_ERROR, failure(listening.await()))
        }

    @Test
    fun aChannelClosedBeforeBluetoothTurnsOffIsClosedOnce() =
        runTest {
            // Arrange
            val stream = accepted()

            // Act
            stream.disconnect()
            stream.disconnect()
            bluetooth.events.peripheralState(CbState.POWERED_OFF)

            // Assert
            assertEquals(1, channel.closed)
        }

    @Test
    fun theChannelDeliversEveryAvailableChunkInOrder() =
        runTest {
            // Arrange
            val stream = accepted()
            val chunks = chunks(stream)
            channel.readable += byteArrayOf(1, 2)
            channel.readable += byteArrayOf(3)

            // Act
            channel.events!!.readable()
            stream.disconnect()

            // Assert
            assertEquals(listOf(listOf<Byte>(1, 2), listOf<Byte>(3)), chunks.await().map { it.toList() })
        }

    @Test
    fun theEndOfTheInputClosesTheChannel() =
        runTest {
            // Arrange
            val stream = accepted()
            val chunks = chunks(stream)
            channel.readable += byteArrayOf(1)
            channel.readable += ByteArray(0)
            channel.readable += byteArrayOf(2)

            // Act
            channel.events!!.readable()

            // Assert
            assertEquals(listOf(listOf<Byte>(1)), chunks.await().map { it.toList() })
            assertEquals(1, channel.closed)
            assertEquals(1, channel.readable.size)
        }

    @Test
    fun anEndOrErrorOnAStreamClosesTheChannel() =
        runTest {
            // Arrange
            val stream = accepted()
            val chunks = chunks(stream)

            // Act
            channel.events!!.ended()

            // Assert
            assertTrue(chunks.await().isEmpty())
            assertEquals(1, channel.closed)
        }

    @Test
    fun aClosedChannelReadsNothingMore() =
        runTest {
            // Arrange
            val stream = accepted()
            stream.disconnect()
            channel.readable += byteArrayOf(1)

            // Act
            channel.events!!.readable()

            // Assert
            assertEquals(1, channel.readable.size)
        }

    @Test
    fun aWriteGoesWholeAcrossPartialStreamWrites() =
        runTest {
            // Arrange
            val stream = accepted()
            channel.writeLimit = 2

            // Act
            val result = stream.write(byteArrayOf(0, 1, 2, 3, 4))

            // Assert
            assertIs<RadioResult.Ok<Unit>>(result)
            val expected = listOf(listOf<Byte>(0, 1), listOf<Byte>(2, 3), listOf<Byte>(4))
            assertEquals(expected, channel.written.map { it.toList() })
        }

    @Test
    fun aWriteWaitsUntilTheStreamHasSpace() =
        runTest {
            // Arrange
            val stream = accepted()
            channel.hasSpace = false
            val writing = start { stream.write(byteArrayOf(7)) }
            channel.events!!.writable()
            val waited = writing.isActive

            // Act
            channel.hasSpace = true
            channel.events!!.writable()

            // Assert
            assertTrue(waited)
            assertIs<RadioResult.Ok<Unit>>(writing.await())
            assertContentEquals(byteArrayOf(7), channel.written.single())
        }

    @Test
    fun aFailedWriteClosesTheChannel() =
        runTest {
            // Arrange
            val stream = accepted()
            val chunks = chunks(stream)
            channel.writeFails = true

            // Act
            val result = stream.write(byteArrayOf(7))

            // Assert
            assertEquals(RadioFailure.LINK_CLOSED, failure(result))
            assertTrue(chunks.await().isEmpty())
            assertEquals(1, channel.closed)
        }

    @Test
    fun writingToAClosedChannelFails() =
        runTest {
            // Arrange
            val stream = accepted()
            stream.disconnect()

            // Act
            val result = stream.write(ByteArray(0))

            // Assert
            assertEquals(RadioFailure.LINK_CLOSED, failure(result))
            assertTrue(channel.written.isEmpty())
        }

    @Test
    fun closingTheChannelFailsAWriteWaitingForSpace() =
        runTest {
            // Arrange
            val stream = accepted()
            channel.hasSpace = false
            val writing = start { stream.write(byteArrayOf(7)) }

            // Act
            stream.disconnect()

            // Assert
            assertEquals(RadioFailure.LINK_CLOSED, failure(writing.await()))
            assertTrue(channel.written.isEmpty())
        }

    private companion object {
        const val PSM = 0x0081
    }
}
