package io.meshlink.model

import io.meshlink.util.hexToBytes
import io.meshlink.util.toHex
import kotlin.jvm.JvmInline
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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
 * Type-safe message identifier wrapping the hex-encoded message ID string.
 * Provides [toUuid] for Kotlin UUID interop and [random] factory.
 */
@JvmInline
value class MessageId(val hex: String) {
    val bytes: ByteArray get() = hexToBytes(hex)
    @OptIn(ExperimentalUuidApi::class)
    fun toUuid(): Uuid = Uuid.fromByteArray(bytes)
    override fun toString(): String = hex

    companion object {
        fun fromBytes(bytes: ByteArray): MessageId = MessageId(bytes.toHex())
        @OptIn(ExperimentalUuidApi::class)
        fun random(): MessageId = fromBytes(Uuid.random().toByteArray())
    }
}
