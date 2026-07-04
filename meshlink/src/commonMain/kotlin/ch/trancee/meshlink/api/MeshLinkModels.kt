package ch.trancee.meshlink.api

import ch.trancee.meshlink.diagnostics.DiagnosticEvent
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * Stable peer handle used for addressing MeshLink operations and events.
 *
 * Treat [value] as opaque. [toString] redacts the identifier to the last six characters.
 *
 * [equals]/[hashCode] are overridden to be value-based on [value]: callers that resolve or
 * re-resolve a [PeerId] from routing/transport state (for example `RouteCoordinator.nextHopFor`)
 * may return a distinct instance each time even when the underlying peer id string is unchanged.
 * Reference-based equality previously made those distinct-but-equal instances compare as different,
 * causing spurious `delivery.send.routeRefreshed` retries whenever two independently resolved
 * [PeerId] instances for the same peer were compared with `==`/`!=`. This is a plain `class` rather
 * than a `data class` so the public API surface doesn't gain a `copy`/`component1` that would be
 * misleading for an opaque handle.
 */
public class PeerId public constructor(public val value: String) {
    init {
        if (value.isBlank()) {
            throw MeshLinkException.InvalidConfiguration("peerId must not be blank")
        }
    }

    override fun equals(other: Any?): Boolean {
        return this === other || (other is PeerId && value == other.value)
    }

    override fun hashCode(): Int {
        return value.hashCode()
    }

    override fun toString(): String {
        val suffix = value.takeLast(REDACTED_PEER_SUFFIX_LENGTH)
        return "PeerId(...$suffix)"
    }
}

/** Current MeshLink runtime lifecycle state. */
public sealed class MeshLinkState {
    public data object Uninitialized : MeshLinkState()

    /** Runtime has been created and configured, but not started yet. */
    public data object Configured : MeshLinkState()

    public data object Running : MeshLinkState()

    public data object Paused : MeshLinkState()

    public data object Stopped : MeshLinkState()
}

/** Delivery priority hint used when MeshLink schedules and expires outbound work. */
public enum class DeliveryPriority {
    HIGH,
    NORMAL,
    LOW,
}

/** Categorizes expected non-exceptional delivery outcomes from [MeshLink.send]. */
public enum class SendFailureReason {
    PAYLOAD_TOO_LARGE,
    TRANSFER_TIMED_OUT,
    TRANSFER_ABORTED,
    UNREACHABLE,
    TRUST_FAILURE,
}

/** Result of a call to [MeshLink.send]. */
public sealed class SendResult {
    /**
     * MeshLink accepted the payload and completed its local protocol path for this send attempt.
     */
    public data object Sent : SendResult()

    /** MeshLink could not send the payload for the expected [reason]. */
    public class NotSent public constructor(public val reason: SendFailureReason) : SendResult() {
        override fun toString(): String {
            return "NotSent(reason=$reason)"
        }
    }
}

/** Idempotent result from [MeshLink.start]. */
public sealed class StartResult {
    public data object Started : StartResult()

    public data object AlreadyRunning : StartResult()

    public class InvalidState public constructor(public val currentState: MeshLinkState) :
        StartResult() {
        override fun toString(): String {
            return "InvalidState(currentState=$currentState)"
        }
    }
}

/** Idempotent result from [MeshLink.pause]. */
public sealed class PauseResult {
    public data object Paused : PauseResult()

    public data object AlreadyPaused : PauseResult()

    public class InvalidState public constructor(public val currentState: MeshLinkState) :
        PauseResult() {
        override fun toString(): String {
            return "InvalidState(currentState=$currentState)"
        }
    }
}

/** Idempotent result from [MeshLink.resume]. */
public sealed class ResumeResult {
    public data object Resumed : ResumeResult()

    public data object AlreadyRunning : ResumeResult()

    public class InvalidState public constructor(public val currentState: MeshLinkState) :
        ResumeResult() {
        override fun toString(): String {
            return "InvalidState(currentState=$currentState)"
        }
    }
}

/** Idempotent result from [MeshLink.stop]. */
public sealed class StopResult {
    public data object Stopped : StopResult()

    public data object AlreadyStopped : StopResult()
}

/** Result of a call to [MeshLink.forgetPeer]. */
public sealed class ForgetPeerResult {
    public data object Forgotten : ForgetPeerResult()

    public data object NotFound : ForgetPeerResult()
}

/** Connectivity state reported for a discovered peer. */
public enum class PeerConnectionState {
    CONNECTED,
    DISCONNECTED,
}

/** Peer-discovery and connectivity events emitted by [MeshLink.peerEvents]. */
public sealed class PeerEvent {
    /** A peer became visible to the current runtime. */
    public class Found
    public constructor(public val peerId: PeerId, public val state: PeerConnectionState) :
        PeerEvent()

    /** A visible peer changed its connection state without leaving discovery entirely. */
    public class StateChanged
    public constructor(public val peerId: PeerId, public val state: PeerConnectionState) :
        PeerEvent()

    /** A previously visible peer left the current runtime view. */
    public class Lost public constructor(public val peerId: PeerId) : PeerEvent()
}

/**
 * Application payload delivered by MeshLink after transport, trust, and routing succeed.
 *
 * [payload] is defensively copied on construction. [receivedAtEpochMillis] records when MeshLink
 * emitted the message to the host app using the current platform epoch clock.
 */
public class InboundMessage
public constructor(
    public val originPeerId: PeerId,
    payload: ByteArray,
    public val receivedAtEpochMillis: Long,
    public val priority: DeliveryPriority,
) {
    public val payload: ByteArray = payload.copyOf()

    override fun toString(): String {
        return buildString {
            append("InboundMessage(originPeerId=")
            append(originPeerId)
            append(", receivedAtEpochMillis=")
            append(receivedAtEpochMillis)
            append(", priority=")
            append(priority)
            append(", payloadSize=")
            append(payload.size)
            append(')')
        }
    }
}

/**
 * Main runtime interface for controlling MeshLink and observing its public streams.
 *
 * Lifecycle methods are idempotent and report repeated calls through `Already*` result variants
 * instead of throwing.
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName(name = "MeshLinkRuntime", swiftName = "MeshLinkRuntime", exact = true)
public interface MeshLink {
    /** Current lifecycle state for this MeshLink runtime. */
    public val state: kotlinx.coroutines.flow.StateFlow<MeshLinkState>

    /** Discovery and peer-connectivity events for the current runtime view. */
    public val peerEvents: kotlinx.coroutines.flow.Flow<PeerEvent>

    /** Structured operator-facing diagnostics emitted by the runtime. */
    public val diagnosticEvents: kotlinx.coroutines.flow.Flow<DiagnosticEvent>

    /** Inbound application payloads delivered to the host app. */
    public val messages: kotlinx.coroutines.flow.Flow<InboundMessage>

    /** Starts MeshLink when the current lifecycle state permits a new hard run. */
    public suspend fun start(): StartResult

    /** Pauses MeshLink when the runtime is currently running. */
    public suspend fun pause(): PauseResult

    /** Resumes MeshLink when the runtime is currently paused. */
    public suspend fun resume(): ResumeResult

    /** Stops MeshLink, clears in-memory runtime state, and ends the current hard run. */
    public suspend fun stop(): StopResult

    /**
     * Sends an application payload toward [peerId].
     *
     * Expected delivery-path outcomes are reported through [SendResult]. Payloads larger than 64
     * KiB are rejected with [SendFailureReason.PAYLOAD_TOO_LARGE]. Calling [send] while MeshLink is
     * not [MeshLinkState.Running] throws [MeshLinkException.InvalidStateTransition].
     */
    public suspend fun send(
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority = DeliveryPriority.NORMAL,
    ): SendResult

    /** Removes the pinned trust state for [peerId], if one exists. */
    public suspend fun forgetPeer(peerId: PeerId): ForgetPeerResult

    /** Feeds [snapshot] into MeshLink's shared automatic power-policy logic. */
    public fun updateBattery(snapshot: BatterySnapshot): Unit
}

private const val REDACTED_PEER_SUFFIX_LENGTH: Int = 6
