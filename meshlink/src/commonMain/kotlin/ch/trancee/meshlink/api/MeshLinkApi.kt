package ch.trancee.meshlink.api

import ch.trancee.meshlink.power.PowerTier
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// ── Priority ─────────────────────────────────────────────────────────────────

/** Message delivery priority. */
public enum class MessagePriority {
    /** Best-effort delivery, may be deprioritised under load. */
    LOW,

    /** Standard delivery priority. Default. */
    NORMAL,

    /** Expedited delivery; routed and buffered with preference. */
    HIGH,
}

// ── MessageId ────────────────────────────────────────────────────────────────

/**
 * Opaque identifier for a mesh message.
 *
 * Equality is defined by content of [bytes].
 */
public data class MessageId(val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is MessageId && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = bytes.contentToString()
}

// ── ReceivedMessage ───────────────────────────────────────────────────────────

/**
 * A mesh message received from a remote peer.
 *
 * @param id Unique identifier for deduplication.
 * @param senderId Key hash (12 bytes) of the originating peer.
 * @param payload Raw application payload.
 * @param receivedAtMillis Monotonic timestamp (ms) when the message was delivered locally.
 */
public data class ReceivedMessage(
    val id: MessageId,
    val senderId: ByteArray,
    val payload: ByteArray,
    val receivedAtMillis: Long,
) {
    override fun equals(other: Any?): Boolean = other is ReceivedMessage && id == other.id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "ReceivedMessage(id=$id, senderId=${senderId.contentToString()}, " +
            "payloadSize=${payload.size}, receivedAtMillis=$receivedAtMillis)"
}

// ── PeerEvent ─────────────────────────────────────────────────────────────────

/** Events emitted on the [MeshLinkApi.peers] flow when the set of known peers changes. */
public sealed class PeerEvent {
    /**
     * A new peer was discovered and a Noise XX handshake completed successfully.
     *
     * @param id Key hash (12 bytes) of the discovered peer.
     * @param detail Current details for the peer.
     */
    public data class Found(val id: ByteArray, val detail: PeerDetail) : PeerEvent() {
        override fun equals(other: Any?): Boolean = other is Found && id.contentEquals(other.id)

        override fun hashCode(): Int = id.contentHashCode()
    }

    /**
     * A previously discovered peer is no longer reachable.
     *
     * @param id Key hash (12 bytes) of the lost peer.
     */
    public data class Lost(val id: ByteArray) : PeerEvent() {
        override fun equals(other: Any?): Boolean = other is Lost && id.contentEquals(other.id)

        override fun hashCode(): Int = id.contentHashCode()
    }
}

// ── KeyChangeEvent (public) ───────────────────────────────────────────────────

/**
 * Emitted on [MeshLinkApi.keyChanges] when a peer presents a public key that differs from the
 * pinned key in the trust store.
 *
 * Consumers must resolve this event via [MeshLinkApi.acceptKeyChange] or
 * [MeshLinkApi.rejectKeyChange].
 *
 * @param peerId Key hash (12 bytes) of the peer that changed its key.
 * @param oldKey Previously pinned X25519 static public key (32 bytes), or `null` if the peer was
 *   not yet pinned.
 * @param newKey Newly observed X25519 static public key (32 bytes).
 */
public data class KeyChangeEvent(
    val peerId: ByteArray,
    val oldKey: ByteArray?,
    val newKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is KeyChangeEvent && peerId.contentEquals(other.peerId)

    override fun hashCode(): Int = peerId.contentHashCode()

    override fun toString(): String =
        "KeyChangeEvent(peerId=${peerId.contentToString()}, hasOldKey=${oldKey != null})"
}

// ── DiagnosticEvent ───────────────────────────────────────────────────────────

/** Severity level for a [DiagnosticEvent]. */
public enum class DiagnosticLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

/**
 * A single structured diagnostic event emitted on [MeshLinkApi.diagnosticEvents].
 *
 * @param code The [DiagnosticCode] identifying which condition was observed.
 * @param severity Severity copied from [code] at emission time for convenient filtering.
 * @param monotonicMillis Monotonic timestamp (ms) when the event was constructed.
 * @param wallClockMillis Wall-clock timestamp (ms) when the event was constructed.
 * @param droppedCount Number of diagnostic events dropped since the previous emitted event due to
 *   ring-buffer overflow. Zero under normal load.
 * @param payload Type-safe payload specific to [code].
 */
public data class DiagnosticEvent(
    val code: DiagnosticCode,
    val severity: DiagnosticLevel,
    val monotonicMillis: Long,
    val wallClockMillis: Long,
    val droppedCount: Int,
    val payload: DiagnosticPayload,
)

// ── MeshHealthSnapshot ────────────────────────────────────────────────────────

/**
 * A point-in-time health snapshot emitted periodically on [MeshLinkApi.meshHealthFlow] and returned
 * by [MeshLinkApi.meshHealth].
 *
 * Matches spec §11.5.
 *
 * @param connectedPeers Number of peers with an active Noise session.
 * @param routingTableSize Number of routes in the local routing table.
 * @param bufferUsageBytes Current store-and-forward buffer occupancy in bytes.
 * @param capturedAtMillis Monotonic timestamp (ms) when this snapshot was taken.
 * @param reachablePeers Number of peers reachable within the routing table (may include multi-hop).
 * @param bufferUtilizationPercent Combined buffer utilization as a percentage (0–100).
 * @param activeTransfers Number of currently active chunked-transfer sessions.
 * @param powerMode Current [PowerTier] in effect.
 * @param avgRouteCost Average Babel link-state cost across all routes; 0.0 if no routes.
 * @param relayQueueSize Current number of messages queued for relay forwarding.
 */
public data class MeshHealthSnapshot(
    val connectedPeers: Int,
    val routingTableSize: Int,
    val bufferUsageBytes: Long,
    val capturedAtMillis: Long,
    val reachablePeers: Int = 0,
    val bufferUtilizationPercent: Int = 0,
    val activeTransfers: Int = 0,
    val powerMode: PowerTier = PowerTier.BALANCED,
    val avgRouteCost: Double = 0.0,
    val relayQueueSize: Int = 0,
)

// ── Transfer events ───────────────────────────────────────────────────────────

/**
 * Progress update for a chunked transfer. Emitted periodically on [MeshLinkApi.transferProgress].
 *
 * @param transferId Opaque transfer identifier (matches [TransferFailure] events for the same
 *   transfer).
 * @param recipient Key hash (12 bytes) of the destination peer.
 * @param bytesTransferred Bytes confirmed received by the peer so far.
 * @param totalBytes Total byte count of the payload.
 */
public data class TransferProgress(
    val transferId: ByteArray,
    val recipient: ByteArray,
    val bytesTransferred: Long,
    val totalBytes: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is TransferProgress && transferId.contentEquals(other.transferId)

    override fun hashCode(): Int = transferId.contentHashCode()

    override fun toString(): String =
        "TransferProgress(transferId=${transferId.contentToString()}, " +
            "recipient=${recipient.contentToString()}, " +
            "$bytesTransferred/$totalBytes bytes)"
}

/** Events emitted on [MeshLinkApi.transferFailures] when a chunked transfer does not complete. */
public sealed class TransferFailure {
    /**
     * No chunk acknowledgement was received within the configured inactivity timeout.
     *
     * @param transferId Opaque transfer identifier.
     * @param recipient Key hash (12 bytes) of the intended recipient.
     */
    public data class Timeout(val transferId: ByteArray, val recipient: ByteArray) :
        TransferFailure() {
        override fun equals(other: Any?): Boolean =
            other is Timeout && transferId.contentEquals(other.transferId)

        override fun hashCode(): Int = transferId.contentHashCode()
    }

    /**
     * The target peer is no longer reachable and the transfer was abandoned.
     *
     * @param transferId Opaque transfer identifier.
     * @param recipient Key hash (12 bytes) of the intended recipient.
     */
    public data class PeerUnavailable(val transferId: ByteArray, val recipient: ByteArray) :
        TransferFailure() {
        override fun equals(other: Any?): Boolean =
            other is PeerUnavailable && transferId.contentEquals(other.transferId)

        override fun hashCode(): Int = transferId.contentHashCode()
    }

    /**
     * The transfer was cancelled by the local app via [MeshLinkApi.shedMemoryPressure] or
     * [MeshLinkApi.forgetPeer].
     *
     * @param transferId Opaque transfer identifier.
     */
    public data class Cancelled(val transferId: ByteArray) : TransferFailure() {
        override fun equals(other: Any?): Boolean =
            other is Cancelled && transferId.contentEquals(other.transferId)

        override fun hashCode(): Int = transferId.contentHashCode()
    }
}

// ── MeshLinkApi ───────────────────────────────────────────────────────────────

/**
 * Public API for the MeshLink peer-to-peer mesh library. See spec §13.4 for the full contract.
 *
 * Obtain an instance via `MeshLink(config)` (see `MeshLink.kt`). The implementation class is the
 * single entry point for consumers — this interface exists for testability.
 *
 * ## Lifecycle
 *
 * ```
 * val mesh = MeshLink(config)
 * mesh.start()           // UNINITIALIZED → RUNNING
 * mesh.pause()           // RUNNING → PAUSED
 * mesh.resume()          // PAUSED → RUNNING
 * mesh.stop()            // RUNNING → STOPPED
 * ```
 *
 * ## Thread safety
 *
 * All `suspend` methods are coroutine-safe. Non-suspend methods are safe to call from any thread
 * unless noted otherwise.
 */
public interface MeshLinkApi {

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Starts the mesh engine. Initialises crypto, BLE transport, and routing.
     *
     * Valid from: [MeshLinkState.UNINITIALIZED], [MeshLinkState.RECOVERABLE].
     *
     * @throws IllegalStateException If called from an invalid state ([MeshLinkState.RUNNING],
     *   [MeshLinkState.PAUSED], [MeshLinkState.STOPPED], [MeshLinkState.TERMINAL]).
     */
    public suspend fun start()

    /**
     * Stops the mesh engine.
     *
     * - [Duration.ZERO] (default): Immediate teardown. In-flight transfers are abandoned; buffered
     *   messages are retained (E2E-encrypted, resumable on next start).
     * - Non-zero: Graceful drain — blocks new operations and waits up to [timeout] for in-flight
     *   transfers to complete before abandoning the remainder.
     *
     * Valid from: [MeshLinkState.RUNNING], [MeshLinkState.PAUSED], [MeshLinkState.RECOVERABLE].
     *
     * @param timeout Maximum time to wait for graceful drain. Defaults to [Duration.ZERO].
     */
    public suspend fun stop(timeout: Duration = Duration.ZERO)

    /**
     * Pauses BLE advertising and scanning. In-flight transfers continue until completion or
     * timeout.
     *
     * Valid from: [MeshLinkState.RUNNING].
     */
    public suspend fun pause()

    /**
     * Resumes BLE advertising and scanning after [pause].
     *
     * Valid from: [MeshLinkState.PAUSED].
     */
    public suspend fun resume()

    // ── State ──────────────────────────────────────────────────────────────

    /** Current lifecycle state. Hot [StateFlow] — never completes. */
    public val state: StateFlow<MeshLinkState>

    // ── Messaging ─────────────────────────────────────────────────────────

    /**
     * Sends a unicast message to [recipient].
     *
     * The message is buffered if the peer is temporarily unreachable (store-and-forward).
     *
     * @param recipient Key hash (12 bytes) of the target peer.
     * @param payload Application payload. Must not exceed
     *   [MeshLinkConfig.MessagingConfig.maxMessageSize].
     * @param priority Delivery priority. Defaults to [MessagePriority.NORMAL].
     */
    public suspend fun send(
        recipient: ByteArray,
        payload: ByteArray,
        priority: MessagePriority = MessagePriority.NORMAL,
    )

    /**
     * Broadcasts [payload] to all reachable peers within [maxHops] hops.
     *
     * Broadcasts are signed (Ed25519) and deduplicated. Unsigned broadcasts are rejected by default
     * when [MeshLinkConfig.SecurityConfig.requireBroadcastSignatures] is true.
     *
     * @param payload Application payload. Must not exceed
     *   [MeshLinkConfig.MessagingConfig.maxBroadcastSize].
     * @param maxHops Maximum relay hops. Clamped to [MeshLinkConfig.RoutingConfig.maxHops].
     * @param priority Delivery priority. Defaults to [MessagePriority.NORMAL].
     */
    public suspend fun broadcast(
        payload: ByteArray,
        maxHops: Int,
        priority: MessagePriority = MessagePriority.NORMAL,
    )

    // ── Event streams ─────────────────────────────────────────────────────

    /** Emits [PeerEvent.Found] / [PeerEvent.Lost] as the set of reachable peers changes. */
    public val peers: Flow<PeerEvent>

    /** Emits every [ReceivedMessage] delivered to this node. */
    public val messages: Flow<ReceivedMessage>

    /** Emits the [MessageId] of each message whose delivery was confirmed by the recipient. */
    public val deliveryConfirmations: Flow<MessageId>

    /** Emits progress updates for outgoing chunked transfers. */
    public val transferProgress: Flow<TransferProgress>

    /** Emits a [TransferFailure] for every chunked transfer that does not complete. */
    public val transferFailures: Flow<TransferFailure>

    /**
     * Periodically emits [MeshHealthSnapshot] with current connectivity and resource metrics.
     * Interval is governed by [MeshLinkConfig.DiagnosticsConfig].
     */
    public val meshHealthFlow: Flow<MeshHealthSnapshot>

    /**
     * Emits [KeyChangeEvent] when a peer presents a new public key that differs from the pinned
     * value. Consumers must resolve via [acceptKeyChange] or [rejectKeyChange].
     */
    public val keyChanges: Flow<KeyChangeEvent>

    /** Emits [DiagnosticEvent] entries from the internal diagnostic pipeline. */
    public val diagnosticEvents: Flow<DiagnosticEvent>

    // ── Power ─────────────────────────────────────────────────────────────

    /**
     * Reports the current battery state. Overrides automatic power tier selection when called on
     * headless/embedded platforms where the battery monitor cannot read hardware state.
     *
     * @param percent Battery level in the range `[0.0, 1.0]`.
     * @param isCharging Whether the device is currently charging.
     */
    public fun updateBattery(percent: Float, isCharging: Boolean)

    /**
     * Pins the engine to a fixed [PowerTier], overriding automatic selection. Pass `null` to
     * restore automatic mode.
     *
     * @param mode Fixed tier to apply, or `null` to restore automatic selection.
     */
    public fun setCustomPowerMode(mode: PowerTier? = null)

    // ── Identity & Trust ──────────────────────────────────────────────────

    /** The local node's Ed25519 public key (32 bytes). Available after [start] succeeds. */
    public val localPublicKey: ByteArray

    /**
     * Returns the pinned X25519 static public key (32 bytes) for [id], or `null` if the peer is not
     * in the trust store.
     *
     * @param id Key hash (12 bytes) of the peer.
     */
    public fun peerPublicKey(id: ByteArray): ByteArray?

    /**
     * Returns full [PeerDetail] for [id], or `null` if the peer is unknown.
     *
     * @param id Key hash (12 bytes) of the peer.
     */
    public fun peerDetail(id: ByteArray): PeerDetail?

    /** Returns [PeerDetail] for all currently known peers. */
    public fun allPeerDetails(): List<PeerDetail>

    /**
     * Returns the human-readable fingerprint (hex-encoded key hash) for [id], or `null` if the peer
     * is unknown.
     *
     * @param id Key hash (12 bytes) of the peer.
     */
    public fun peerFingerprint(id: ByteArray): String?

    /**
     * Rotates the local identity key pair. Issues a signed RotationAnnouncement to all connected
     * peers. All existing Noise sessions are invalidated; new handshakes are required on next
     * contact.
     */
    public suspend fun rotateIdentity()

    /**
     * Re-pins the current key for [id] without triggering a [KeyChangeEvent]. Use when the local
     * app independently confirmed the peer's new key (e.g., out-of-band QR verification).
     *
     * @param id Key hash (12 bytes) of the peer to repin.
     */
    public suspend fun repinKey(id: ByteArray)

    /**
     * Accepts the pending key change for [peerId], updating the trust store to the new key.
     *
     * @param peerId Key hash (12 bytes) of the peer whose key change is being accepted.
     */
    public suspend fun acceptKeyChange(peerId: ByteArray)

    /**
     * Rejects the pending key change for [peerId]. The peer will be disconnected and the previous
     * pinned key is retained.
     *
     * @param peerId Key hash (12 bytes) of the peer whose key change is being rejected.
     */
    public suspend fun rejectKeyChange(peerId: ByteArray)

    /** Returns all [KeyChangeEvent] entries that are pending consumer resolution. */
    public fun pendingKeyChanges(): List<KeyChangeEvent>

    // ── Health ────────────────────────────────────────────────────────────

    /** Returns the current [MeshHealthSnapshot] without waiting for the next periodic emission. */
    public fun meshHealth(): MeshHealthSnapshot

    /**
     * Sheds memory pressure by evicting low-priority buffered messages and cancelling in-progress
     * transfers below the degradation threshold.
     */
    public fun shedMemoryPressure()

    // ── Routing ───────────────────────────────────────────────────────────

    /**
     * Returns a read-only point-in-time snapshot of the routing table. Not a live view — call again
     * for updated data.
     *
     * Intended for debugging and diagnostics, not for routing decisions.
     */
    public fun routingSnapshot(): RoutingSnapshot

    // ── GDPR ──────────────────────────────────────────────────────────────

    /**
     * Erases all locally stored data for [peerId]: trust store entry, replay counters, rotation
     * nonces, buffered messages, and routing table entries. Sends route retractions for any routes
     * through the forgotten peer.
     *
     * @param peerId Key hash (12 bytes) of the peer to forget.
     */
    public suspend fun forgetPeer(peerId: ByteArray)

    /**
     * Erases all identity keys, trust store entries, replay counters, and rotation nonces. The
     * engine must be stopped before calling this.
     */
    public suspend fun factoryReset()

    // ── Experimental ──────────────────────────────────────────────────────

    /**
     * Manually injects a route into the routing table, bypassing the feasibility condition and
     * sanity validation. For testing and debugging only.
     *
     * @param destination Key hash (12 bytes) of the route destination.
     * @param nextHop Key hash (12 bytes) of the next-hop peer.
     * @param cost Babel link-state metric.
     * @param seqNo Babel sequence number.
     */
    @ExperimentalMeshLinkApi
    public suspend fun addRoute(destination: ByteArray, nextHop: ByteArray, cost: Int, seqNo: Int)
}
