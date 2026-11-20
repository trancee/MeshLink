package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.internal.Container
import ch.trancee.meshlink.internal.Decoded
import ch.trancee.meshlink.internal.Derivations
import ch.trancee.meshlink.internal.LinkRecord
import ch.trancee.meshlink.internal.MessageContent
import ch.trancee.meshlink.internal.MessageFrame
import ch.trancee.meshlink.internal.Reassembly
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
        val draft = sender.draft(bob, PAYLOAD, EXPIRY, hopLimit = 1)

        // Act
        val delivery = receiver.accept(sender.frame(draft, epoch), NOW)

        // Assert
        assertNotNull(delivery)
        assertContentEquals(alice, delivery.sender)
        assertContentEquals(PAYLOAD, delivery.payload)
    }

    @Test
    fun aFrameCarriesTheEpochsUnsecuredTagsTheCommitmentToKAndTheDraftsHeaderFields() {
        // Arrange
        val draft = sender.draft(bob, PAYLOAD, EXPIRY, hopLimit = 1)

        // Act
        val frame = (MessageFrame.decode(sender.frame(draft, epoch)) as Decoded.Valid).value

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
        val first = sender.draft(bob, PAYLOAD, EXPIRY, hopLimit = 1)
        val second = sender.draft(bob, PAYLOAD, EXPIRY, hopLimit = 1)

        assertFalse(first.messageId.contentEquals(second.messageId))
        assertFalse(first.commitment.contentEquals(second.commitment))
    }

    @Test
    fun theLargestPayloadMakesASmallFrameAndALargerOneIsRefused() {
        val draft = sender.draft(bob, ByteArray(UnsecuredMessaging.PAYLOAD_MAX), EXPIRY, hopLimit = 1)

        val size = sender.frame(draft, epoch).size
        assertTrue(size > LinkRecord.BODY_MAX && size <= Reassembly.FRAME_SMALL_MAX, "$size")
        assertFailsWith<IllegalArgumentException> {
            sender.draft(bob, ByteArray(UnsecuredMessaging.PAYLOAD_MAX + 1), EXPIRY, hopLimit = 1)
        }
    }

    @Test
    fun theDestinationAcceptsTagsOfTheAdjacentEpochsOnly() {
        val draft = sender.draft(bob, PAYLOAD, EXPIRY, hopLimit = 1)

        listOf(epoch - 1u, epoch, epoch + 1u).forEach {
            assertNotNull(receiver.accept(sender.frame(draft, it), NOW), "epoch $it")
        }
        listOf(epoch - 2u, epoch + 2u).forEach {
            assertNull(receiver.accept(sender.frame(draft, it), NOW), "epoch $it")
        }
    }

    @Test
    fun aFrameTakesTheHopLimitOfItsWrite() {
        val draft = sender.draft(bob, PAYLOAD, EXPIRY, hopLimit = 1)

        val frame = (MessageFrame.decode(sender.frame(draft, epoch, hopLimit = 3)) as Decoded.Valid).value

        assertEquals(3, frame.header.hopLimit)
    }

    @Test
    fun aNodeOwnsItsTagsOfTheAdjacentEpochsOnly() {
        listOf(epoch - 1u, epoch, epoch + 1u).forEach {
            assertTrue(receiver.owns(derivations.unsecuredTag(bob, it), NOW), "epoch $it")
        }
        listOf(epoch - 2u, epoch + 2u).forEach {
            assertFalse(receiver.owns(derivations.unsecuredTag(bob, it), NOW), "epoch $it")
        }
        assertFalse(receiver.owns(derivations.unsecuredTag(alice, epoch), NOW))
    }

    @Test
    fun anotherNodesFrameIsDropped() {
        val draft = sender.draft(alice, PAYLOAD, EXPIRY, hopLimit = 1)

        assertNull(receiver.accept(sender.frame(draft, epoch), NOW))
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
        val draft = sender.draft(bob, PAYLOAD, EXPIRY, hopLimit = 1)
        val acceptAt = { now: Long -> receiver.accept(sender.frame(draft, Derivations.epoch(now)), now) }

        assertNotNull(acceptAt(EXPIRY.toLong() + skew))
        assertNull(acceptAt(EXPIRY.toLong() + skew + 1))
        assertNotNull(acceptAt(EXPIRY.toLong() - latest))
        assertNull(acceptAt(EXPIRY.toLong() - latest - 1))
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
        val EXPIRY = (NOW + 300).toUInt()
        val PAYLOAD = "hello".encodeToByteArray()
        val MESSAGE_ID = ByteArray(ID_12) { 7 }
        val K = ByteArray(COMMITMENT) { 9 }
    }
}
