package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.SendResult
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import ch.trancee.meshlink.meshLinkConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Delivery receipts end to end (spec "Delivery receipts"; ADR 0013). */
@OptIn(ExperimentalCoroutinesApi::class)
class DeliveryReceiptsTest {
    @Test
    fun fortyMessagesFiveHopsAwayAreDeliveredByBatchedReceiptsAndNeverResent() =
        runTest(timeout = 5.minutes) {
            // Arrange: six nodes in a line, each in range of its neighbors only.
            val mesh = SeededMesh(this, SEED, List(LINE) { it * SPACING to 0.0 })
            mesh.start()
            advanceTimeBy(SETTLE_MILLIS)
            val f = checkNotNull(mesh.idOf(LINE - 1))
            val statuses = List(MESSAGES) { mutableListOf<Pair<Long, DeliveryStatus>>() }

            // Act
            statuses.forEachIndexed { index, seen ->
                track(mesh.nodes.first().send(f, byteArrayOf(index.toByte())), seen)
            }
            advanceTimeBy(DELIVERED_MILLIS)

            // Assert
            assertEquals(List(MESSAGES) { DeliveryStatus.Delivered }, statuses.map { it.last().second })
            val receipts =
                mesh.platforms
                    .last()
                    .radio.ends
                    .flatMap(::timedFrames)
                    .filter { it.second[0] == RECEIPT_TYPE }
            assertTrue(receipts.all { it.second[COUNT_OFFSET] in 1..DeliveryReceipt.TOMBSTONES_MAX })
            val firstAcknowledged = linkedMapOf<List<Byte>, Long>()
            receipts.forEach { (at, frame) -> ids(frame).forEach { firstAcknowledged.getOrPut(it) { at } } }
            assertEquals(MESSAGES, firstAcknowledged.size)
            val taken = mesh.inboxes.last().map { TestClock.WALL_CLOCK_START_MILLIS + it.atMillis }
            val lags = firstAcknowledged.values.zip(taken) { acknowledged, take -> acknowledged - take }
            assertTrue(lags.all { it in 0..BATCH_DELAY_MILLIS }, "receipt lags $lags")
            val delivered = statuses.maxOf { it.last().first }
            val resent =
                mesh.platforms
                    .first()
                    .radio.ends
                    .flatMap(::timedFrames)
                    .filter { (at, frame) -> at > delivered && frame[0] == MESSAGE_TYPE }
            assertEquals(0, resent.size)
        }

    @Test
    fun aRepeatedCopyOfATakenMessageIsDeliveredOnceAndAcknowledgedAgain() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val node = node(alice).also { it.start() }
            val inbox = inbox(node)
            val fake = connectFake(air, alice)
            fake.write(record(LinkFrame.PlainHello(1, digest(MESH), FAKE_ID)))
            advanceTimeBy(SETTLE_MILLIS)
            grantCredits(fake, alice.radio.ends.last())
            val sender = UnsecuredMessaging(SodiumCryptoProvider, FAKE_ID)
            val draft = sender.draft(nodeId(alice), PAYLOAD, (nowSeconds() + MINUTE).toUInt())
            val message = record(sender.frame(draft, Derivations.epoch(nowSeconds()), hopLimit = 1))
            fake.write(message)
            advanceTimeBy(SETTLE_MILLIS)

            // Act
            fake.write(message)
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(1, inbox.size)
            val receipts = timedFrames(alice.radio.ends.last()).map { it.second }.filter { it[0] == RECEIPT_TYPE }
            val acknowledged = listOf(draft.messageId.toList())
            assertEquals(listOf(acknowledged, acknowledged), receipts.map(::ids))
        }

    @Test
    fun aReceiptWithinTheSkewAllowanceAfterExpiryStillDelivers() =
        runTest(timeout = 5.minutes) {
            // Arrange: the fake withholds its receipt until 10 s after the message's expiry.
            val peer = linkedFake()
            val status = mutableListOf<Pair<Long, DeliveryStatus>>()
            track(peer.alice.send(peer.fakeId, PAYLOAD), status)
            advanceTimeBy(SETTLE_MILLIS)
            val delivery = peer.received()
            advanceTimeBy((delivery.expiry.toLong() + LATE_SECONDS) * MILLIS - wallClockMillis())

            // Act
            peer.acknowledge(delivery)
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(DeliveryStatus.Delivered, status.last().second)
        }

    @Test
    fun anUnacknowledgedMessageExpiresOnlyOnceTheSkewAllowancePasses() =
        runTest(timeout = 5.minutes) {
            // Arrange
            val peer = linkedFake()
            val status = mutableListOf<Pair<Long, DeliveryStatus>>()
            track(peer.alice.send(peer.fakeId, PAYLOAD), status)
            advanceTimeBy(SETTLE_MILLIS)
            val expiry = peer.received().expiry.toLong()

            // Act
            advanceTimeBy((expiry + CLOCK_SKEW_SECONDS) * MILLIS - wallClockMillis() + SETTLE_MILLIS)

            // Assert
            val (expiredAt, last) = status.last()
            assertEquals(DeliveryStatus.Expired, last)
            assertTrue(expiredAt >= (expiry + CLOCK_SKEW_SECONDS) * MILLIS, "expired at $expiredAt")
        }

    @Test
    fun malformedOrForeignReceiptsLeaveAMessageSentUntilAValidOneArrives() =
        runTest(timeout = 5.minutes) {
            // Arrange: the first lists in its tombstone the message's ID but in MESSAGE_IDS another.
            val peer = linkedFake()
            val status = mutableListOf<Pair<Long, DeliveryStatus>>()
            track(peer.alice.send(peer.fakeId, PAYLOAD), status)
            advanceTimeBy(SETTLE_MILLIS)
            val delivery = peer.received()
            val valid = peer.receipt(listOf(delivery.tombstone), delivery.expiry)
            val unknown = Tombstone(ByteArray(MESSAGE_ID) { 1 }, delivery.tombstone.k)
            val invalid =
                listOf(
                    peer.receipt(listOf(unknown), delivery.expiry).also {
                        delivery.tombstone.messageId.copyInto(it, COUNT_OFFSET + 1)
                    },
                    valid.copyOf().also { it[COUNT_OFFSET] = 0 },
                    valid.copyOf().also { it[COUNT_OFFSET] = (DeliveryReceipt.TOMBSTONES_MAX + 1).toByte() },
                    peer.receipt(listOf(unknown), delivery.expiry),
                    peer.initiatorReceipt(valid),
                )

            // Act
            invalid.forEach { peer.fake.write(record(it)) }
            advanceTimeBy(SETTLE_MILLIS)
            val afterInvalid = status.last().second
            peer.fake.write(record(valid))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(DeliveryStatus.Sent, afterInvalid)
            assertEquals(DeliveryStatus.Delivered, status.last().second)
        }

    /** Alice, a started short-lived node, linked to a fake peer that grants credits and sends nothing unasked. */
    private suspend fun TestScope.linkedFake(): LinkedFake {
        val air = air()
        val platform = air.platform(StandardTestDispatcher(testScheduler))
        val alice = shortLivedNode(platform).also { it.start() }
        val fake = connectFake(air, platform)
        fake.write(record(LinkFrame.PlainHello(1, digest(MESH), FAKE_ID)))
        advanceTimeBy(SETTLE_MILLIS)
        val end = platform.radio.ends.last()
        grantCredits(fake, end)
        val fakeId =
            alice.peers.value
                .single()
                .nodeId
        return LinkedFake(this, alice, nodeId(platform), fake, end, fakeId)
    }

    /** [alice], whose NodeId is [aliceId], linked over [end] to [fake], known to her as [fakeId]. */
    private class LinkedFake(
        private val scope: TestScope,
        val alice: MeshLink,
        private val aliceId: ByteArray,
        val fake: RadioLink,
        private val end: VirtualLinkEnd,
        val fakeId: NodeId,
    ) {
        private val messaging = UnsecuredMessaging(SodiumCryptoProvider, FAKE_ID)

        /** The single message Alice wrote to the fake. */
        fun received(): Delivery {
            val messages = timedFrames(end).map { it.second }.filter { it[0] == MESSAGE_TYPE }
            return checkNotNull(messaging.accept(messages.distinctBy { it.toList() }.single(), nowSeconds()))
        }

        /** A receipt from the fake to Alice for [tombstones] with [expiry]. */
        fun receipt(
            tombstones: List<Tombstone>,
            expiry: UInt,
        ): ByteArray = messaging.frame(messaging.receipt(aliceId, tombstones, expiry), epoch(), hopLimit = 1)

        /** [valid] with an `IK` initiator container in place of its `unsecured` one. */
        fun initiatorReceipt(valid: ByteArray): ByteArray =
            valid.copyOf(COUNT_OFFSET + 1 + Tombstone.LENGTH) + Container.IkInitiator(ByteArray(1)).encode()

        /** Writes the fake's receipt for [delivery]. */
        suspend fun acknowledge(delivery: Delivery) {
            fake.write(record(receipt(listOf(delivery.tombstone), delivery.expiry)))
        }

        private fun nowSeconds(): Long = (TestClock.WALL_CLOCK_START_MILLIS + scope.testScheduler.currentTime) / MILLIS

        private fun epoch(): UInt = Derivations.epoch(nowSeconds())
    }

    /** A stopped `unsecured` node whose messages live 120 s. */
    @OptIn(InternalMeshLinkApi::class)
    private fun shortLivedNode(platform: VirtualPlatform): MeshLink =
        MeshLink.create(
            meshLinkConfig(LINE_PRESET, SecurityProfile.Unsecured) { meshId = MESH }.build(),
            MeshPlatform.of(platform),
        )

    /** Every payload [node] receives from now on. */
    private fun TestScope.inbox(node: MeshLink): List<ByteArray> {
        val received = mutableListOf<ByteArray>()
        backgroundScope.launch { node.messages.collect { received += it.payload } }
        return received
    }

    /** Records into [seen] every status of the message [result] accepted, with its wall-clock time. */
    private fun TestScope.track(
        result: SendResult,
        seen: MutableList<Pair<Long, DeliveryStatus>>,
    ) {
        val message = assertIs<SendResult.Accepted>(result).message
        backgroundScope.launch { message.status.collect { seen += wallClockMillis() to it } }
    }

    private fun TestScope.wallClockMillis(): Long = TestClock.WALL_CLOCK_START_MILLIS + testScheduler.currentTime

    private fun TestScope.nowSeconds(): Long = wallClockMillis() / MILLIS

    private companion object {
        val PAYLOAD = "receipted".encodeToByteArray()
        const val SEED = 47L
        const val LINE = 6
        const val SPACING = 25.0
        const val MESSAGES = 40
        const val DELIVERED_MILLIS = 120_000L
        const val BATCH_DELAY_MILLIS = 500L
        const val MINUTE = 60L
        const val MILLIS = 1_000L
        const val LATE_SECONDS = 10L
        const val CLOCK_SKEW_SECONDS = 30L
        const val MESSAGE_ID = 12

        /** A receipt's tombstone count follows its unicast header; its tombstones follow the count. */
        const val COUNT_OFFSET = UnicastHeader.LENGTH

        val RECEIPT_TYPE = DeliveryReceipt.TYPE.toByte()
        val MESSAGE_TYPE = MessageFrame.TYPE.toByte()

        /** The message IDs [receipt] acknowledges. */
        fun ids(receipt: ByteArray): List<List<Byte>> =
            List(receipt[COUNT_OFFSET].toInt()) {
                val at = COUNT_OFFSET + 1 + it * Tombstone.LENGTH
                receipt.copyOfRange(at, at + MESSAGE_ID).toList()
            }

        /** The frames [end] wrote, each reassembled from its `FRAGMENT`s, with the time its last record was written. */
        fun timedFrames(end: VirtualLinkEnd): List<Pair<Long, ByteArray>> {
            val reader = RecordReader()
            val timed =
                end.writes.flatMap { (at, bytes) ->
                    assertIs<Decoded.Valid<List<ByteArray>>>(reader.read(bytes)).value.map { at to it }
                }
            val frames = timed.map { it.second }.reassembled()
            val ends = timed.filter { (_, body) -> body.isLastOfFrame() }.map { it.first }
            return ends.zip(frames)
        }

        private fun ByteArray.isLastOfFrame(): Boolean {
            val fragment = (LinkFrame.decode(this) as? Decoded.Valid)?.value as? LinkFrame.Fragment
            return fragment?.last ?: true
        }
    }
}
