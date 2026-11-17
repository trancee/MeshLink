package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.ReceivedMessage
import ch.trancee.meshlink.SendResult
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Frames longer than a record travel as `FRAGMENT` streams; a full receiver answers `BUSY` (spec "Fragments"). */
@OptIn(ExperimentalCoroutinesApi::class)
class FragmentLinkTest {
    @Test
    fun messagesOfOneOrSeveralRecordsArriveWhole() =
        runTest {
            // Arrange
            val air = air()
            val platforms = List(2) { air.platform(StandardTestDispatcher(testScheduler)) }
            val nodes = platforms.map { node(it).also { node -> node.start() } }
            advanceTimeBy(SETTLE_MILLIS)
            val received = mutableListOf<ReceivedMessage>()
            backgroundScope.launch { nodes[1].messages.collect { received += it } }
            val bob =
                nodes[0]
                    .peers.value
                    .single()
                    .nodeId
            val payloads = SIZES.map { size -> ByteArray(size) { (it % PATTERN).toByte() } }

            // Act
            val statuses = payloads.map { status(nodes[0].send(bob, it)) }
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(payloads.map { it.toList() }, received.map { it.payload.toList() })
            assertEquals(List(SIZES.size) { DeliveryStatus.Sent }, statuses.map { it.value })
        }

    @Test
    fun aFrameThatFitsTakesOneRecordAndALongerOneAStreamOfFragments() =
        runTest {
            // Arrange
            val (node, end) = linkedToFake()
            val fake =
                node.peers.value
                    .single()
                    .nodeId

            // Act
            node.send(fake, ByteArray(SMALL))
            node.send(fake, ByteArray(UnsecuredMessaging.PAYLOAD_MAX))
            node.send(fake, ByteArray(UnsecuredMessaging.PAYLOAD_MAX))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            val bodies = bodies(end).drop(1)
            assertEquals(MESSAGE_TYPE, bodies[0][0])
            assertTrue(bodies.all { it.size <= LinkRecord.BODY_MAX })
            val streams = streams(end)
            assertEquals(listOf(0, 1), streams.keys.toList())
            streams.values.forEach { fragments ->
                assertEquals(fragments.indices.toList(), fragments.map { it.number })
                assertEquals(fragments.indices.map { it == fragments.lastIndex }, fragments.map { it.last })
            }
            val payloads = (listOf(bodies[0]) + streams.values.map { join(it) }).map { accept(it).size }
            assertEquals(listOf(SMALL, UnsecuredMessaging.PAYLOAD_MAX, UnsecuredMessaging.PAYLOAD_MAX), payloads)
        }

    @Test
    fun interleavedStreamsAndAFrameMaxStreamReassembleOnAnOpenLink() =
        runTest {
            // Arrange
            val linked = linkedToFake()
            val (node, end, fake) = linked
            val received = mutableListOf<ReceivedMessage>()
            backgroundScope.launch { node.messages.collect { received += it } }
            val first = LinkFrame.Fragment.split(FIRST, draft(nodeId(linked.platform), FIRST_PAYLOAD), SMALL)
            val second = LinkFrame.Fragment.split(SECOND, draft(nodeId(linked.platform), SECOND_PAYLOAD), SMALL)
            val largest = LinkFrame.Fragment.split(LARGE, routed(Reassembly.FRAME_MAX), LinkFrame.Fragment.DATA_MAX)

            // Act
            first.zip(second).forEach { (a, b) ->
                fake.write(record(a))
                fake.write(record(b))
            }
            largest.forEach { fake.write(record(it)) }
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(listOf(FIRST_PAYLOAD, SECOND_PAYLOAD), received.map { it.payload.decodeToString() })
            assertTrue(end.open)
            assertEquals(emptyList(), frames(end).drop(1))
        }

    @Test
    fun aFatalFragmentOrRecordClosesTheLinkAndFreesItsBudget() =
        runTest {
            // Link 0 holds an almost frame-max stream; the "above frame-max" case sends its last fragment.
            val held = LinkFrame.Fragment.split(STREAM, routed(Reassembly.FRAME_MAX + 1), LinkFrame.Fragment.DATA_MAX)
            val cases =
                mapOf(
                    "first not zero" to listOf(fragment(0, 1)),
                    "skipped number" to listOf(fragment(0, 0), fragment(0, 2)),
                    "after the last" to listOf(fragment(0, 0, last = true), fragment(0, 1)),
                    "nested" to listOf(LinkFrame.Fragment(0, 0, false, byteArrayOf(0x01, 0x00)).encode()),
                    "above frame-max" to listOf(held.last().encode()),
                    "truncated fragment" to listOf(byteArrayOf(0x01, 0x00, 0x00)),
                    "empty fragment" to listOf(byteArrayOf(0x01, 0x00, 0x00, 0x00)),
                    "short busy" to listOf(byteArrayOf(BUSY_TYPE, 0x00)),
                )
            cases.forEach { (case, bodies) ->
                // Arrange
                val air = air()
                val alice = air.platform(StandardTestDispatcher(testScheduler))
                val node = node(alice).also { it.start() }
                val fakes = List(FILLING_LINKS) { connectFake(air, alice) }
                // With a large stream held on each of the other links, the last link's stream does not fit.
                held.dropLast(1).forEach { fakes[0].write(record(it)) }
                fakes.drop(1).dropLast(1).forEach { fake ->
                    largeStream(STREAM).dropLast(1).forEach { fake.write(record(it)) }
                }
                advanceTimeBy(SETTLE_MILLIS)
                largeStream(STREAM).forEach { fakes.last().write(record(it)) }
                advanceTimeBy(SETTLE_MILLIS)

                // Act
                bodies.forEach { fakes[0].write(LinkRecord.encode(it)) }
                advanceTimeBy(SETTLE_MILLIS)
                largeStream(STREAM + 1).forEach { fakes.last().write(record(it)) }
                advanceTimeBy(SETTLE_MILLIS)

                // Assert
                val ends = alice.radio.ends
                val reasons = frames(ends[0]).map { (it as LinkFrame.Close).reason }
                assertEquals(listOf(CloseReason.PROTOCOL_ERROR), reasons, case)
                assertFalse(ends[0].open, case)
                assertEquals(listOf(STREAM to BusyReason.LARGE_BUDGET.code), busies(ends.last()), case)
                assertTrue(ends.last().open, case)
                node.close()
            }
        }

    @Test
    fun aFifthStreamOrASecondLargeStreamIsAnsweredBusyOnceAndTheLinkStaysOpen() =
        runTest {
            // Arrange
            val (_, end, fake) = linkedToFake()
            val large = { id: Int -> largeStream(id).dropLast(1) }

            // Act
            large(0).forEach { fake.write(record(it)) }
            large(1).forEach { fake.write(record(it)) }
            (2..Reassembly.SLOTS).forEach { fake.write(record(fragment(it, 0))) }
            fake.write(record(fragment(Reassembly.SLOTS + 1, 0)))
            fake.write(record(fragment(Reassembly.SLOTS + 1, 1)))
            fake.write(record(fragment(Reassembly.SLOTS + 1, 2, last = true)))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            val slots = BusyReason.REASSEMBLY_SLOTS.code
            assertEquals(listOf(1 to slots, Reassembly.SLOTS + 1 to slots), busies(end))
            assertTrue(end.open)
        }

    @Test
    fun aBusyWaitingForACreditLetsTheLinkReadTheGrant() =
        runTest {
            // Arrange
            val (_, end, fake) = linkedToFake(granting = false)
            (0..Reassembly.SLOTS).forEach { fake.write(record(fragment(it, 0))) }
            advanceTimeBy(SETTLE_MILLIS)
            val starved = busies(end)

            // Act
            fake.write(record(LinkFrame.Credit(CREDIT_WINDOW)))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(emptyList(), starved)
            assertEquals(listOf(Reassembly.SLOTS to BusyReason.REASSEMBLY_SLOTS.code), busies(end))
        }

    @Test
    fun aStreamIdleForTenSecondsIsDiscarded() =
        runTest {
            listOf(Reassembly.IDLE_TIMEOUT_MILLIS - 2 * SETTLE_MILLIS to true, Reassembly.IDLE_TIMEOUT_MILLIS to false)
                .forEach { (idle, open) ->
                    // Arrange
                    val (node, end, fake) = linkedToFake()
                    fake.write(record(fragment(0, 0)))
                    advanceTimeBy(SETTLE_MILLIS)

                    // Act
                    advanceTimeBy(idle)
                    fake.write(record(fragment(0, 1, last = true)))
                    advanceTimeBy(SETTLE_MILLIS)

                    // Assert
                    assertEquals(open, end.open, "$idle")
                    node.close()
                }
        }

    @Test
    fun aBusyAfterTheLastFragmentRetriesTheMessageWhateverItsReason() =
        runTest {
            // Arrange
            val (node, end, fake) = linkedToFake()
            val status =
                status(
                    node.send(
                        node.peers.value
                            .single()
                            .nodeId,
                        ByteArray(UnsecuredMessaging.PAYLOAD_MAX),
                    ),
                )
            advanceTimeBy(SETTLE_MILLIS)
            val sentFirst = status.value

            // Act
            fake.write(record(LinkFrame.Busy(0, UNKNOWN_REASON)))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            val streams = streams(end)
            assertEquals(DeliveryStatus.Sent, sentFirst)
            assertEquals(listOf(0, 1), streams.keys.toList())
            assertContentEquals(join(streams.getValue(0)), join(streams.getValue(1)))
            assertEquals(DeliveryStatus.Sent, status.value)
            assertTrue(end.open)
        }

    @Test
    fun aBusyWhileTheStreamIsWrittenStopsItAndTheMessageIsSentAgain() =
        runTest {
            // Arrange
            val air = VirtualAir(backgroundScope, TestClock(testScheduler), SeededRandom(1), PACED_LATENCY_MILLIS)
            val (node, end, fake) = linkedToFake(air)
            end.paced = true
            val status =
                status(
                    node.send(
                        node.peers.value
                            .single()
                            .nodeId,
                        ByteArray(UnsecuredMessaging.PAYLOAD_MAX),
                    ),
                )
            while (streams(end).isEmpty()) advanceTimeBy(1)

            // Act
            fake.write(record(LinkFrame.Busy(0, BusyReason.LARGE_BUDGET)))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            val streams = streams(end)
            assertEquals(listOf(0, 1), streams.keys.toList())
            assertFalse(streams.getValue(0).last().last)
            assertTrue(streams.getValue(1).last().last)
            assertEquals(DeliveryStatus.Sent, status.value)
        }

    @Test
    fun aBusyForAStreamTheLinkNoLongerTracksIsIgnored() =
        runTest {
            // Arrange
            val (node, end, fake) = linkedToFake()
            val peer =
                node.peers.value
                    .single()
                    .nodeId
            node.send(peer, ByteArray(UnsecuredMessaging.PAYLOAD_MAX))
            advanceTimeBy(SETTLE_MILLIS + Reassembly.IDLE_TIMEOUT_MILLIS)
            node.send(peer, ByteArray(UnsecuredMessaging.PAYLOAD_MAX))
            advanceTimeBy(SETTLE_MILLIS)

            // Act
            fake.write(record(LinkFrame.Busy(0, BusyReason.REASSEMBLY_SLOTS)))
            fake.write(record(LinkFrame.Busy(UNUSED_STREAM, BusyReason.REASSEMBLY_SLOTS)))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(listOf(0, 1), streams(end).keys.toList())
            assertTrue(end.open)
        }

    /**
     * A started node on [air] linked to a fake central that said hello as [FAKE_ID] and grants
     * credits if [granting].
     */
    private suspend fun TestScope.linkedToFake(
        air: VirtualAir = air(),
        granting: Boolean = true,
    ): Linked {
        val alice = air.platform(StandardTestDispatcher(testScheduler))
        val node = node(alice).also { it.start() }
        val fake = connectFake(air, alice)
        fake.write(record(LinkFrame.PlainHello(1, digest(MESH), FAKE_ID)))
        advanceTimeBy(SETTLE_MILLIS)
        if (granting) grantCredits(fake, alice.radio.ends.single())
        advanceTimeBy(SETTLE_MILLIS)
        return Linked(node, alice.radio.ends.single(), fake, alice)
    }

    private data class Linked(
        val node: MeshLink,
        val end: VirtualLinkEnd,
        val fake: RadioLink,
        val platform: VirtualPlatform,
    )

    private fun status(result: SendResult): StateFlow<DeliveryStatus> {
        val accepted = assertIs<SendResult.Accepted>(result)
        return accepted.message.status
    }

    /** A `MESSAGE` frame from [FAKE_ID] to [to] carrying [payload]. */
    private fun TestScope.draft(
        to: ByteArray,
        payload: String,
    ): ByteArray {
        val sender = UnsecuredMessaging(SodiumCryptoProvider, FAKE_ID)
        val expiry = (now().epochSeconds + LIFETIME_SECONDS).toUInt()
        val draft = sender.draft(to, payload.encodeToByteArray(), expiry, hopLimit = 1)
        return sender.frame(draft, Derivations.epoch(now().epochSeconds))
    }

    /** The payload of `MESSAGE` [frame] as the fake receives it. */
    private fun TestScope.accept(frame: ByteArray): ByteArray =
        assertNotNull(UnsecuredMessaging(SodiumCryptoProvider, FAKE_ID).accept(frame, now().epochSeconds)).payload

    private fun TestScope.now(): Instant {
        val millis = TestClock.WALL_CLOCK_START_MILLIS + testScheduler.currentTime
        return Instant.fromEpochMilliseconds(millis)
    }

    /** Fragment [number] of stream [id] carrying a routed type byte. */
    private fun fragment(
        id: Int,
        number: Int,
        last: Boolean = false,
    ): ByteArray = LinkFrame.Fragment(id, number, last, byteArrayOf(MESSAGE_TYPE, 0x00)).encode()

    private fun record(fragment: ByteArray): ByteArray = LinkRecord.encode(fragment)

    /** The fragments of a [LARGE_STREAM_BYTES] routed frame as stream [id]. */
    private fun largeStream(id: Int): List<LinkFrame.Fragment> =
        LinkFrame.Fragment.split(id, routed(LARGE_STREAM_BYTES), LinkFrame.Fragment.DATA_MAX)

    /** A routed frame of [size] bytes that no node accepts as a message. */
    private fun routed(size: Int): ByteArray = ByteArray(size).also { it[0] = MESSAGE_TYPE }

    private fun join(fragments: List<LinkFrame.Fragment>): ByteArray =
        fragments.fold(ByteArray(0)) { frame, fragment -> frame + fragment.data }

    private fun bodies(end: VirtualLinkEnd): List<ByteArray> =
        assertIs<Decoded.Valid<List<ByteArray>>>(RecordReader().read(end.written)).value

    /** The link-local frames [end] wrote, fragments aside. */
    private fun frames(end: VirtualLinkEnd): List<LinkFrame> =
        bodies(end)
            .filter { it[0] != MESSAGE_TYPE }
            .map { assertIs<Decoded.Valid<LinkFrame>>(LinkFrame.decode(it)).value }
            .filter { it !is LinkFrame.Fragment }

    /** The fragments [end] wrote, by stream in order of the stream's first fragment. */
    private fun streams(end: VirtualLinkEnd): Map<Int, List<LinkFrame.Fragment>> =
        bodies(end)
            .mapNotNull { (LinkFrame.decode(it) as? Decoded.Valid)?.value as? LinkFrame.Fragment }
            .groupBy { it.streamId }

    /** The `BUSY` replies [end] wrote as stream id and reason code. */
    private fun busies(end: VirtualLinkEnd): List<Pair<Int, Int>> =
        frames(end).filterIsInstance<LinkFrame.Busy>().map { it.streamId to it.code }

    private companion object {
        val SIZES = listOf(800, 1_500, UnsecuredMessaging.PAYLOAD_MAX)
        const val PATTERN = 251
        const val SMALL = 800
        const val MESSAGE_TYPE: Byte = 0x40
        const val BUSY_TYPE: Byte = 0x06
        const val LIFETIME_SECONDS = 1_800
        const val FIRST = 3
        const val SECOND = 9
        const val LARGE = 200
        const val FIRST_PAYLOAD = "first, interleaved"
        const val SECOND_PAYLOAD = "second, interleaved"
        const val STREAM = 7
        const val UNUSED_STREAM = 9
        const val UNKNOWN_REASON = 0x7F

        // 127 whole fragments and one byte: seven held streams and link 0's leave under 8 KiB of the budget.
        const val LARGE_STREAM_BYTES = 129_541

        // Link 0 holds a stream and fails; seven more hold one each; the last needs link 0's share.
        const val FILLING_LINKS = 9
        val PACED_LATENCY_MILLIS = 10..10
    }
}
