package ch.trancee.meshlink.api

import kotlin.jvm.JvmInline

/**
 * Hex-encoded peer identity string used in [DiagnosticPayload] fields.
 *
 * When [DiagnosticsConfig.redactPeerIds] is enabled the underlying [hex] value is replaced with a
 * truncated SHA-256 hash before the event is emitted by [DiagnosticSink].
 */
@JvmInline public value class PeerIdHex(public val hex: String)

/**
 * Converts this [ByteArray] to a [PeerIdHex] by hex-encoding each byte (lowercase, zero-padded).
 *
 * Used at every diagnostic emit site to convert raw peer identity bytes into the hex-string wrapper
 * expected by [DiagnosticPayload] fields.
 */
internal fun ByteArray.toPeerIdHex(): PeerIdHex =
    PeerIdHex(joinToString("") { it.toUByte().toString(16).padStart(2, '0') })
