package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.internal.Container
import ch.trancee.meshlink.internal.Decoded
import ch.trancee.meshlink.internal.Derivations
import ch.trancee.meshlink.internal.EndToEndTags
import ch.trancee.meshlink.internal.FirstMessages
import ch.trancee.meshlink.internal.Hpke
import ch.trancee.meshlink.internal.IdentityCertificate
import ch.trancee.meshlink.internal.KeyPair
import ch.trancee.meshlink.internal.MessageContent
import ch.trancee.meshlink.internal.MessageFrame
import ch.trancee.meshlink.internal.NoiseHandshake
import ch.trancee.meshlink.internal.NoiseKeys
import ch.trancee.meshlink.internal.NoisePattern
import ch.trancee.meshlink.internal.NoiseResult
import ch.trancee.meshlink.internal.NoiseRole
import ch.trancee.meshlink.internal.Reassembly
import ch.trancee.meshlink.internal.SecuredKeys
import ch.trancee.meshlink.internal.StaticKeys
import ch.trancee.meshlink.internal.Tlv
import ch.trancee.meshlink.internal.UnicastHeader
import ch.trancee.meshlink.internal.UnsecuredMessaging
import ch.trancee.meshlink.internal.WireProfile
import ch.trancee.meshlink.vectors.mutationInputs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirstMessagesTest {
    private val crypto = SodiumCryptoProvider
    private val derivations = Derivations(crypto)
    private val tags = EndToEndTags(derivations)
    private val clock = FixedClock.atSeconds(NOW)
    private val epoch = Derivations.epoch(NOW)

    // One side of a secured mesh in [profile]: its keys, certificate and codec.
    private inner class Side(
        val profile: WireProfile,
        seed: Byte,
    ) {
        val identity = crypto.ed25519KeyPair(ByteArray(KEY) { seed })
        val nodeId: ByteArray = derivations.nodeId(identity.publicKey)
        val static: KeyPair = crypto.x25519KeyPair()
        val xWing: KeyPair? = if (profile == WireProfile.POST_QUANTUM) crypto.xWingKeyPair() else null
        val certificate: IdentityCertificate = certify(static.publicKey)
        val codec = FirstMessages(crypto, clock, SecuredKeys(nodeId, static, xWing, certificate))

        fun certify(
            x25519Key: ByteArray,
            xWingKey: ByteArray? = xWing?.publicKey,
        ): IdentityCertificate = IdentityCertificate.issue(crypto, clock, identity, StaticKeys(x25519Key, xWingKey))
    }

    private fun sides(profile: WireProfile) = Side(profile, 1) to Side(profile, 2)

    private val profiles = listOf(WireProfile.CLASSICAL, WireProfile.POST_QUANTUM)

    @Test
    fun theResponderAcceptsADraftedFirstMessageWithTheSenderItsCertificateAndThePayload() =
        profiles.forEach { profile ->
            // Arrange
            val (alice, bob) = sides(profile)
            val draft = assertNotNull(alice.codec.draft(bob.certificate, PAYLOAD, EXPIRY, readReceipt = false))

            // Act
            val delivery = bob.codec.accept(alice.codec.frame(draft, epoch, HOP_LIMIT), NOW)

            // Assert
            assertNotNull(delivery, "$profile")
            assertContentEquals(alice.nodeId, delivery.sender)
            assertContentEquals(alice.certificate.encode(), delivery.certificate.encode())
            assertContentEquals(PAYLOAD, delivery.payload)
            assertFalse(delivery.readReceipt)
        }

    @Test
    fun aRequestedReadReceiptReachesTheResponder() {
        val (alice, bob) = sides(WireProfile.CLASSICAL)
        val draft = assertNotNull(alice.codec.draft(bob.certificate, PAYLOAD, EXPIRY, readReceipt = true))

        val delivery = bob.codec.accept(alice.codec.frame(draft, epoch, HOP_LIMIT), NOW)

        assertTrue(assertNotNull(delivery).readReceipt)
    }

    @Test
    fun aFrameCarriesThePublicTagTheConversationTagAndAnIkInitiatorThatRetriesReuse() =
        profiles.forEach { profile ->
            // Arrange
            val (alice, bob) = sides(profile)
            val draft = assertNotNull(alice.codec.draft(bob.certificate, PAYLOAD, EXPIRY, readReceipt = false))

            // Act
            val first = decode(alice.codec.frame(draft, epoch, HOP_LIMIT))
            val retry = decode(alice.codec.frame(draft, epoch + 1u, 1))

            // Assert
            assertEquals(HOP_LIMIT, first.header.hopLimit)
            assertEquals(1, retry.header.hopLimit)
            assertEquals(EXPIRY, first.header.expiry)
            assertContentEquals(draft.messageId, first.header.messageId)
            assertContentEquals(tags.publicTag(bob.static.publicKey, epoch), first.header.destinationTag)
            assertContentEquals(tags.publicTag(bob.static.publicKey, epoch + 1u), retry.header.destinationTag)
            assertContentEquals(alice.codec.sourceTag(draft, epoch), first.header.sourceTag)
            assertContentEquals(alice.codec.sourceTag(draft, epoch + 1u), retry.header.sourceTag)
            assertFalse(first.header.sourceTag.contentEquals(retry.header.sourceTag))
            assertContentEquals(draft.commitment, first.commitment)
            assertIs<Container.IkInitiator>(first.container)
            assertContentEquals(first.container.encode(), retry.container.encode())
        }

    @Test
    fun theSourceTagIsTheConversationsTagForDirectionTwo() {
        val (alice, bob) = sides(WireProfile.CLASSICAL)
        val draft = assertNotNull(alice.codec.draft(bob.certificate, PAYLOAD, EXPIRY, readReceipt = false))
        val frame = decode(alice.codec.frame(draft, epoch, HOP_LIMIT))
        val container = assertIs<Container.IkInitiator>(frame.container)
        val responder =
            NoiseHandshake(crypto, NoisePattern.IK, NoiseRole.RESPONDER, PROLOGUE_CLASSICAL, NoiseKeys(bob.static))
        val padded = (responder.readMessage(container.noiseMessage) as NoiseResult.Ok).value
        val content = (MessageContent.decode(padded, secured = true) as Decoded.Valid).value
        val seed = content.extensions.single { it.type == CONVERSATION_SEED }.value

        val secret = tags.conversationSecret(seed, alice.nodeId, bob.nodeId)

        assertContentEquals(tags.conversationTag(secret, 2, epoch), frame.header.sourceTag)
        assertEquals(listOf(SENDER_CERTIFICATE, CONVERSATION_SEED), content.extensions.map { it.type })
        assertEquals(0, padded.size % BLOCK)
    }

    @Test
    fun eachDraftHasAFreshMessageIdSecretAndConversation() {
        val (alice, bob) = sides(WireProfile.CLASSICAL)
        val first = assertNotNull(alice.codec.draft(bob.certificate, PAYLOAD, EXPIRY, readReceipt = false))
        val second = assertNotNull(alice.codec.draft(bob.certificate, PAYLOAD, EXPIRY, readReceipt = false))

        assertFalse(first.messageId.contentEquals(second.messageId))
        assertFalse(first.commitment.contentEquals(second.commitment))
        assertFalse(alice.codec.sourceTag(first, epoch).contentEquals(alice.codec.sourceTag(second, epoch)))
    }

    @Test
    fun theLargestPostQuantumPayloadWithAReadReceiptMakesASmallFrameAndALargerOneIsRefused() {
        val (alice, bob) = sides(WireProfile.POST_QUANTUM)
        val largest = ByteArray(UnsecuredMessaging.PAYLOAD_MAX)

        val draft = assertNotNull(alice.codec.draft(bob.certificate, largest, EXPIRY, readReceipt = true))

        assertTrue(alice.codec.frame(draft, epoch, HOP_LIMIT).size <= Reassembly.FRAME_SMALL_MAX)
        assertFailsWith<IllegalArgumentException> {
            alice.codec.draft(bob.certificate, ByteArray(UnsecuredMessaging.PAYLOAD_MAX + 1), EXPIRY, false)
        }
    }

    @Test
    fun aCertificateWhoseKeysCannotBeSealedToMakesNoDraft() {
        val (alice, bob) = sides(WireProfile.CLASSICAL)
        val (quantumAlice, quantumBob) = sides(WireProfile.POST_QUANTUM)
        val xWing = assertNotNull(quantumBob.xWing).publicKey

        val lowOrder = bob.certify(ByteArray(KEY))
        val lowOrderX25519Part = xWing.copyOf().also { it.fill(0, it.size - KEY) }
        val lowOrderXWing = quantumBob.certify(quantumBob.static.publicKey, lowOrderX25519Part)

        assertNull(alice.codec.draft(lowOrder, PAYLOAD, EXPIRY, readReceipt = false))
        assertNull(quantumAlice.codec.draft(lowOrderXWing, PAYLOAD, EXPIRY, readReceipt = false))
    }

    @Test
    fun aNodeOwnsThePublicTagsOfItsKeyForTheAdjacentEpochsOnly() {
        val (alice, bob) = sides(WireProfile.CLASSICAL)

        listOf(epoch - 1u, epoch, epoch + 1u).forEach {
            assertTrue(bob.codec.owns(tags.publicTag(bob.static.publicKey, it), NOW), "epoch $it")
        }
        listOf(epoch - 2u, epoch + 2u).forEach {
            assertFalse(bob.codec.owns(tags.publicTag(bob.static.publicKey, it), NOW), "epoch $it")
        }
        assertFalse(bob.codec.owns(tags.publicTag(alice.static.publicKey, epoch), NOW))
        assertContentEquals(tags.publicTag(bob.static.publicKey, epoch), alice.codec.publicTag(bob.certificate, epoch))
    }

    @Test
    fun aFirstMessageForAnotherNodeOrAnUndecodableFrameIsDropped() {
        val (alice, bob) = sides(WireProfile.CLASSICAL)
        val carol = Side(WireProfile.CLASSICAL, 3)
        val draft = assertNotNull(alice.codec.draft(carol.certificate, PAYLOAD, EXPIRY, readReceipt = false))

        assertNull(bob.codec.accept(alice.codec.frame(draft, epoch, HOP_LIMIT), NOW))
        assertNull(bob.codec.accept(byteArrayOf(MessageFrame.TYPE.toByte()), NOW))
    }

    @Test
    fun aWellFormedForgeryIsAcceptedSoEachRefusalBelowIsCausedByItsOneFlaw() =
        profiles.forEach { profile ->
            val (alice, bob) = sides(profile)

            assertNotNull(bob.codec.accept(Forgery(alice, bob).frame(), NOW), "$profile")
        }

    @Test
    fun aPlaintextBreakingAMustIsDropped() =
        profiles.forEach { profile ->
            val (alice, bob) = sides(profile)
            val forged = Forgery(alice, bob)
            val other = Side(profile, 3)
            val sealed = { content: MessageContent -> forged.frame(plaintext = content.encode(secured = true)) }
            val flaws =
                mapOf(
                    "wrong padding" to
                        forged.frame(plaintext = forged.content().encode(secured = true) + ByteArray(BLOCK)),
                    "no padding" to forged.frame(plaintext = forged.content().encode(secured = false)),
                    "no seed" to sealed(forged.content(seed = null)),
                    "no certificate" to sealed(forged.content(certificate = null)),
                    "other x25519 key" to sealed(forged.content(alice.certify(other.static.publicKey).encode())),
                    "invalid certificate" to sealed(forged.content(other.certificate.encode().also { it[0] = 2 })),
                    "other message_id" to sealed(forged.content(messageId = ByteArray(ID) { 1 })),
                    "other expiry" to sealed(forged.content(expiry = EXPIRY + 1u)),
                    "other commitment" to forged.frame(commitment = ByteArray(COMMITMENT)),
                    "unsecured container" to
                        forged.frame(container = Container.Unsecured(alice.nodeId, byteArrayOf(1))),
                    "transport container" to
                        forged.frame(container = Container.Transport(0u, ByteArray(TRANSPORT_MIN))),
                    "other responder" to forged.frame(responderStatic = other.static.publicKey),
                )

            flaws.forEach { (flaw, frame) -> assertNull(bob.codec.accept(frame, NOW), "$profile: $flaw") }
        }

    @Test
    fun aTamperedEncOrCtIsDropped() {
        val (alice, bob) = sides(WireProfile.POST_QUANTUM)
        val forged = Forgery(alice, bob)

        val enc = forged.frame(seal = { it.also { sealed -> sealed[0] = (sealed[0] + 1).toByte() } })
        val ct = forged.frame(seal = { it.also { sealed -> sealed[sealed.size - 1] = (sealed.last() + 1).toByte() } })
        val short = forged.frame(seal = { it.copyOf(Hpke.OVERHEAD - 1) })

        assertNull(bob.codec.accept(enc, NOW))
        assertNull(bob.codec.accept(ct, NOW))
        assertNull(bob.codec.accept(short, NOW))
    }

    @Test
    fun anExpiredSenderCertificateOrMessageIsDroppedAllowingClockSkew() {
        val (alice, bob) = sides(WireProfile.CLASSICAL)
        val draft = assertNotNull(alice.codec.draft(bob.certificate, PAYLOAD, EXPIRY, readReceipt = false))
        val skew = UnsecuredMessaging.CLOCK_SKEW_SECONDS
        val acceptAt = { now: Long ->
            val later = FirstMessages(crypto, FixedClock.atSeconds(now), bobKeys(bob))
            later.accept(alice.codec.frame(draft, Derivations.epoch(now), HOP_LIMIT), now)
        }

        assertNotNull(acceptAt(EXPIRY.toLong() + skew))
        assertNull(acceptAt(EXPIRY.toLong() + skew + 1))
        val certificateEnd = alice.certificate.notAfter.toLong() + IdentityCertificate.CLOCK_SKEW_SECONDS
        val late = FirstMessages(crypto, FixedClock.atSeconds(certificateEnd), bobKeys(bob))
        assertNull(late.accept(alice.codec.frame(draft, epoch, HOP_LIMIT), NOW))
    }

    @Test
    fun mutatedFirstMessagesNeverThrowAndOnlyDeliverTheOriginalPayload() =
        profiles.forEach { profile ->
            val (alice, bob) = sides(profile)
            val draft = assertNotNull(alice.codec.draft(bob.certificate, PAYLOAD, EXPIRY, readReceipt = false))

            mutationInputs(alice.codec.frame(draft, epoch, HOP_LIMIT)).forEach { input ->
                bob.codec.accept(input, NOW)?.let { assertContentEquals(PAYLOAD, it.payload, "$profile") }
            }
        }

    private fun bobKeys(bob: Side) = SecuredKeys(bob.nodeId, bob.static, bob.xWing, bob.certificate)

    private fun decode(bytes: ByteArray): MessageFrame = (MessageFrame.decode(bytes) as Decoded.Valid).value

    // The initiator side written out by hand, so each test can break one rule the codec checks.
    private inner class Forgery(
        private val alice: Side,
        private val bob: Side,
    ) {
        private val k = ByteArray(COMMITMENT) { 9 }

        fun content(
            certificate: ByteArray? = alice.certificate.encode(),
            seed: ByteArray? = ByteArray(BLOCK) { 5 },
            messageId: ByteArray = MESSAGE_ID,
            expiry: UInt = EXPIRY,
        ): MessageContent {
            val sender = certificate?.let { Tlv(SENDER_CERTIFICATE, it) }
            val extensions = listOfNotNull(sender, seed?.let { Tlv(CONVERSATION_SEED, it) })
            return MessageContent(messageId, expiry, k, extensions, PAYLOAD)
        }

        fun frame(
            plaintext: ByteArray = content().encode(secured = true),
            commitment: ByteArray = derivations.tombstoneCommitment(k),
            responderStatic: ByteArray = bob.static.publicKey,
            seal: (ByteArray) -> ByteArray = { it },
            container: Container? = null,
        ): ByteArray {
            val pattern = if (alice.profile == WireProfile.POST_QUANTUM) NoisePattern.IK_HFS else NoisePattern.IK
            val prologue = Derivations.e2ePrologue(1, alice.profile)
            val keys = NoiseKeys(alice.static, remoteStatic = responderStatic)
            val initiator = NoiseHandshake(crypto, pattern, NoiseRole.INITIATOR, prologue, keys)
            val payload =
                bob.certificate.xWingKey?.let {
                    val info = Derivations.hpkeInfo(alice.static.publicKey, bob.nodeId)
                    seal(Hpke(crypto).seal(it, info, plaintext).ok())
                } ?: plaintext
            val message = (initiator.writeMessage(payload) as NoiseResult.Ok).value
            val destination = tags.publicTag(bob.static.publicKey, epoch)
            val header = UnicastHeader(HOP_LIMIT, MESSAGE_ID, EXPIRY, destination, ByteArray(TAG))
            return MessageFrame(header, commitment, container ?: Container.IkInitiator(message)).encode()
        }
    }

    private companion object {
        const val NOW = 1_798_762_200L
        const val KEY = 32
        const val ID = 12
        const val TAG = 8
        const val BLOCK = 32
        const val COMMITMENT = 16
        const val TRANSPORT_MIN = 17
        const val HOP_LIMIT = 255
        const val SENDER_CERTIFICATE = 0x01
        const val CONVERSATION_SEED = 0x82
        val EXPIRY = (NOW + 300).toUInt()
        val PAYLOAD = "hello".encodeToByteArray()
        val MESSAGE_ID = ByteArray(ID) { 7 }
        val PROLOGUE_CLASSICAL = Derivations.e2ePrologue(1, WireProfile.CLASSICAL)
    }
}
