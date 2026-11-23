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
    private val clock: Clock,
    private val keys: SecuredKeys,
) {
    private val derivations = Derivations(crypto)
    private val tags = EndToEndTags(derivations)
    private val profile = keys.certificate.profile
    private val pattern = if (profile == WireProfile.POST_QUANTUM) NoisePattern.IK_HFS else NoisePattern.IK
    private val prologue = Derivations.e2ePrologue(PROTOCOL_VERSION, profile)
    private var windowEpoch: UInt? = null
    private var window: List<ByteArray> = emptyList()

    /**
     * A new first message of [payload] to the holder of [responder], with a fresh `message_id`,
     * tombstone secret and conversation seed; `null` if [responder]'s keys can't be sealed to.
     *
     * @param payload at most [PAYLOAD_MAX] bytes, so the frame is a small frame.
     * @param expiry Unix seconds; the caller adds the message lifetime to the current time.
     * @param readReceipt whether the plaintext asks for a read receipt.
     */
    public fun draft(
        responder: IdentityCertificate,
        payload: ByteArray,
        expiry: UInt,
        readReceipt: Boolean,
    ): Draft? {
        require(payload.size <= PAYLOAD_MAX) { "payload must be at most $PAYLOAD_MAX bytes, not ${payload.size}" }
        val messageId = crypto.randomBytes(MESSAGE_ID)
        val k = crypto.randomBytes(K)
        val seed = crypto.randomBytes(SEED)
        val extensions =
            listOfNotNull(
                Tlv(SENDER_CERTIFICATE, keys.certificate.encode()),
                Tlv(READ_RECEIPT_REQUESTED, ByteArray(0)).takeIf { readReceipt },
                Tlv(CONVERSATION_SEED, seed),
            )
        val plaintext = MessageContent(messageId, expiry, k, extensions, payload).encode(secured = true)
        // Fixed ephemeral keys, so the opening can replay message 1 for each message 2 it tries.
        val kem = crypto.mlKem768KeyPair().takeIf { pattern == NoisePattern.IK_HFS }
        val ephemeral = crypto.x25519KeyPair()
        val noiseKeys =
            NoiseKeys(keys.static, ephemeral = ephemeral, kemKeyPair = kem, remoteStatic = responder.x25519Key)
        val start = { NoiseHandshake(crypto, pattern, NoiseRole.INITIATOR, prologue, noiseKeys) }
        val written =
            seal(responder, plaintext)?.let { sealed ->
                (start().writeMessage(sealed) as? NoiseResult.Ok)?.let { sealed to it.value }
            } ?: return null
        val (sealed, message) = written
        val responderNodeId = derivations.nodeId(responder.ed25519Key)
        val secret = tags.conversationSecret(seed, keys.nodeId, responderNodeId)
        val replay = { start().also { it.writeMessage(sealed) } }
        val opening = Opening(crypto, replay, secret, keys.nodeId, responderNodeId, messageId)
        val commitment = derivations.tombstoneCommitment(k)
        return Draft(responder.x25519Key, messageId, expiry, commitment, Container.IkInitiator(message), opening)
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
     * Whether [tag] is this node's public tag for epoch e − 1, e or e + 1, where e is the epoch of
     * [nowSeconds].
     */
    public fun owns(
        tag: ByteArray,
        nowSeconds: Long,
    ): Boolean {
        val epoch = Derivations.epoch(nowSeconds)
        if (epoch != windowEpoch) {
            window = listOf(epoch - 1u, epoch, epoch + 1u).map { tags.publicTag(keys.static.publicKey, it) }
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
     * (spec "Answering"); `null` if message 2 can't be written.
     */
    public fun accept(
        bytes: ByteArray,
        nowSeconds: Long,
    ): FirstDelivery? {
        val frame = (MessageFrame.decode(bytes) as? Decoded.Valid)?.value
        val owned = frame?.takeIf { owns(it.header.destinationTag, nowSeconds) } ?: return null
        val opened = (owned.container as? Container.IkInitiator)?.let { read(it) }
        return opened?.takeIf { derivations.bound(owned, it.content, nowSeconds) }?.let { delivery(it) }
    }

    // The content of message 1 [message] to this node and its sender's static key; `null` if it doesn't open.
    private fun read(message: Container.IkInitiator): Opened? {
        val handshake = NoiseHandshake(crypto, pattern, NoiseRole.RESPONDER, prologue, NoiseKeys(keys.static))
        val read = handshake.readMessage(message.noiseMessage) as? NoiseResult.Ok ?: return null
        val remoteStatic = handshake.remoteStaticKey
        val decoded = open(remoteStatic, read.value)?.let { MessageContent.decode(it, secured = true) }
        return (decoded as? Decoded.Valid)?.let { Opened(handshake, remoteStatic, it.value) }
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

    // The delivery of [opened] if it carries a seed and a valid sender certificate for its static key.
    private fun delivery(opened: Opened): FirstDelivery? {
        val types = opened.content.extensions.associateBy { it.type }
        val readReceipt = READ_RECEIPT_REQUESTED in types
        return types[CONVERSATION_SEED]?.let { seed ->
            val certificate = types[SENDER_CERTIFICATE]?.let { certified(it.value, opened.remoteStatic) }
            certificate?.let { answered(opened, it, seed.value, readReceipt) }
        }
    }

    // [opened] from the holder of [sender] with its answer: a receipt for it as message 2, after which
    // the responder's session splits. `null` if message 2 can't be written.
    private fun answered(
        opened: Opened,
        sender: IdentityCertificate,
        seed: ByteArray,
        readReceipt: Boolean,
    ): FirstDelivery? {
        val content = opened.content
        val senderNodeId = derivations.nodeId(sender.ed25519Key)
        val tombstone = Tombstone(content.messageId, content.k)
        val receiptId = crypto.randomBytes(MESSAGE_ID)
        val plaintext = receiptPlaintext(receiptId, content.expiry, listOf(tombstone), secured = true)
        val written = opened.handshake.writeMessage(plaintext) as? NoiseResult.Ok ?: return null
        val session = (opened.handshake.session() as NoiseResult.Ok).value
        val secret = tags.conversationSecret(seed, senderNodeId, keys.nodeId)
        val conversation = Conversation(crypto, session, secret, senderNodeId, keys.nodeId, initiator = false)
        val container = Container.IkResponder(written.value)
        val answer = ReceiptDraft(senderNodeId, receiptId, content.expiry, listOf(tombstone), container)
        val message = Delivery(senderNodeId, content.payload, tombstone, content.expiry)
        return FirstDelivery(sender, message, readReceipt, conversation, answer)
    }

    // The certificate encoded in [bytes] if it is valid and binds [remoteStatic].
    private fun certified(
        bytes: ByteArray,
        remoteStatic: ByteArray,
    ): IdentityCertificate? {
        val decoded = IdentityCertificate.decode(bytes) as? Decoded.Valid ?: return null
        val sender = derivations.nodeId(decoded.value.ed25519Key)
        val check = IdentityCertificate.validate(crypto, bytes, profile, sender, clock) as? CertificateCheck.Valid
        return check?.let { it.certificate.takeIf { held -> held.x25519Key.contentEquals(remoteStatic) } }
    }

    // A message 1 that opened: the responder's [handshake], its sender's [remoteStatic] key and its plaintext [content].
    private class Opened(
        val handshake: NoiseHandshake,
        val remoteStatic: ByteArray,
        val content: MessageContent,
    )

    public companion object {
        /** The largest payload, in bytes: `unicast-payload-small-max`, as [UnsecuredMessaging.PAYLOAD_MAX]. */
        public const val PAYLOAD_MAX: Int = UnsecuredMessaging.PAYLOAD_MAX

        private const val PROTOCOL_VERSION = 1
        private const val MESSAGE_ID = 12
        private const val K = 16
        private const val SEED = 32
        private const val REPLY_DIRECTION = 0x02
        private const val SENDER_CERTIFICATE = 0x01
        private const val READ_RECEIPT_REQUESTED = 0x04
        private const val CONVERSATION_SEED = 0x82
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
 * NodeId, whether the sender asked for a [readReceipt], the responder's [conversation], and its
 * [answer]: the delivery receipt that carries message 2, which [conversation] frames and which is
 * resent unchanged for every later copy (spec "Answering").
 */
public class FirstDelivery(
    public val certificate: IdentityCertificate,
    public val message: Delivery,
    public val readReceipt: Boolean,
    public val conversation: Conversation,
    public val answer: ReceiptDraft,
)
