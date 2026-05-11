package ch.trancee.meshlink.api

import ch.trancee.meshlink.diagnostics.DiagnosticEvent

public class PeerId public constructor(public val value: String) {
    init {
        if (value.isBlank()) {
            throw MeshLinkException.InvalidConfiguration("peerId must not be blank")
        }
    }

    override fun toString(): String {
        val suffix = value.takeLast(6)
        return "PeerId(...$suffix)"
    }
}

public sealed class MeshLinkState {
    public data object Uninitialized : MeshLinkState()

    public data object Running : MeshLinkState()

    public data object Paused : MeshLinkState()

    public data object Stopped : MeshLinkState()
}

public enum class DeliveryPriority {
    HIGH,
    NORMAL,
    LOW,
}

public enum class SendFailureReason {
    PAYLOAD_TOO_LARGE,
    TRANSFER_TIMED_OUT,
    TRANSFER_ABORTED,
    UNREACHABLE,
    TRUST_FAILURE,
}

public sealed class SendResult {
    public data object Sent : SendResult()

    public class NotSent public constructor(public val reason: SendFailureReason) : SendResult() {
        override fun toString(): String {
            return "NotSent(reason=$reason)"
        }
    }
}

public sealed class StartResult {
    public data object Started : StartResult()

    public data object AlreadyRunning : StartResult()
}

public sealed class PauseResult {
    public data object Paused : PauseResult()

    public data object AlreadyPaused : PauseResult()
}

public sealed class ResumeResult {
    public data object Resumed : ResumeResult()

    public data object AlreadyRunning : ResumeResult()
}

public sealed class StopResult {
    public data object Stopped : StopResult()

    public data object AlreadyStopped : StopResult()
}

public sealed class ForgetPeerResult {
    public data object Forgotten : ForgetPeerResult()

    public data object NotFound : ForgetPeerResult()
}

public enum class PeerConnectionState {
    CONNECTED,
    DISCONNECTED,
}

public sealed class PeerEvent {
    public class Found
    public constructor(public val peerId: PeerId, public val state: PeerConnectionState) :
        PeerEvent()

    public class StateChanged
    public constructor(public val peerId: PeerId, public val state: PeerConnectionState) :
        PeerEvent()

    public class Lost public constructor(public val peerId: PeerId) : PeerEvent()
}

public class InboundMessage
public constructor(
    public val originPeerId: PeerId,
    payload: ByteArray,
    public val receivedAtEpochMillis: Long,
    public val priority: DeliveryPriority,
) {
    public val payload: ByteArray = payload.copyOf()

    override fun toString(): String {
        return "InboundMessage(originPeerId=$originPeerId, receivedAtEpochMillis=$receivedAtEpochMillis, priority=$priority, payloadSize=${payload.size})"
    }
}

public interface MeshLinkApi {
    public val state: kotlinx.coroutines.flow.StateFlow<MeshLinkState>
    public val peerEvents: kotlinx.coroutines.flow.Flow<PeerEvent>
    public val diagnosticEvents: kotlinx.coroutines.flow.Flow<DiagnosticEvent>
    public val messages: kotlinx.coroutines.flow.Flow<InboundMessage>

    public suspend fun start(): StartResult

    public suspend fun pause(): PauseResult

    public suspend fun resume(): ResumeResult

    public suspend fun stop(): StopResult

    public suspend fun send(
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority = DeliveryPriority.NORMAL,
    ): SendResult

    public suspend fun forgetPeer(peerId: PeerId): ForgetPeerResult

    public fun updateBattery(level: Float, isCharging: Boolean): Unit
}
