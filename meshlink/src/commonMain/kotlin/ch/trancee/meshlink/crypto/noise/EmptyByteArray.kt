package ch.trancee.meshlink.crypto.noise

/**
 * Shared zero-length byte array singleton. Avoids allocating a fresh `ByteArray(0)` at every call
 * site that needs an empty array (prologue, default payload, HKDF info, etc.).
 *
 * Safe to share: zero-length arrays are immutable by definition (no indices to write).
 */
internal val EMPTY_BYTE_ARRAY: ByteArray = ByteArray(0)
