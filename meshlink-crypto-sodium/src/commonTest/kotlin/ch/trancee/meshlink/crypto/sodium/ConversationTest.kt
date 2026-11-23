package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.internal.CipherState
import ch.trancee.meshlink.internal.Container
import ch.trancee.meshlink.internal.Conversation
import ch.trancee.meshlink.internal.CryptoResult
import ch.trancee.meshlink.internal.Decoded
import ch.trancee.meshlink.internal.DeliveryReceipt
import ch.trancee.meshlink.internal.Derivations
import ch.trancee.meshlink.internal.Draft
import ch.trancee.meshlink.internal.EndToEndSession
import ch.trancee.meshlink.internal.EndToEndTags
import ch.trancee.meshlink.internal.FirstDelivery
import ch.trancee.meshlink.internal.FirstMessages
import ch.trancee.meshlink.internal.Hpke
import ch.trancee.meshlink.internal.IdentityCertificate
import ch.trancee.meshlink.internal.MessageContent
import ch.trancee.meshlink.internal.MessageFrame
import ch.trancee.meshlink.internal.NoiseHandshake
import ch.trancee.meshlink.internal.NoiseKeys
import ch.trancee.meshlink.internal.NoisePattern
import ch.trancee.meshlink.internal.NoiseResult
import ch.trancee.meshlink.internal.NoiseRole
import ch.trancee.meshlink.internal.Opening
import ch.trancee.meshlink.internal.ReceiptContent
import ch.trancee.meshlink.internal.SecuredKeys
import ch.trancee.meshlink.internal.StaticKeys
import ch.trancee.meshlink.internal.Tlv
import ch.trancee.meshlink.internal.Tombstone
import ch.trancee.meshlink.internal.UnicastHeader
import ch.trancee.meshlink.internal.WireProfile
import ch.trancee.meshlink.internal.session
import ch.trancee.meshlink.vectors.mutationInputs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationTest {
    private val crypto = SodiumCryptoProvider
    private val derivations = Derivations(crypto)
    private val tags = EndToEndTags(derivations)
    private val clock = FixedClock.atSeconds(NOW)
    private val epoch = Derivations.epoch(NOW)
    private val profiles = listOf(WireProfile.CLASSICAL, WireProfile.POST_QUANTUM)

    // One side of a secured mesh in [profile]: its keys and first-message codec.
    private inner class Side(
        val profile: WireProfile,
        seed: Byte,
    ) {
        val identity = crypto.ed25519KeyPair(ByteArray(KEY) { seed })
        val nodeId: ByteArray = derivations.nodeId(identity.publicKey)
        val static = crypto.x25519KeyPair()
        val xWing = if (profile == WireProfile.POST_QUANTUM) crypto.xWingKeyPair() else null
        val certificate: IdentityCertificate =
            IdentityCertificate.issue(crypto, clock, identity, StaticKeys(static.publicKey, xWing?.publicKey))
        val codec = FirstMessages(crypto, clock, SecuredKeys(nodeId, static, xWing, certificate))
    }

    // A first message from Alice that Bob opened, before Alice reads Bob's answer.
    private inner class Opened(
        profile: WireProfile,
    ) {
        val alice = Side(profile, 1)
        val bob = Side(profile, 2)
        val draft: Draft = assertNotNull(alice.codec.draft(bob.certificate, PAYLOAD, EXPIRY, readReceipt = false))
        val opening = draft.sealer as Opening
        val first: FirstDelivery = assertNotNull(bob.codec.accept(alice.codec.frame(draft, epoch, HOP_LIMIT), NOW))
        val answer: ByteArray = first.conversation.frame(first.answer, epoch, HOP_LIMIT)
        val secret: ByteArray = conversationSecret(this)

        fun adopted(): Conversation = assertNotNull(opening.adopt(answer, NOW))
    }

    // The conversation secret Bob derives from the seed Alice sealed in the first message.
    private fun conversationSecret(opened: Opened): ByteArray {
        val frame = decode(opened.alice.codec.frame(opened.draft, epoch, HOP_LIMIT))
        val container = frame.container as Container.IkInitiator
        val responder = responder(opened)
        val payload = (responder.readMessage(container.noiseMessage) as NoiseResult.Ok).value
        val padded =
            opened.bob.xWing?.let {
                val info = Derivations.hpkeInfo(opened.alice.static.publicKey, opened.bob.nodeId)
                (Hpke(crypto).open(it.secretKey, info, payload) as CryptoResult.Ok).value
            } ?: payload
        val content = (MessageContent.decode(padded, secured = true) as Decoded.Valid).value
        val seed = content.extensions.single { it.type == CONVERSATION_SEED }.value
        return tags.conversationSecret(seed, opened.alice.nodeId, opened.bob.nodeId)
    }

    private fun responder(opened: Opened): NoiseHandshake {
        val pattern = if (opened.bob.profile == WireProfile.POST_QUANTUM) NoisePattern.IK_HFS else NoisePattern.IK
        val prologue = Derivations.e2ePrologue(1, opened.bob.profile)
        return NoiseHandshake(crypto, pattern, NoiseRole.RESPONDER, prologue, NoiseKeys(opened.bob.static))
    }

    @Test
    fun aSessionOpensFromMessageTwoAndCarriesMessagesAndReceiptsBothWays() =
        profiles.forEach { profile ->
            // Arrange
            val opened = Opened(profile)
            val alice = opened.adopted()
            val bob = opened.first.conversation

            // Act
            val toBob = assertNotNull(alice.draft(SECOND, EXPIRY))
            val atBob = assertNotNull(bob.accept(alice.frame(toBob, epoch, HOP_LIMIT), NOW), "$profile")
            val receipt = assertNotNull(bob.receipt(listOf(atBob.tombstone), EXPIRY))
            val acknowledged = alice.acknowledged(bob.frame(receipt, epoch, HOP_LIMIT), NOW)
            val toAlice = assertNotNull(bob.draft(THIRD, EXPIRY))
            val atAlice = assertNotNull(alice.accept(bob.frame(toAlice, epoch, HOP_LIMIT), NOW))

            // Assert
            assertContentEquals(opened.alice.nodeId, atBob.sender)
            assertContentEquals(SECOND, atBob.payload)
            assertContentEquals(toBob.messageId, atBob.tombstone.messageId)
            assertEquals(EXPIRY, atBob.expiry)
            assertEquals(listOf(toBob.messageId.toList()), assertNotNull(acknowledged).map { it.toList() })
            assertContentEquals(opened.bob.nodeId, atAlice.sender)
            assertContentEquals(THIRD, atAlice.payload)
            assertContentEquals(opened.bob.nodeId, alice.peer)
            assertContentEquals(opened.alice.nodeId, bob.peer)
            assertContentEquals(opened.alice.nodeId, alice.initiatorNodeId)
            assertContentEquals(opened.alice.nodeId, bob.initiatorNodeId)
            assertTrue(alice.initiator)
            assertFalse(bob.initiator)
            assertEquals(1 to 2, alice.sent to alice.received)
            assertEquals(2 to 1, bob.sent to bob.received)
        }

    @Test
    fun theFirstDeliveryCarriesTheTombstoneAndAnAnswerReceiptInAnIkResponderToTheReplyTag() =
        profiles.forEach { profile ->
            val opened = Opened(profile)
            val message = opened.first.message

            val answer = (DeliveryReceipt.decode(opened.answer) as Decoded.Valid).value

            assertContentEquals(PAYLOAD, message.payload)
            assertContentEquals(opened.alice.nodeId, message.sender)
            assertContentEquals(opened.draft.messageId, message.tombstone.messageId)
            assertContentEquals(opened.draft.commitment, derivations.tombstoneCommitment(message.tombstone.k))
            assertEquals(EXPIRY, message.expiry)
            assertIs<Container.IkResponder>(answer.container, "$profile")
            assertContentEquals(message.tombstone.encode(), answer.tombstones.single().encode())
            assertEquals(EXPIRY, answer.header.expiry)
            assertFalse(answer.header.messageId.contentEquals(opened.draft.messageId))
            assertContentEquals(tags.conversationTag(opened.secret, 2, epoch), answer.header.destinationTag)
            assertContentEquals(tags.conversationTag(opened.secret, 1, epoch), answer.header.sourceTag)
            assertContentEquals(opened.alice.nodeId, opened.first.answer.destination)
        }

    @Test
    fun eachSideSendsToTheTagOfItsDirectionFromTheOtherAndOwnsOnlyTheOtherForAdjacentEpochs() {
        val opened = Opened(WireProfile.CLASSICAL)
        val alice = opened.adopted()
        val bob = opened.first.conversation
        val tag = { direction: Int, epoch: UInt -> tags.conversationTag(opened.secret, direction, epoch) }

        val frame = decode(alice.frame(assertNotNull(alice.draft(SECOND, EXPIRY)), epoch, HOP_LIMIT))

        assertContentEquals(tag(1, epoch), frame.header.destinationTag)
        assertContentEquals(tag(2, epoch), frame.header.sourceTag)
        assertContentEquals(tag(1, epoch + 1u), alice.destination(epoch + 1u))
        assertContentEquals(tag(2, epoch + 1u), alice.source(epoch + 1u))
        assertContentEquals(tag(2, epoch), bob.destination(epoch))
        assertContentEquals(tag(1, epoch), bob.source(epoch))
        listOf(epoch - 1u, epoch, epoch + 1u).forEach {
            assertTrue(alice.owns(tag(2, it), NOW), "epoch $it")
            assertTrue(bob.owns(tag(1, it), NOW), "epoch $it")
        }
        listOf(epoch - 2u, epoch + 2u).forEach { assertFalse(alice.owns(tag(2, it), NOW), "epoch $it") }
        assertFalse(alice.owns(tag(1, epoch), NOW))
        assertTrue(alice.owns(tag(2, epoch + 2u), NOW + Derivations.EPOCH_SECONDS))
    }

    @Test
    fun onlyTheFirstCopyOfMessageTwoIsAdopted() {
        val opened = Opened(WireProfile.CLASSICAL)

        opened.adopted()

        assertNull(opened.opening.adopt(opened.answer, NOW))
    }

    @Test
    fun aMessageTwoBreakingARuleIsNotAdoptedAndLeavesTheOpeningForTheRealOne() =
        profiles.forEach { profile ->
            val flaws =
                mapOf<String, (Forgery) -> ByteArray>(
                    "undecodable" to { byteArrayOf(DeliveryReceipt.TYPE.toByte()) },
                    "transport container" to { it.answer(container = Container.Transport(0u, ByteArray(17))) },
                    "other tombstone" to { it.answer(tombstone = Tombstone(ByteArray(ID) { 1 }, ByteArray(K))) },
                    "extra tombstone" to { it.answer(extra = Tombstone(ByteArray(ID) { 1 }, ByteArray(K))) },
                    "other message_ids" to { it.answer(listed = ByteArray(ID) { 1 }) },
                    "other message_id" to { it.answer(headerId = ByteArray(ID) { 2 }) },
                    "other expiry" to { it.answer(headerExpiry = EXPIRY + 1u) },
                    "no padding" to { it.answer(secured = false) },
                    "tampered" to { it.answer(tamper = true) },
                )

            assertNotNull(Forgery(Opened(profile)).let { it.opened.opening.adopt(it.answer(), NOW) }, "$profile")
            flaws.forEach { (flaw, bytes) ->
                val forgery = Forgery(Opened(profile))
                assertNull(forgery.opened.opening.adopt(bytes(forgery), NOW), "$profile: $flaw")
                assertNotNull(forgery.opened.opening.adopt(forgery.answer(), NOW), "$profile: real after $flaw")
            }
            val late = Opened(profile)
            assertNull(late.opening.adopt(late.answer, EXPIRY.toLong() + SKEW + 1), "$profile: expired")
            assertNotNull(late.opening.adopt(late.answer, NOW), "$profile: real after expired")
        }

    @Test
    fun aTransportMessageIsOpenedOnceAndAnyFlawedOneIsDropped() {
        val opened = Opened(WireProfile.CLASSICAL)
        val alice = opened.adopted()
        val bob = opened.first.conversation
        val draft = assertNotNull(alice.draft(SECOND, EXPIRY))
        val bytes = alice.frame(draft, epoch, HOP_LIMIT)
        val frame = decode(bytes)
        val reframe = { header: UnicastHeader, container: Container ->
            MessageFrame(header, frame.commitment, container).encode()
        }
        val transport = frame.container as Container.Transport
        val tampered = transport.ciphertext.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val flaws =
            mapOf(
                "undecodable" to byteArrayOf(MessageFrame.TYPE.toByte()),
                "first message" to opened.alice.codec.frame(opened.draft, epoch, HOP_LIMIT),
                "other tag" to reframe(frame.header.with(destination = bob.destination(epoch)), transport),
                "ik container" to reframe(frame.header, Container.IkInitiator(ByteArray(IK_MIN))),
                "tampered" to reframe(frame.header, Container.Transport(transport.nonce, tampered)),
            )
        // These open, so each spends its own frame's nonce.
        val other = decode(alice.frame(assertNotNull(alice.draft(SECOND, EXPIRY)), epoch, HOP_LIMIT))
        val late = alice.frame(assertNotNull(alice.draft(SECOND, EXPIRY)), epoch, HOP_LIMIT)

        flaws.forEach { (flaw, flawed) -> assertNull(bob.accept(flawed, NOW), flaw) }
        assertEquals(0, bob.received)
        val unbound = MessageFrame(other.header.with(expiry = EXPIRY + 1u), other.commitment, other.container)
        assertNull(bob.accept(unbound.encode(), NOW))
        assertNull(bob.accept(late, EXPIRY.toLong() + SKEW + 1), "expired")
        assertEquals(2, bob.received)
        assertNotNull(bob.accept(bytes, NOW))
        assertNull(bob.accept(bytes, NOW), "replayed")
        assertEquals(3, bob.received)
    }

    @Test
    fun aNonceBelowTheReplayWindowIsDropped() {
        val opened = Opened(WireProfile.CLASSICAL)
        val alice = opened.adopted()
        val bob = opened.first.conversation
        val frames = List(REPLAY_WINDOW + 1) { alice.frame(assertNotNull(alice.draft(SECOND, EXPIRY)), epoch, 1) }

        assertNotNull(bob.accept(frames.last(), NOW))

        assertNull(bob.accept(frames.first(), NOW))
        assertNotNull(bob.accept(frames[1], NOW))
    }

    @Test
    fun aTransportMessageWhosePlaintextIsNoSecuredMessageIsDropped() {
        val forgery = Forgery(Opened(WireProfile.CLASSICAL))
        val alice = assertNotNull(forgery.opened.opening.adopt(forgery.answer(), NOW))
        val k = ByteArray(K) { 4 }
        val content = MessageContent(RECEIPT_ID, EXPIRY, k, emptyList(), SECOND)
        val header = UnicastHeader(HOP_LIMIT, RECEIPT_ID, EXPIRY, alice.source(epoch), alice.destination(epoch))
        val frame = { plaintext: ByteArray ->
            MessageFrame(header, derivations.tombstoneCommitment(k), forgery.seal(plaintext)).encode()
        }

        assertNull(alice.accept(frame(content.encode(secured = false)), NOW), "unsecured padding")
        assertNotNull(alice.accept(frame(content.encode(secured = true)), NOW), "the same message, padded")
    }

    @Test
    fun aReceiptIsTakenOnceAndAnyFlawedOneIsIgnored() {
        val forgery = Forgery(Opened(WireProfile.CLASSICAL))
        val alice = assertNotNull(forgery.opened.opening.adopt(forgery.answer(), NOW))
        val tombstone = Tombstone(ByteArray(ID) { 3 }, ByteArray(K))
        val ids = listOf(Tlv(MESSAGE_IDS, tombstone.messageId))
        val header = UnicastHeader(HOP_LIMIT, RECEIPT_ID, EXPIRY, alice.source(epoch), alice.destination(epoch))
        val receipt = { content: ReceiptContent, tombstones: List<Tombstone>, header: UnicastHeader ->
            DeliveryReceipt(header, tombstones, forgery.seal(content.encode(secured = true))).encode()
        }
        val content = ReceiptContent(RECEIPT_ID, EXPIRY, ids)
        val valid = { receipt(content, listOf(tombstone), header) }
        val flaws =
            mapOf(
                "undecodable" to byteArrayOf(DeliveryReceipt.TYPE.toByte()),
                "answer" to forgery.opened.answer,
                "other tag" to receipt(content, listOf(tombstone), header.with(destination = ID_TAG)),
                "ik container" to
                    DeliveryReceipt(header, listOf(tombstone), Container.IkResponder(ByteArray(IK_MIN))).encode(),
                "other tombstones" to
                    receipt(content, listOf(Tombstone(ByteArray(ID), ByteArray(K))), header),
                "other message_id" to receipt(ReceiptContent(ANSWER_ID, EXPIRY, ids), listOf(tombstone), header),
                "not a receipt" to receipt(ReceiptContent(RECEIPT_ID, EXPIRY, emptyList()), listOf(tombstone), header),
                "unsecured padding" to
                    DeliveryReceipt(
                        header,
                        listOf(tombstone),
                        forgery.seal(content.encode(secured = false)),
                    ).encode(),
            )

        flaws.forEach { (flaw, flawed) -> assertNull(alice.acknowledged(flawed, NOW), flaw) }
        val late = valid()
        assertNull(alice.acknowledged(late, EXPIRY.toLong() + SKEW + 1), "expired")
        val bytes = valid()
        val acknowledged = assertNotNull(alice.acknowledged(bytes, NOW))
        assertEquals(listOf(tombstone.messageId.toList()), acknowledged.map { it.toList() })
        assertNull(alice.acknowledged(bytes, NOW), "replayed")
        // Four flawed receipts open before their checks fail, then the late and the valid one.
        assertEquals(6, alice.received)
    }

    @Test
    fun aConversationSealsNothingAtTheReservedNonce() {
        val key = ByteArray(KEY) { 1 }
        val spent = CipherState(crypto, key, ULong.MAX_VALUE)
        val session = EndToEndSession(ByteArray(KEY), ByteArray(KEY), spent, CipherState(crypto, key))
        val conversation = Conversation(crypto, session, key, ByteArray(NODE_ID), ByteArray(NODE_ID) { 1 }, true)

        assertNull(conversation.draft(SECOND, EXPIRY))
        assertNull(conversation.receipt(listOf(Tombstone(ByteArray(ID), ByteArray(K))), EXPIRY))
        assertEquals(0, conversation.sent)
    }

    @Test
    fun mutatedSessionFramesNeverThrowAndOnlyDeliverTheOriginalPayload() =
        profiles.forEach { profile ->
            val opened = Opened(profile)
            val alice = opened.adopted()
            val bob = opened.first.conversation
            val draft = assertNotNull(alice.draft(SECOND, EXPIRY))

            mutationInputs(alice.frame(draft, epoch, HOP_LIMIT)).forEach { input ->
                bob.accept(input, NOW)?.let { assertContentEquals(SECOND, it.payload, "$profile") }
            }
            mutationInputs(opened.answer).forEach { Forgery(Opened(profile)).opened.opening.adopt(it, NOW) }
        }

    private fun decode(bytes: ByteArray): MessageFrame = (MessageFrame.decode(bytes) as Decoded.Valid).value

    private fun UnicastHeader.with(
        destination: ByteArray = destinationTag,
        expiry: UInt = this.expiry,
    ) = UnicastHeader(hopLimit, messageId, expiry, destination, sourceTag)

    // Bob's side written out by hand, so each test can break one rule of message 2.
    private inner class Forgery(
        val opened: Opened,
    ) {
        private val tombstone = opened.first.message.tombstone
        private var session: EndToEndSession? = null

        // [plaintext] sealed in the session of the last [answer].
        fun seal(plaintext: ByteArray): Container.Transport = assertNotNull(assertNotNull(session).seal(plaintext))

        fun answer(
            listed: ByteArray = this.tombstone.messageId,
            headerId: ByteArray = ANSWER_ID,
            headerExpiry: UInt = EXPIRY,
            tombstone: Tombstone = this.tombstone,
            extra: Tombstone? = null,
            secured: Boolean = true,
            tamper: Boolean = false,
            container: Container? = null,
        ): ByteArray {
            val handshake = responder(opened)
            val first = decode(opened.alice.codec.frame(opened.draft, epoch, HOP_LIMIT))
            handshake.readMessage((first.container as Container.IkInitiator).noiseMessage)
            val plaintext = ReceiptContent(ANSWER_ID, EXPIRY, listOf(Tlv(MESSAGE_IDS, listed))).encode(secured)
            val message = (handshake.writeMessage(plaintext) as NoiseResult.Ok).value
            if (tamper) message[message.size - 1] = (message.last() + 1).toByte()
            session = (handshake.session() as NoiseResult.Ok).value
            val header = UnicastHeader(HOP_LIMIT, headerId, headerExpiry, ByteArray(TAG), ByteArray(TAG))
            val tombstones = listOfNotNull(tombstone, extra)
            return DeliveryReceipt(header, tombstones, container ?: Container.IkResponder(message)).encode()
        }
    }

    private companion object {
        const val NOW = 1_798_762_200L
        const val KEY = 32
        const val NODE_ID = 12
        const val ID = 12
        const val K = 16
        const val TAG = 8
        const val IK_MIN = 48
        const val HOP_LIMIT = 255
        const val SKEW = 30
        const val REPLAY_WINDOW = 1_024
        const val CONVERSATION_SEED = 0x82
        const val MESSAGE_IDS = 0x83
        val EXPIRY = (NOW + 300).toUInt()
        val PAYLOAD = "hello".encodeToByteArray()
        val SECOND = "second".encodeToByteArray()
        val THIRD = "third".encodeToByteArray()
        val ANSWER_ID = ByteArray(ID) { 9 }
        val RECEIPT_ID = ByteArray(ID) { 8 }
        val ID_TAG = ByteArray(TAG) { 6 }
    }
}
