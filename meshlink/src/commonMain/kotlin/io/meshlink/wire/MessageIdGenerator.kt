package io.meshlink.wire

import io.meshlink.model.MessageId

/**
 * Generates 16-byte random message IDs using the platform CSPRNG.
 *
 * With 128 bits of randomness, the birthday-bound collision probability
 * is ~2⁻⁶⁴ — negligible for any practical message volume. No counter
 * or peer ID prefix is needed.
 */
class MessageIdGenerator {

    fun generate(): MessageId = MessageId.random()
}
