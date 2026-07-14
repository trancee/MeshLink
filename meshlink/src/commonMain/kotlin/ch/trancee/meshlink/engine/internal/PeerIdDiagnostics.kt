package ch.trancee.meshlink.engine.internal

import ch.trancee.meshlink.api.PeerId

/**
 * Returns a short, log-safe suffix of this peer id's value, suitable for inclusion in diagnostic
 * metadata/log lines without leaking the full peer id. Declared once here so the many diagnostic
 * call sites across the engine share one canonical truncation instead of each repeating
 * `.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH)` inline.
 */
internal fun PeerId.diagnosticSuffix(): String {
    return value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH)
}
