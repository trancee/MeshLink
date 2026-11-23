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
        val message = seal(responder, plaintext) ?: return null
        val responderNodeId = derivations.nodeId(responder.ed25519Key)
        val conversation = tags.conversationSecret(seed, keys.nodeId, responderNodeId)
        val commitment = derivations.tombstoneCommitment(k)
        val container = Container.IkInitiator(message)
        return Draft(responder.x25519Key, messageId, expiry, commitment, container, conversation)
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

    /** [draft]'s source tag for [epoch]: its conversation's tag for direction `0x02`, the reply-to tag. */
    public fun sourceTag(
        draft: Draft,
        epoch: UInt,
    ): ByteArray = tags.conversationTag(draft.conversation, REPLY_DIRECTION, epoch)

    /** The public tag of [certificate]'s X25519 key for [epoch]. */
    public fun publicTag(
        certificate: IdentityCertificate,
        epoch: UInt,
    ): ByteArray = tags.publicTag(certificate.x25519Key, epoch)

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
     * Noise remote static key.
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
        return (decoded as? Decoded.Valid)?.let { Opened(remoteStatic, it.value) }
    }

    // Message 1 to [responder] carrying [plaintext], sealed with HPKE first in PostQuantum.
    private fun seal(
        responder: IdentityCertificate,
        plaintext: ByteArray,
    ): ByteArray? {
        val noiseKeys = NoiseKeys(keys.static, remoteStatic = responder.x25519Key)
        val handshake = NoiseHandshake(crypto, pattern, NoiseRole.INITIATOR, prologue, noiseKeys)
        val xWingKey = responder.xWingKey
        val payload =
            if (xWingKey == null) {
                plaintext
            } else {
                val info = Derivations.hpkeInfo(keys.static.publicKey, derivations.nodeId(responder.ed25519Key))
                (Hpke(crypto).seal(xWingKey, info, plaintext) as? CryptoResult.Ok)?.value
            }
        val written = payload?.let { handshake.writeMessage(it) } as? NoiseResult.Ok
        return written?.value
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
        val bytes = types[SENDER_CERTIFICATE]?.let { it.value.takeIf { CONVERSATION_SEED in types } }
        val readReceipt = READ_RECEIPT_REQUESTED in types
        return bytes?.let { certified(it, opened.remoteStatic) }?.let {
            FirstDelivery(derivations.nodeId(it.ed25519Key), it, opened.content.payload, readReceipt)
        }
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

    // A message 1 that opened: its sender's [remoteStatic] key and its plaintext [content].
    private class Opened(
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
 * An opened first message: the [sender]'s NodeId and validated [certificate], the app [payload], and
 * whether the sender asked for a [readReceipt].
 */
public class FirstDelivery(
    public val sender: ByteArray,
    public val certificate: IdentityCertificate,
    public val payload: ByteArray,
    public val readReceipt: Boolean,
)
