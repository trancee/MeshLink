package ch.trancee.meshlink.api

/**
 * Structured payload for a [DiagnosticEvent]. One subtype exists per [DiagnosticCode];
 * [TextMessage] is a generic fallback for ad-hoc messages. All [PeerIdHex] fields are subject to
 * redaction when [DiagnosticsConfig.redactPeerIds] is enabled.
 */
public sealed interface DiagnosticPayload {

    // ── Critical tier ────────────────────────────────────────────────────────

    /** Payload for [DiagnosticCode.DUPLICATE_IDENTITY]. */
    public data class DuplicateIdentity(val peerId: PeerIdHex) : DiagnosticPayload

    /** Payload for [DiagnosticCode.BLE_STACK_UNRESPONSIVE]. */
    public data class BleStackUnresponsive(val reason: String) : DiagnosticPayload

    /** Payload for [DiagnosticCode.DECRYPTION_FAILED]. */
    public data class DecryptionFailed(val peerId: PeerIdHex, val reason: String) :
        DiagnosticPayload

    /** Payload for [DiagnosticCode.ROTATION_FAILED]. */
    public data class RotationFailed(val reason: String) : DiagnosticPayload

    // ── Threshold tier ───────────────────────────────────────────────────────

    /** Payload for [DiagnosticCode.REPLAY_REJECTED]. */
    public data class ReplayRejected(val peerId: PeerIdHex) : DiagnosticPayload

    /** Payload for [DiagnosticCode.RATE_LIMIT_HIT]. */
    public data class RateLimitHit(val peerId: PeerIdHex, val limit: Int, val windowMillis: Long) :
        DiagnosticPayload

    /** Payload for [DiagnosticCode.BUFFER_PRESSURE]. */
    public data class BufferPressure(val utilizationPercent: Int) : DiagnosticPayload

    /** Payload for [DiagnosticCode.MEMORY_PRESSURE]. */
    public data class MemoryPressure(val usedBytes: Long, val maxBytes: Long) : DiagnosticPayload

    /** Payload for [DiagnosticCode.NEXTHOP_UNRELIABLE]. */
    public data class NexthopUnreliable(val peerId: PeerIdHex, val failureRate: Float) :
        DiagnosticPayload

    /** Payload for [DiagnosticCode.LOOP_DETECTED]. */
    public data class LoopDetected(val destination: PeerIdHex) : DiagnosticPayload

    /** Payload for [DiagnosticCode.HOP_LIMIT_EXCEEDED]. */
    public data class HopLimitExceeded(val hops: Int, val maxHops: Int) : DiagnosticPayload

    /** Payload for [DiagnosticCode.MESSAGE_AGE_EXCEEDED]. */
    public data class MessageAgeExceeded(val ageMillis: Long, val maxAgeMillis: Long) :
        DiagnosticPayload

    // ── Log tier ─────────────────────────────────────────────────────────────

    /** Payload for [DiagnosticCode.ROUTE_CHANGED]. */
    public data class RouteChanged(val destination: PeerIdHex, val cost: Double) : DiagnosticPayload

    /** Payload for [DiagnosticCode.PEER_PRESENCE_EVICTED]. */
    public data class PeerPresenceEvicted(val peerId: PeerIdHex) : DiagnosticPayload

    /** Payload for [DiagnosticCode.TRANSPORT_MODE_CHANGED]. */
    public data class TransportModeChanged(val mode: String) : DiagnosticPayload

    /** Payload for [DiagnosticCode.L2CAP_FALLBACK]. */
    public data class L2capFallback(val peerId: PeerIdHex) : DiagnosticPayload

    /** Payload for [DiagnosticCode.GOSSIP_TRAFFIC_REPORT]. */
    public data class GossipTrafficReport(val routeCount: Int) : DiagnosticPayload

    /** Payload for [DiagnosticCode.HANDSHAKE_EVENT]. */
    public data class HandshakeEvent(val peerId: PeerIdHex, val stage: String) : DiagnosticPayload

    /** Payload for [DiagnosticCode.LATE_DELIVERY_ACK]. */
    public data class LateDeliveryAck(val messageIdHex: String) : DiagnosticPayload

    /** Payload for [DiagnosticCode.SEND_FAILED]. */
    public data class SendFailed(val peerId: PeerIdHex, val reason: String) : DiagnosticPayload

    /** Payload for [DiagnosticCode.DELIVERY_TIMEOUT]. */
    public data class DeliveryTimeout(val messageIdHex: String) : DiagnosticPayload

    /** Payload for [DiagnosticCode.APP_ID_REJECTED]. */
    public data class AppIdRejected(val appId: String) : DiagnosticPayload

    /** Payload for [DiagnosticCode.UNKNOWN_MESSAGE_TYPE]. */
    public data class UnknownMessageType(val typeCode: Int) : DiagnosticPayload

    /** Payload for [DiagnosticCode.MALFORMED_DATA]. */
    public data class MalformedData(val reason: String) : DiagnosticPayload

    /** Payload for [DiagnosticCode.PEER_DISCOVERED]. */
    public data class PeerDiscovered(val peerId: PeerIdHex) : DiagnosticPayload

    /** Payload for [DiagnosticCode.CONFIG_CLAMPED]. */
    public data class ConfigClamped(val field: String, val original: String, val clamped: String) :
        DiagnosticPayload

    /** Payload for [DiagnosticCode.INVALID_STATE_TRANSITION]. */
    public data class InvalidStateTransition(val from: String, val trigger: String) :
        DiagnosticPayload

    // ── Generic fallback ─────────────────────────────────────────────────────

    /** Generic text message for ad-hoc diagnostics not covered by a specific code. */
    public data class TextMessage(val message: String) : DiagnosticPayload
}
