package ch.trancee.meshlink.internal

/**
 * A secured node's first messages (spec "First messages", "Plaintext extensions"): seals a message
 * to a responder's certificate as `IK` message 1 (`Classical`) or `IKhfs` message 1 with an HPKE seal
 * (`PostQuantum`), tags each attempt for its epoch, and opens the first messages sent to this node's
 * [keys].
 *
 * Not thread-safe: [owns] caches this node's public tags. A node calls it from its own dispatcher.
 */
public class FirstMessages(
    private val crypto: CryptoProvider,
    clock: Clock,
    private val keys: SecuredKeys,
) {
    private val derivations = Derivations(crypto)
    private val tags = EndToEndTags(derivations)
    private val one = MessageOne(crypto, clock, keys)
    private var windowEpoch: UInt? = null
    private var window: List<ByteArray> = emptyList()

    /**
     * A new first message of [payload] to the holder of [responder], with [tombstone]'s `message_id` and
     * secret, fresh ones if `null`, and a fresh conversation seed; `null` if [responder]'s keys can't be
     * sealed to.
     *
     * @param payload at most [MessageContent.PAYLOAD_MAX] bytes; above [MessageContent.PAYLOAD_SMALL_MAX] the
     *   frame is a large frame.
     * @param expiry Unix seconds; the caller adds the message lifetime to the current time.
     * @param readReceipt whether the plaintext asks for a read receipt.
     * @param tombstone the message's tombstone when it is sealed again after its session lapsed (spec
     *   "Sessions"), so its destination takes it once.
     */
    public fun draft(
        responder: IdentityCertificate,
        payload: ByteArray,
        expiry: UInt,
        readReceipt: Boolean,
        tombstone: Tombstone? = null,
    ): Draft? {
        val kept = tombstone ?: Tombstone(crypto.randomBytes(MESSAGE_ID), crypto.randomBytes(K))
        val messageId = kept.messageId
        val k = kept.k
        val seed = crypto.randomBytes(SEED)
        val extensions =
            listOfNotNull(
                Tlv(SENDER_CERTIFICATE, keys.certificate.encode()),
                Tlv(READ_RECEIPT_REQUESTED, ByteArray(0)).takeIf { readReceipt },
                Tlv(CONVERSATION_SEED, seed),
            )
        val plaintext = MessageContent(messageId, expiry, k, extensions, payload).encode(secured = true)
        val (message, replay) = one.write(responder, plaintext) ?: return null
        val responderNodeId = derivations.nodeId(responder.ed25519Key)
        val secret = tags.conversationSecret(seed, keys.nodeId, responderNodeId)
        val opening = Opening(crypto, replay, secret, keys.certificate, responderNodeId, messageId)
        val commitment = derivations.tombstoneCommitment(k)
        return Draft(responder.x25519Key, kept, expiry, commitment, Container.IkInitiator(message), opening)
    }

    /** The `MESSAGE` bytes of [draft] tagged for [epoch] with [hopLimit]; a retry re-tags the same container. */
    public fun frame(
        draft: Draft,
        epoch: UInt,
        hopLimit: Int,
    ): ByteArray {
        val destination = tags.publicTag(draft.destination, epoch)
        val header = UnicastHeader(hopLimit, draft.messageId, draft.expiry, destination, sourceTag(draft, epoch))
        return MessageFrame(header, draft.commitment, draft.container).encode()
    }

    /**
     * The source tag for [epoch] of [draft], a first message of this codec's: its conversation's tag for
     * direction `0x02`, the reply-to tag.
     */
    public fun sourceTag(
        draft: Draft,
        epoch: UInt,
    ): ByteArray = tags.conversationTag((draft.sealer as Opening).secret, REPLY_DIRECTION, epoch)

    /**
     * Whether [tag] is this node's public tag for an epoch [Derivations.recognized] at
     * [nowSeconds].
     */
    public fun owns(
        tag: ByteArray,
        nowSeconds: Long,
    ): Boolean {
        val epoch = Derivations.epoch(nowSeconds)
        if (epoch != windowEpoch) {
            window = Derivations.recognized(nowSeconds).map { tags.publicTag(keys.static.publicKey, it) }
            windowEpoch = epoch
        }
        return window.any { it.contentEquals(tag) }
    }

    /**
     * The first message in [bytes] if it is for this node, or `null` to drop it. It must decode,
     * carry one of this node's public tags ([owns]), read as message 1 to this node's static key,
     * open its HPKE seal in `PostQuantum`, and hold a plaintext that keeps every MUST: aligned zero
     * padding, the header's `message_id` and `expiry`, the commitment to its `k`, an unexpired
     * `expiry`, a `CONVERSATION_SEED`, and a valid `SENDER_CERTIFICATE` whose X25519 key is the
     * Noise remote static key. Its answer, message 2, is written and the responder's session split
     * (spec "Answering"); `null` if message 2 can't be written. The answer carries [current], this node's
     * newest certificate, when the sender's copy of [keys]' is due a [refresh][IdentityCertificate.refresh].
     */
    public fun accept(
        bytes: ByteArray,
        nowSeconds: Long,
        current: IdentityCertificate = keys.certificate,
    ): FirstDelivery? {
        val frame = (MessageFrame.decode(bytes) as? Decoded.Valid)?.value
        val owned = frame?.takeIf { owns(it.header.destinationTag, nowSeconds) } ?: return null
        val opened = (owned.container as? Container.IkInitiator)?.let { one.read(it) }
        return opened?.let { message ->
            val content = (MessageContent.decode(message.plaintext, secured = true) as? Decoded.Valid)?.value
            content?.takeIf { derivations.bound(owned, it, nowSeconds) }?.let {
                delivery(message, it, keys.certificate.refresh(current, nowSeconds))
            }
        }
    }

    /**
     * A fire-and-forget `READ_RECEIPT` to the holder of [responder] listing [messageIds] in the order
     * they were read, for when no session with it is open (spec "Read receipts"): sealed like a first
     * message with this node's `SENDER_CERTIFICATE` and a fresh `CONVERSATION_SEED` for its reply-to
     * tag, but answered by nothing. `null` if [responder]'s keys can't be sealed to.
     *
     * @param messageIds 1 to [ReadReceipt.IDS_MAX] message IDs.
     */
    public fun readReceipt(
        responder: IdentityCertificate,
        messageIds: List<ByteArray>,
        expiry: UInt,
    ): ReadDraft? {
        val messageId = crypto.randomBytes(MESSAGE_ID)
        val seed = crypto.randomBytes(SEED)
        val extensions = listOf(Tlv(SENDER_CERTIFICATE, keys.certificate.encode()), Tlv(CONVERSATION_SEED, seed))
        val plaintext = readPlaintext(messageId, expiry, messageIds, extensions, secured = true)
        val (message, _) = one.write(responder, plaintext) ?: return null
        val secret = tags.conversationSecret(seed, keys.nodeId, derivations.nodeId(responder.ed25519Key))
        return ReadDraft(
            messageId,
            expiry,
            Container.IkInitiator(message),
            { tags.publicTag(responder.x25519Key, it) },
            { tags.conversationTag(secret, REPLY_DIRECTION, it) },
        )
    }

    /**
     * What the fire-and-forget `READ_RECEIPT` in [bytes] reports read, or `null` to ignore it. It must
     * decode, carry one of this node's public tags ([owns]), read as message 1 to this node's static
     * key like [accept], and hold an aligned, padded read receipt plaintext as [readIds] checks with a
     * `CONVERSATION_SEED` and a valid `SENDER_CERTIFICATE` whose X25519 key is the Noise remote static
     * key. Nothing answers it.
     */
    public fun read(
        bytes: ByteArray,
        nowSeconds: Long,
    ): Acknowledgement? {
        val frame = (ReadReceipt.decode(bytes) as? Decoded.Valid)?.value
        val owned = frame?.takeIf { owns(it.header.destinationTag, nowSeconds) } ?: return null
        val opened = (owned.container as? Container.IkInitiator)?.let { one.read(it) }
        return opened?.let { message ->
            val content = (ReceiptContent.decode(message.plaintext, secured = true) as? Decoded.Valid)?.value
            content?.let { read ->
                sender(message, read.extensions.associateBy { it.type })?.let { sender ->
                    readIds(owned.header, read, nowSeconds)?.let {
                        Acknowledgement(derivations.nodeId(sender.ed25519Key), it)
                    }
                }
            }
        }
    }

    // The delivery of [content], opened from [opened], if it carries a seed and a valid sender
    // certificate for its static key; its answer carries [refresh] if given.
    private fun delivery(
        opened: MessageOne.Opened,
        content: MessageContent,
        refresh: IdentityCertificate?,
    ): FirstDelivery? {
        val types = content.extensions.associateBy { it.type }
        return sender(opened, types)?.let {
            val seed = types.getValue(CONVERSATION_SEED).value
            answered(opened.handshake, content, it, seed, refresh)
        }
    }

    // The valid sender certificate for [opened]'s static key among [types], if they also hold a seed.
    private fun sender(
        opened: MessageOne.Opened,
        types: Map<Int, Tlv>,
    ): IdentityCertificate? =
        types[SENDER_CERTIFICATE]?.takeIf { CONVERSATION_SEED in types }?.let {
            one.certified(it.value, opened.remoteStatic)
        }

    // [content] from the holder of [sender] with its answer: a receipt for it as message 2, written
    // with [handshake] and carrying [refresh] if given, after which the responder's session splits.
    // `null` if message 2 can't be written.
    private fun answered(
        handshake: NoiseHandshake,
        content: MessageContent,
        sender: IdentityCertificate,
        seed: ByteArray,
        refresh: IdentityCertificate?,
    ): FirstDelivery? {
        val senderNodeId = derivations.nodeId(sender.ed25519Key)
        val tombstone = Tombstone(content.messageId, content.k)
        val receiptId = crypto.randomBytes(MESSAGE_ID)
        val plaintext = receiptPlaintext(receiptId, content.expiry, listOf(tombstone), secured = true, refresh)
        val written = handshake.writeMessage(plaintext) as? NoiseResult.Ok ?: return null
        val session = (handshake.session() as NoiseResult.Ok).value
        val secret = tags.conversationSecret(seed, senderNodeId, keys.nodeId)
        val conversation = Conversation(crypto, session, secret, keys.certificate, senderNodeId, initiator = false)
        val container = Container.IkResponder(written.value)
        val answer = ReceiptDraft(senderNodeId, receiptId, content.expiry, listOf(tombstone), container)
        val message = Delivery(senderNodeId, content.payload, tombstone, content.expiry, content.readReceiptRequested)
        return FirstDelivery(sender, message, conversation, answer)
    }

    public companion object {
        private const val MESSAGE_ID = 12
        private const val K = 16
        private const val SEED = 32
        private const val REPLY_DIRECTION = 0x02
        private const val SENDER_CERTIFICATE = 0x01
        private const val CONVERSATION_SEED = 0x82
    }
}

/**
 * `IK` or `IKhfs` message 1 for a secured node's [keys] (spec "First messages"): writes it to a
 * responder, with an HPKE seal in `PostQuantum`, and reads the ones sent to this node.
 */
internal class MessageOne(
    private val crypto: CryptoProvider,
    private val clock: Clock,
    private val keys: SecuredKeys,
) {
    private val derivations = Derivations(crypto)
    private val profile = keys.certificate.profile
    private val pattern = if (profile == WireProfile.POST_QUANTUM) NoisePattern.IK_HFS else NoisePattern.IK
    private val prologue = Derivations.e2ePrologue(PROTOCOL_VERSION, profile)

    /**
     * Message 1 to the holder of [responder] carrying [plaintext], and a replay that gives the
     * initiator's state after it afresh; `null` if [responder]'s keys can't be sealed to.
     */
    fun write(
        responder: IdentityCertificate,
        plaintext: ByteArray,
    ): Pair<ByteArray, () -> NoiseHandshake>? {
        // Fixed ephemeral keys, so the opening can replay message 1 for each message 2 it tries.
        val kem = crypto.mlKem768KeyPair().takeIf { pattern == NoisePattern.IK_HFS }
        val ephemeral = crypto.x25519KeyPair()
        val noiseKeys =
            NoiseKeys(keys.static, ephemeral = ephemeral, kemKeyPair = kem, remoteStatic = responder.x25519Key)
        val start = { NoiseHandshake(crypto, pattern, NoiseRole.INITIATOR, prologue, noiseKeys) }
        val sealed = seal(responder, plaintext) ?: return null
        val message = (start().writeMessage(sealed) as? NoiseResult.Ok)?.value
        return message?.let { it to { start().also { replay -> replay.writeMessage(sealed) } } }
    }

    /** Message 1 [message] to this node, opened; `null` if it doesn't read or its HPKE seal doesn't open. */
    fun read(message: Container.IkInitiator): Opened? {
        val handshake = NoiseHandshake(crypto, pattern, NoiseRole.RESPONDER, prologue, NoiseKeys(keys.static))
        val read = handshake.readMessage(message.noiseMessage) as? NoiseResult.Ok ?: return null
        val remoteStatic = handshake.remoteStaticKey
        return open(remoteStatic, read.value)?.let { Opened(handshake, remoteStatic, it) }
    }

    /** The certificate encoded in [bytes] if it is valid and binds [remoteStatic]. */
    fun certified(
        bytes: ByteArray,
        remoteStatic: ByteArray,
    ): IdentityCertificate? {
        val decoded = IdentityCertificate.decode(bytes) as? Decoded.Valid ?: return null
        val sender = derivations.nodeId(decoded.value.ed25519Key)
        val check = IdentityCertificate.validate(crypto, bytes, profile, sender, clock) as? CertificateCheck.Valid
        return check?.let { it.certificate.takeIf { held -> held.x25519Key.contentEquals(remoteStatic) } }
    }

    // The message 1 payload to [responder] carrying [plaintext]: sealed with HPKE in PostQuantum.
    private fun seal(
        responder: IdentityCertificate,
        plaintext: ByteArray,
    ): ByteArray? {
        val xWingKey = responder.xWingKey ?: return plaintext
        val info = Derivations.hpkeInfo(keys.static.publicKey, derivations.nodeId(responder.ed25519Key))
        return (Hpke(crypto).seal(xWingKey, info, plaintext) as? CryptoResult.Ok)?.value
    }

    // The padded plaintext of a message 1 [payload] from [remoteStatic]: opened in PostQuantum.
    private fun open(
        remoteStatic: ByteArray,
        payload: ByteArray,
    ): ByteArray? {
        val xWing = keys.xWing ?: return payload
        val info = Derivations.hpkeInfo(remoteStatic, keys.nodeId)
        return (Hpke(crypto).open(xWing.secretKey, info, payload) as? CryptoResult.Ok)?.value
    }

    /**
     * A message 1 that opened: the responder's [handshake], its sender's [remoteStatic] key and its
     * padded [plaintext].
     */
    class Opened(
        val handshake: NoiseHandshake,
        val remoteStatic: ByteArray,
        val plaintext: ByteArray,
    )

    private companion object {
        const val PROTOCOL_VERSION = 1
    }
}

/**
 * A secured node's end-to-end keys for one run: its [nodeId], X25519 [static] key pair, X-Wing key
 * pair in `PostQuantum` only, and the [certificate] that binds their public keys.
 */
public class SecuredKeys(
    public val nodeId: ByteArray,
    public val static: KeyPair,
    public val xWing: KeyPair?,
    public val certificate: IdentityCertificate,
)

/**
 * An opened first message: the sender's validated [certificate], the [message] with the sender's
 * NodeId, the responder's [conversation], and its [answer]: the delivery receipt that carries
 * message 2, which [conversation] frames and which is resent unchanged for every later copy (spec
 * "Answering").
 */
public class FirstDelivery(
    public val certificate: IdentityCertificate,
    public val message: Delivery,
    public val conversation: Conversation,
    public val answer: ReceiptDraft,
)
