package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.internal.Container
import ch.trancee.meshlink.internal.Decoded
import ch.trancee.meshlink.internal.DeliveryReceipt
import ch.trancee.meshlink.internal.Derivations
import ch.trancee.meshlink.internal.LinkRecord
import ch.trancee.meshlink.internal.MessageContent
import ch.trancee.meshlink.internal.MessageFrame
import ch.trancee.meshlink.internal.ReadReceipt
import ch.trancee.meshlink.internal.Reassembly
import ch.trancee.meshlink.internal.ReceiptContent
import ch.trancee.meshlink.internal.Tlv
import ch.trancee.meshlink.internal.Tombstone
import ch.trancee.meshlink.internal.UnicastHeader
import ch.trancee.meshlink.internal.UnsecuredMessaging
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnsecuredMessagingTest {
    private val derivations = Derivations(SodiumCryptoProvider)
    private val alice = SodiumCryptoProvider.randomBytes(ID)
    private val bob = SodiumCryptoProvider.randomBytes(ID)
    private val sender = UnsecuredMessaging(SodiumCryptoProvider, alice)
    private val receiver = UnsecuredMessaging(SodiumCryptoProvider, bob)
    private val epoch = Derivations.epoch(NOW)

    @Test
    fun theDestinationAcceptsADraftedFrameWithTheSenderAndPayload() {
        // Arrange
        val draft = sender.draft(bob, PAYLOAD, EXPIRY)

        // Act
        val delivery = receiver.accept(sender.frame(draft, epoch, hopLimit = 1), NOW)

        // Assert
        assertNotNull(delivery)
        assertContentEquals(alice, delivery.sender)
        assertContentEquals(PAYLOAD, delivery.payload)
        assertContentEquals(draft.messageId, delivery.tombstone.messageId)
        assertContentEquals(draft.commitment, derivations.tombstoneCommitment(delivery.tombstone.k))
        assertEquals(EXPIRY, delivery.expiry)
    }

    @Test
    fun theSenderAcceptsAReceiptListingItsMessagesInOrder() {
        // Arrange
        val first = receiver.accept(sender.frame(sender.draft(bob, PAYLOAD, EXPIRY), epoch, hopLimit = 1), NOW)!!
        val second = receiver.accept(sender.frame(sender.draft(bob, PAYLOAD, EXPIRY + 9u), epoch, hopLimit = 1), NOW)!!
        val receipt = receiver.receipt(alice, listOf(first.tombstone, second.tombstone), EXPIRY + 9u)

        // Act
        val acknowledged = sender.acknowledged(receiver.frame(receipt, epoch, hopLimit = 3), NOW)

        // Assert
        assertNotNull(acknowledged)
        assertContentEquals(bob, acknowledged.sender)
        val expected = listOf(first, second).map { it.tombstone.messageId.toList() }
        assertEquals(expected, acknowledged.messageIds.map { it.toList() })
    }

    @Test
    fun aReceiptFrameCarriesFreshHeaderFieldsTheTombstonesAndMatchingMessageIds() {
        // Arrange
        val tombstones = listOf(Tombstone(MESSAGE_ID, K), Tombstone(ByteArray(ID_12) { 2 }, K))
        val receipt = receiver.receipt(alice, tombstones, EXPIRY)

        // Act
        val frame = (DeliveryReceipt.decode(receiver.frame(receipt, epoch, hopLimit = 3)) as Decoded.Valid).value

        // Assert
        assertEquals(3, frame.header.hopLimit)
        assertEquals(EXPIRY, frame.header.expiry)
        assertContentEquals(receipt.messageId, frame.header.messageId)
        assertFalse(receipt.messageId.contentEquals(receiver.receipt(alice, tombstones, EXPIRY).messageId))
        assertContentEquals(derivations.unsecuredTag(alice, epoch), frame.header.destinationTag)
        assertContentEquals(derivations.unsecuredTag(bob, epoch), frame.header.sourceTag)
        assertEquals(tombstones.map { it.encode().toList() }, frame.tombstones.map { it.encode().toList() })
        val container = assertIs<Container.Unsecured>(frame.container)
        assertContentEquals(bob, container.senderNodeId)
        val content = (ReceiptContent.decode(container.plaintext, secured = false) as Decoded.Valid).value
        assertContentEquals(receipt.messageId, content.messageId)
        assertEquals(EXPIRY, content.expiry)
        assertEquals(listOf(MESSAGE_IDS), content.extensions.map { it.type })
        assertContentEquals(tombstones[0].messageId + tombstones[1].messageId, content.extensions[0].value)
    }

    @Test
    fun theSenderIgnoresAReceiptWhoseListsDifferOrWhosePlaintextDoesNotMatch() {
        val ids = MESSAGE_ID + MESSAGE_ID
        val cases =
            listOf(
                receiptFrame(listOf(Tlv(MESSAGE_IDS, MESSAGE_ID))),
                receiptFrame(listOf(Tlv(MESSAGE_IDS, ByteArray(ID_12) { 1 }))),
                receiptFrame(emptyList()),
                receiptFrame(listOf(Tlv(1, ByteArray(0)))),
                receiptFrame(listOf(Tlv(MESSAGE_IDS, ids)), tombstones = 1),
                receiptFrame(listOf(Tlv(MESSAGE_IDS, MESSAGE_ID)), contentId = ByteArray(ID_12)),
                receiptFrame(listOf(Tlv(MESSAGE_IDS, MESSAGE_ID)), contentExpiry = EXPIRY + 1u),
                receiptFrame(listOf(Tlv(MESSAGE_IDS, MESSAGE_ID)), plaintext = byteArrayOf(1)),
                receiptFrame(listOf(Tlv(MESSAGE_IDS, MESSAGE_ID)), destination = bob),
                byteArrayOf(DeliveryReceipt.TYPE.toByte()),
                byteArrayOf(MessageFrame.TYPE.toByte()),
            )

        assertNotNull(sender.acknowledged(receiptFrame(listOf(Tlv(MESSAGE_IDS, ids))), NOW))
        cases.forEachIndexed { index, case -> assertNull(sender.acknowledged(case, NOW), "case $index") }
        assertNull(sender.acknowledged(secured(), NOW))
    }

    @Test
    fun aReceiptIsTakenOnlyWithinItsLifetimeAllowingClockSkew() {
        val skew = UnsecuredMessaging.CLOCK_SKEW_SECONDS.toLong()
        val lifetime = UnsecuredMessaging.LIFETIME_MAX_SECONDS.toLong()
        val receipt = receiver.receipt(alice, listOf(Tombstone(MESSAGE_ID, K)), EXPIRY)
        val at = { now: Long ->
            sender.acknowledged(receiver.frame(receipt, Derivations.epoch(now), hopLimit = 1), now)
        }

        assertNotNull(at(EXPIRY.toLong() + skew))
        assertNull(at(EXPIRY.toLong() + skew + 1))
        assertNotNull(at(EXPIRY.toLong() - lifetime - skew))
        assertNull(at(EXPIRY.toLong() - lifetime - skew - 1))
    }

    @Test
    fun aFrameCarriesTheEpochsUnsecuredTagsTheCommitmentToKAndTheDraftsHeaderFields() {
        // Arrange
        val draft = sender.draft(bob, PAYLOAD, EXPIRY)

        // Act
        val frame = (MessageFrame.decode(sender.frame(draft, epoch, hopLimit = 1)) as Decoded.Valid).value

        // Assert
        assertEquals(1, frame.header.hopLimit)
        assertEquals(EXPIRY, frame.header.expiry)
        assertContentEquals(draft.messageId, frame.header.messageId)
        assertContentEquals(derivations.unsecuredTag(bob, epoch), frame.header.destinationTag)
        assertContentEquals(derivations.unsecuredTag(alice, epoch), frame.header.sourceTag)
        val container = assertIs<Container.Unsecured>(frame.container)
        assertContentEquals(alice, container.senderNodeId)
        val content = (MessageContent.decode(container.plaintext, secured = false) as Decoded.Valid).value
        assertContentEquals(derivations.tombstoneCommitment(content.k), frame.commitment)
        assertContentEquals(draft.messageId, content.messageId)
        assertEquals(EXPIRY, content.expiry)
        assertEquals(emptyList(), content.extensions)
    }

    @Test
    fun eachDraftHasAFreshMessageIdAndSecret() {
        val first = sender.draft(bob, PAYLOAD, EXPIRY)
        val second = sender.draft(bob, PAYLOAD, EXPIRY)

        assertFalse(first.messageId.contentEquals(second.messageId))
        assertFalse(first.commitment.contentEquals(second.commitment))
    }

    @Test
    fun theLargestPayloadMakesASmallFrameAndALargerOneIsRefused() {
        val draft = sender.draft(bob, ByteArray(UnsecuredMessaging.PAYLOAD_MAX), EXPIRY)

        val size = sender.frame(draft, epoch, hopLimit = 1).size
        assertTrue(size > LinkRecord.BODY_MAX && size <= Reassembly.FRAME_SMALL_MAX, "$size")
        assertFailsWith<IllegalArgumentException> {
            sender.draft(bob, ByteArray(UnsecuredMessaging.PAYLOAD_MAX + 1), EXPIRY)
        }
    }

    @Test
    fun theDestinationAcceptsTagsOfTheLastLifetimeAndTheNextEpochOnly() {
        val draft = sender.draft(bob, PAYLOAD, EXPIRY)

        (epoch - 12u..epoch + 1u).forEach {
            assertNotNull(receiver.accept(sender.frame(draft, it, hopLimit = 1), NOW), "epoch $it")
        }
        listOf(epoch - 13u, epoch + 2u).forEach {
            assertNull(receiver.accept(sender.frame(draft, it, hopLimit = 1), NOW), "epoch $it")
        }
    }

    @Test
    fun aFrameTakesTheHopLimitOfItsWrite() {
        val draft = sender.draft(bob, PAYLOAD, EXPIRY)

        val frame = (MessageFrame.decode(sender.frame(draft, epoch, hopLimit = 3)) as Decoded.Valid).value

        assertEquals(3, frame.header.hopLimit)
    }

    @Test
    fun aNodeOwnsItsTagsOfTheLastLifetimeAndTheNextEpochOnly() {
        (epoch - 12u..epoch + 1u).forEach {
            assertTrue(receiver.owns(derivations.unsecuredTag(bob, it), NOW), "epoch $it")
        }
        listOf(epoch - 13u, epoch + 2u).forEach {
            assertFalse(receiver.owns(derivations.unsecuredTag(bob, it), NOW), "epoch $it")
        }
        assertFalse(receiver.owns(derivations.unsecuredTag(alice, epoch), NOW))
    }

    @Test
    fun anotherNodesFrameIsDropped() {
        val draft = sender.draft(alice, PAYLOAD, EXPIRY)

        assertNull(receiver.accept(sender.frame(draft, epoch, hopLimit = 1), NOW))
    }

    @Test
    fun undecodableFramesAndPlaintextsAreDropped() {
        assertNull(receiver.accept(byteArrayOf(MessageFrame.TYPE.toByte()), NOW))
        assertNull(receiver.accept(frame(Container.Unsecured(alice, byteArrayOf(1))), NOW))
    }

    @Test
    fun aSecuredContainerIsDropped() {
        assertNull(receiver.accept(frame(Container.Transport(0u, ByteArray(TRANSPORT_MINIMUM))), NOW))
    }

    @Test
    fun aPlaintextWhoseIdOrExpiryDiffersFromTheHeaderIsDropped() {
        val otherId = content(messageId = ByteArray(ID_12) { 1 })
        val otherExpiry = content(expiry = EXPIRY + 1u)

        assertNotNull(receiver.accept(frame(content()), NOW))
        assertNull(receiver.accept(frame(otherId), NOW))
        assertNull(receiver.accept(frame(otherExpiry), NOW))
    }

    @Test
    fun aCommitmentThatIsNotTheHashOfKIsDropped() {
        assertNull(receiver.accept(frame(content(), commitment = ByteArray(COMMITMENT)), NOW))
    }

    @Test
    fun anExpiredOrFarFutureFrameIsDroppedAllowingClockSkew() {
        val skew = UnsecuredMessaging.CLOCK_SKEW_SECONDS
        val latest = UnsecuredMessaging.LIFETIME_MAX_SECONDS + skew
        val draft = sender.draft(bob, PAYLOAD, EXPIRY)
        val acceptAt = { now: Long -> receiver.accept(sender.frame(draft, Derivations.epoch(now), hopLimit = 1), now) }

        assertNotNull(acceptAt(EXPIRY.toLong() + skew))
        assertNull(acceptAt(EXPIRY.toLong() + skew + 1))
        assertNotNull(acceptAt(EXPIRY.toLong() - latest))
        assertNull(acceptAt(EXPIRY.toLong() - latest - 1))
    }

    @Test
    fun aRequestedReadReceiptReachesTheDestinationAndAnUnrequestedOneDoesNot() {
        val requested = sender.draft(bob, PAYLOAD, EXPIRY, readReceipt = true)
        val unrequested = sender.draft(bob, PAYLOAD, EXPIRY)

        val delivery = assertNotNull(receiver.accept(sender.frame(requested, epoch, hopLimit = 1), NOW))
        val plain = assertNotNull(receiver.accept(sender.frame(unrequested, epoch, hopLimit = 1), NOW))

        assertTrue(delivery.readReceipt)
        assertFalse(plain.readReceipt)
    }

    @Test
    fun theSenderReadsAReadReceiptListingTheMessagesReadInOrder() {
        // Arrange
        val ids = List(ReadReceipt.IDS_MAX) { index -> ByteArray(ID_12) { index.toByte() } }
        val read = receiver.readReceipt(alice, ids, EXPIRY)

        // Act
        val bytes = read.frame(epoch, hopLimit = 3)

        // Assert
        val frame = (ReadReceipt.decode(bytes) as Decoded.Valid).value
        assertEquals(3, frame.header.hopLimit)
        assertEquals(EXPIRY, frame.header.expiry)
        assertContentEquals(read.messageId, frame.header.messageId)
        assertContentEquals(derivations.unsecuredTag(alice, epoch), frame.header.destinationTag)
        assertContentEquals(derivations.unsecuredTag(alice, epoch), read.destination(epoch))
        assertContentEquals(derivations.unsecuredTag(bob, epoch), frame.header.sourceTag)
        assertContentEquals(derivations.unsecuredTag(bob, epoch), read.source(epoch))
        val acknowledgement = assertNotNull(sender.read(bytes, NOW))
        assertContentEquals(bob, acknowledgement.sender)
        assertEquals(ids.map { it.toList() }, acknowledgement.messageIds.map { it.toList() })
    }

    @Test
    fun aReadReceiptListsOneToSixtyFourMessages() {
        val ids = List(ReadReceipt.IDS_MAX + 1) { MESSAGE_ID }

        assertFailsWith<IllegalArgumentException> { receiver.readReceipt(alice, emptyList(), EXPIRY) }
        assertFailsWith<IllegalArgumentException> { receiver.readReceipt(alice, ids, EXPIRY) }
    }

    @Test
    fun theSenderIgnoresAFlawedReadReceipt() {
        val most = ByteArray(ID_12 * ReadReceipt.IDS_MAX)
        val flaws =
            mapOf(
                "undecodable" to byteArrayOf(ReadReceipt.TYPE.toByte()),
                "other destination" to readFrame(listOf(Tlv(MESSAGE_IDS, MESSAGE_ID)), destination = bob),
                "secured container" to readFrame(container = Container.Transport(0u, ByteArray(TRANSPORT_MINIMUM))),
                "no MESSAGE_IDS" to readFrame(emptyList()),
                "empty MESSAGE_IDS" to readFrame(listOf(Tlv(MESSAGE_IDS, ByteArray(0)))),
                "partial id" to readFrame(listOf(Tlv(MESSAGE_IDS, MESSAGE_ID + 1))),
                "65 ids" to readFrame(listOf(Tlv(MESSAGE_IDS, most + MESSAGE_ID))),
                "other message_id" to readFrame(listOf(Tlv(MESSAGE_IDS, MESSAGE_ID)), contentId = ByteArray(ID_12)),
                "other expiry" to readFrame(listOf(Tlv(MESSAGE_IDS, MESSAGE_ID)), contentExpiry = EXPIRY + 1u),
                "not a receipt" to readFrame(plaintext = byteArrayOf(1)),
            )

        assertNotNull(sender.read(readFrame(listOf(Tlv(MESSAGE_IDS, most))), NOW))
        flaws.forEach { (flaw, frame) -> assertNull(sender.read(frame, NOW), flaw) }
        val skew = UnsecuredMessaging.CLOCK_SKEW_SECONDS
        assertNull(sender.read(readFrame(listOf(Tlv(MESSAGE_IDS, MESSAGE_ID))), EXPIRY.toLong() + skew + 1), "expired")
    }

    private fun readFrame(
        extensions: List<Tlv> = listOf(Tlv(MESSAGE_IDS, MESSAGE_ID)),
        contentId: ByteArray = MESSAGE_ID,
        contentExpiry: UInt = EXPIRY,
        plaintext: ByteArray? = null,
        destination: ByteArray = alice,
        container: Container? = null,
    ): ByteArray {
        val content = plaintext ?: ReceiptContent(contentId, contentExpiry, extensions).encode(secured = false)
        val (to, from) = derivations.unsecuredTag(destination, epoch) to derivations.unsecuredTag(bob, epoch)
        val header = UnicastHeader(1, MESSAGE_ID, EXPIRY, to, from)
        return ReadReceipt(header, container ?: Container.Unsecured(bob, content)).encode()
    }

    private fun receiptFrame(
        extensions: List<Tlv>,
        tombstones: Int = 2,
        contentId: ByteArray = MESSAGE_ID,
        contentExpiry: UInt = EXPIRY,
        plaintext: ByteArray? = null,
        destination: ByteArray = alice,
    ): ByteArray {
        val content = plaintext ?: ReceiptContent(contentId, contentExpiry, extensions).encode(secured = false)
        val header =
            UnicastHeader(
                1,
                MESSAGE_ID,
                EXPIRY,
                derivations.unsecuredTag(destination, epoch),
                derivations.unsecuredTag(bob, epoch),
            )
        val entries = List(tombstones) { Tombstone(MESSAGE_ID, K) }
        return DeliveryReceipt(header, entries, Container.Unsecured(bob, content)).encode()
    }

    private fun secured(): ByteArray {
        val (destination, source) = derivations.unsecuredTag(alice, epoch) to derivations.unsecuredTag(bob, epoch)
        val header = UnicastHeader(1, MESSAGE_ID, EXPIRY, destination, source)
        val container = Container.Transport(0u, ByteArray(TRANSPORT_MINIMUM))
        return DeliveryReceipt(header, listOf(Tombstone(MESSAGE_ID, K)), container).encode()
    }

    private fun content(
        messageId: ByteArray = MESSAGE_ID,
        expiry: UInt = EXPIRY,
    ): Container.Unsecured {
        val plaintext = MessageContent(messageId, expiry, K, emptyList(), PAYLOAD).encode(secured = false)
        return Container.Unsecured(alice, plaintext)
    }

    private fun frame(
        container: Container,
        commitment: ByteArray = derivations.tombstoneCommitment(K),
    ): ByteArray {
        val destination = derivations.unsecuredTag(bob, epoch)
        val header = UnicastHeader(1, MESSAGE_ID, EXPIRY, destination, derivations.unsecuredTag(alice, epoch))
        return MessageFrame(header, commitment, container).encode()
    }

    private companion object {
        const val NOW = 1_798_762_200L
        const val ID = 32
        const val ID_12 = 12
        const val COMMITMENT = 16
        const val TRANSPORT_MINIMUM = 17
        const val MESSAGE_IDS = 0x83
        val EXPIRY = (NOW + 300).toUInt()
        val PAYLOAD = "hello".encodeToByteArray()
        val MESSAGE_ID = ByteArray(ID_12) { 7 }
        val K = ByteArray(COMMITMENT) { 9 }
    }
}
