package ch.trancee.meshlink.internal

import ch.trancee.meshlink.internal.Derivations.Companion.ROUTING_TAG_LENGTH

/**
 * The end-to-end routing derivations (spec "Routing tags" and "Conversation tags", D-32): a node's
 * public tag and the tags of a conversation, computed with [derivations].
 */
public class EndToEndTags(
    private val derivations: Derivations,
) {
    /** The public routing tag of a static [x25519Key]: `trunc_8(H("public-tag", x25519_key ‖ u32(epoch)))`. */
    public fun publicTag(
        x25519Key: ByteArray,
        epoch: UInt,
    ): ByteArray = derivations.hash(Domain.PUBLIC_TAG, x25519Key + u32(epoch)).copyOf(ROUTING_TAG_LENGTH)

    /** `KDF(seed, "", "conversation", initiator NodeId ‖ responder NodeId, 32)` (D-32). */
    public fun conversationSecret(
        seed: ByteArray,
        initiatorNodeId: ByteArray,
        responderNodeId: ByteArray,
    ): ByteArray = derivations.kdf(seed, ByteArray(0), Domain.CONVERSATION, initiatorNodeId + responderNodeId)

    /** `tag(d) = trunc_8(H("conversation-tag", u8(d) ‖ u32(epoch); key = secret))` for direction [direction]. */
    public fun conversationTag(
        secret: ByteArray,
        direction: Int,
        epoch: UInt,
    ): ByteArray {
        val input = byteArrayOf(direction.toByte()) + u32(epoch)
        return derivations.hash(Domain.CONVERSATION_TAG, input, secret).copyOf(ROUTING_TAG_LENGTH)
    }
}
