package ch.trancee.meshlink.api

/**
 * All 27 diagnostic event codes defined in spec §11.3, grouped into three tiers.
 *
 * Each code carries its own [severity] — the tier default applies to most codes but individual
 * codes may override (e.g. [DECRYPTION_FAILED] is WARN despite being in the Critical tier).
 */
public enum class DiagnosticCode(public val severity: DiagnosticLevel) {

    // ── Critical tier (4) ────────────────────────────────────────────────────

    /** Two peers advertise the same identity keypair — network integrity risk. */
    DUPLICATE_IDENTITY(DiagnosticLevel.ERROR),

    /** BLE stack stopped responding to scan/advertise requests. */
    BLE_STACK_UNRESPONSIVE(DiagnosticLevel.ERROR),

    /** Authenticated decryption of a received frame failed. */
    DECRYPTION_FAILED(DiagnosticLevel.WARN),

    /** Local identity key rotation could not complete. */
    ROTATION_FAILED(DiagnosticLevel.ERROR),

    // ── Threshold tier (8) ───────────────────────────────────────────────────

    /** A received message was rejected because its replay counter was already seen. */
    REPLAY_REJECTED(DiagnosticLevel.WARN),

    /** An outbound message was held back due to per-peer rate limiting. */
    RATE_LIMIT_HIT(DiagnosticLevel.WARN),

    /** Store-and-forward buffer is approaching its capacity limit. */
    BUFFER_PRESSURE(DiagnosticLevel.WARN),

    /** Heap or native memory usage has crossed the critical threshold. */
    MEMORY_PRESSURE(DiagnosticLevel.ERROR),

    /** The next-hop peer has accumulated a high failure rate. */
    NEXTHOP_UNRELIABLE(DiagnosticLevel.WARN),

    /** A routing loop was detected for a destination. */
    LOOP_DETECTED(DiagnosticLevel.WARN),

    /** A relayed message exceeded the configured maximum hop count. */
    HOP_LIMIT_EXCEEDED(DiagnosticLevel.INFO),

    /** A received message was older than the configured deduplication window. */
    MESSAGE_AGE_EXCEEDED(DiagnosticLevel.INFO),

    // ── Log tier (15) ────────────────────────────────────────────────────────

    /** The best route to a destination changed. */
    ROUTE_CHANGED(DiagnosticLevel.INFO),

    /** A peer was evicted from the presence cache due to expiry or pressure. */
    PEER_PRESENCE_EVICTED(DiagnosticLevel.INFO),

    /** The BLE transport mode switched (L2CAP ↔ GATT or advertising interval). */
    TRANSPORT_MODE_CHANGED(DiagnosticLevel.INFO),

    /** Fell back from L2CAP CoC to GATT for a peer that did not accept L2CAP. */
    L2CAP_FALLBACK(DiagnosticLevel.WARN),

    /** Periodic gossip traffic statistics snapshot. */
    GOSSIP_TRAFFIC_REPORT(DiagnosticLevel.INFO),

    /** A Noise handshake stage completed or failed. */
    HANDSHAKE_EVENT(DiagnosticLevel.INFO),

    /** A delivery acknowledgement arrived later than the expected window. */
    LATE_DELIVERY_ACK(DiagnosticLevel.WARN),

    /** Sending a unicast message to a peer failed. */
    SEND_FAILED(DiagnosticLevel.WARN),

    /** A chunked transfer timed out without completing. */
    DELIVERY_TIMEOUT(DiagnosticLevel.WARN),

    /** An incoming message carried an application ID not registered locally. */
    APP_ID_REJECTED(DiagnosticLevel.INFO),

    /** A received frame carried an unrecognised message type code. */
    UNKNOWN_MESSAGE_TYPE(DiagnosticLevel.WARN),

    /** A received frame was structurally malformed and could not be decoded. */
    MALFORMED_DATA(DiagnosticLevel.WARN),

    /** A new peer has been discovered and a Noise session is available. */
    PEER_DISCOVERED(DiagnosticLevel.INFO),

    /** A configuration field was silently clamped to its allowed range on start. */
    CONFIG_CLAMPED(DiagnosticLevel.INFO),

    /** An invalid [MeshLinkApi] lifecycle transition was attempted. */
    INVALID_STATE_TRANSITION(DiagnosticLevel.WARN),
}
