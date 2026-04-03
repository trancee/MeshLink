package io.meshlink.model

import io.meshlink.crypto.secureRandomBytes
import io.meshlink.util.hexToBytes
import io.meshlink.util.toHex
import kotlin.jvm.JvmInline

/**
 * Type-safe peer identifier wrapping the hex-encoded peer ID string.
 * Using hex (String) as the backing value ensures correct equals/hashCode
 * for use as map keys — unlike ByteArray which uses reference equality.
 */
@JvmInline
value class PeerId(val hex: String) {
    val bytes: ByteArray get() = hexToBytes(hex)
    override fun toString(): String = hex

    companion object {
        fun fromBytes(bytes: ByteArray): PeerId = PeerId(bytes.toHex())
    }
}

/**
 * Type-safe 16-byte message identifier generated from the platform CSPRNG.
 * 128-bit randomness provides a birthday-bound collision probability of ~2⁻⁶⁴.
 * Use [MessageIdGenerator] for wire message IDs and [random] for
 * non-wire identifiers (failure tracking, loopback).
 */
@JvmInline
value class MessageId(val hex: String) {
    val bytes: ByteArray get() = hexToBytes(hex)
    override fun toString(): String = hex

    companion object {
        const val SIZE = 16

        fun fromBytes(bytes: ByteArray): MessageId {
            require(bytes.size == SIZE) { "MessageId must be $SIZE bytes, got ${bytes.size}" }
            return MessageId(bytes.toHex())
        }

        fun random(): MessageId = fromBytes(secureRandomBytes(SIZE))
    }
}
