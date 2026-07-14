package ch.trancee.meshlink.engine.internal

import ch.trancee.meshlink.identity.toHexString

/**
 * Number of leading bytes from an unexpected/malformed inbound payload surfaced in diagnostic
 * metadata as [unexpectedFramePrefixHex] -- enough to distinguish genuinely different unexpected
 * payloads (protocol confusion, truncation, garbage) from each other without needing a raw wire
 * trace, but bounded so a diagnostic event never echoes an entire oversized or malicious payload.
 */
internal const val UNEXPECTED_FRAME_HEX_SNIPPET_BYTES: Int = 16

/**
 * Returns a hex-encoded snippet of this byte array's first [UNEXPECTED_FRAME_HEX_SNIPPET_BYTES]
 * bytes (or fewer, if shorter), suitable for inclusion in diagnostic metadata when an inbound
 * frame's payload didn't match what was expected at a given handshake/dispatch stage. Declared once
 * here so the several `payloadPrefixHex` diagnostic call sites share one canonical
 * truncation/encoding instead of each repeating the same `copyOf(minOf(...))`/`toHexString()`
 * pattern inline.
 */
internal fun ByteArray.unexpectedFramePrefixHex(): String {
    return copyOf(minOf(size, UNEXPECTED_FRAME_HEX_SNIPPET_BYTES)).toHexString()
}
