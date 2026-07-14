package ch.trancee.meshlink.engine.internal

import ch.trancee.meshlink.transport.TransportSendResult

/**
 * Matches transient "link not ready yet" drop reasons across bearers (GATT and L2CAP, Android and
 * iOS) rather than a single hardcoded L2CAP string. Bearer-specific wording varies (e.g. "L2CAP
 * connection is not ready", "client not ready"), but all of them share the general "not ready"
 * phrasing and all represent the same underlying condition: the physical BLE side-link is still
 * mid-negotiation (connect/MTU/discovery/CCCD) and a retry within the handshake window is likely to
 * succeed once it completes. Permanent/config failures (e.g. "transport is not implemented", "peer
 * has not been discovered") intentionally do not match this substring and are still treated as
 * non-retryable.
 */
internal fun TransportSendResult.Dropped.isTransientLinkNotReady(): Boolean {
    return reason.contains("connection is not ready", ignoreCase = true) ||
        reason.contains("client not ready", ignoreCase = true)
}
