package ch.trancee.meshlink.api

/**
 * Observable lifecycle state of a remote peer.
 *
 * Emitted inside [PeerEvent.StateChanged] and available on [PeerDetail.state].
 *
 * Note: there is no `GONE` value — gone peers are evicted from all state and surfaced only via
 * [PeerEvent.Lost]. See MEM271.
 */
public enum class PeerState {
    /** The peer has an active Noise session and is reachable. */
    CONNECTED,

    /** The peer's BLE link dropped but may reconnect within the grace period. */
    DISCONNECTED,
}
