package io.meshlink.util

import io.meshlink.crypto.Sha256

/**
 * Returns a privacy-safe representation of a peer ID for diagnostic output.
 *
 * When [redact] is true, the peer ID bytes are SHA-256 hashed and the first
 * 4 hex characters of the hash are returned (prefixed with `#`), making the
 * output useful for correlating log entries without revealing actual peer IDs.
 *
 * When [redact] is false, the first 8 hex characters of the raw peer ID are
 * returned (the existing behaviour).
 */
fun diagnosticPeerId(peerId: ByteArray, redact: Boolean): String {
    return if (redact) {
        // Security: hash peer ID so logs cannot be used for traffic analysis.
        val hash = Sha256.hash(peerId)
        "#${hash.toHex().take(4)}"
    } else {
        peerId.toHex().take(8)
    }
}
