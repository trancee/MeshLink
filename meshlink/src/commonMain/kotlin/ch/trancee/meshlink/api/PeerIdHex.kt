package ch.trancee.meshlink.api

/**
 * Hex-encoded peer identity string used in [DiagnosticPayload] fields.
 *
 * When [DiagnosticsConfig.redactPeerIds] is enabled the underlying [hex] value is replaced with a
 * truncated SHA-256 hash before the event is emitted by [DiagnosticSink].
 */
@JvmInline public value class PeerIdHex(public val hex: String)
