package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticEvent
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.diagnostics.DiagnosticSink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

internal class MeshEngineHardRunToken internal constructor(internal val epoch: Long)

internal enum class MeshEngineRuntimeAwaitActiveResult {
    Active,
    HardRunEnded,
}

internal enum class MeshEngineRuntimeInterruption {
    Paused,
    HardRunEnded,
}

internal interface MeshEnginePublishedRuntimeSurface {
    val state: StateFlow<MeshLinkState>
    val peerEvents: Flow<PeerEvent>
    val diagnosticEvents: Flow<DiagnosticEvent>
    val messages: Flow<InboundMessage>
}

internal interface MeshEngineRuntimeGate {
    fun currentState(): MeshLinkState

    fun currentHardRunEpoch(): Long

    fun captureHardRunToken(): MeshEngineHardRunToken

    fun isAcceptingNewSends(): Boolean

    fun isHardRunActive(token: MeshEngineHardRunToken): Boolean

    suspend fun awaitActive(token: MeshEngineHardRunToken): MeshEngineRuntimeAwaitActiveResult

    suspend fun awaitInterruption(token: MeshEngineHardRunToken): MeshEngineRuntimeInterruption
}

internal interface MeshEngineCompatibilityRuntimeSurface {
    val runtimeGate: MeshEngineRuntimeGate
    val mutablePeerEvents: MutableSharedFlow<PeerEvent>
    val mutableMessages: MutableSharedFlow<InboundMessage>

    fun currentState(): MeshLinkState

    fun beginHardRun(): MeshEngineHardRunToken

    fun setLifecycleState(state: MeshLinkState): Unit

    @Suppress("LongParameterList")
    fun emitDiagnostic(
        code: DiagnosticCode,
        severity: DiagnosticSeverity,
        stage: String,
        peerSuffix: String? = null,
        reason: DiagnosticReason? = null,
        metadata: Map<String, String> = emptyMap(),
    ): Unit
}

private data class MeshEngineRuntimeGateSnapshot(val state: MeshLinkState, val hardRunEpoch: Long)

/**
 * Shared runtime surface used by the runtime assembly and test helpers.
 *
 * The default `Uninitialized` state models the construction-start boundary. Production runtime
 * assembly explicitly passes `Configured` once construction completes, so this helper's default
 * does not redefine the public lifecycle.
 */
internal class MeshEngineRuntimeSurface(
    initialState: MeshLinkState = MeshLinkState.Uninitialized,
    diagnosticSink: DiagnosticSink? = null,
) :
    MeshEnginePublishedRuntimeSurface,
    MeshEngineCompatibilityRuntimeSurface,
    MeshEngineRuntimeGate {
    private val mutableState: MutableStateFlow<MeshLinkState> = MutableStateFlow(initialState)
    private val mutableGateSnapshot: MutableStateFlow<MeshEngineRuntimeGateSnapshot> =
        MutableStateFlow(MeshEngineRuntimeGateSnapshot(state = initialState, hardRunEpoch = 0L))
    override val mutablePeerEvents: MutableSharedFlow<PeerEvent> =
        MutableSharedFlow(extraBufferCapacity = 16)
    override val mutableMessages: MutableSharedFlow<InboundMessage> =
        MutableSharedFlow(extraBufferCapacity = 16)
    private val mutableDiagnostics: MutableSharedFlow<DiagnosticEvent> =
        MutableSharedFlow(extraBufferCapacity = 32)
    private val diagnosticSink: DiagnosticSink? = diagnosticSink

    override val state: StateFlow<MeshLinkState> = mutableState.asStateFlow()
    override val peerEvents: Flow<PeerEvent> = mutablePeerEvents.asSharedFlow()
    override val diagnosticEvents: Flow<DiagnosticEvent> = mutableDiagnostics.asSharedFlow()
    override val messages: Flow<InboundMessage> = mutableMessages.asSharedFlow()
    override val runtimeGate: MeshEngineRuntimeGate = this

    override fun currentState(): MeshLinkState {
        return mutableGateSnapshot.value.state
    }

    override fun currentHardRunEpoch(): Long {
        return mutableGateSnapshot.value.hardRunEpoch
    }

    override fun captureHardRunToken(): MeshEngineHardRunToken {
        return MeshEngineHardRunToken(currentHardRunEpoch())
    }

    override fun isAcceptingNewSends(): Boolean {
        return currentState() === MeshLinkState.Running
    }

    override fun isHardRunActive(token: MeshEngineHardRunToken): Boolean {
        val snapshot = mutableGateSnapshot.value
        return snapshot.hardRunEpoch == token.epoch && snapshot.state === MeshLinkState.Running
    }

    override suspend fun awaitActive(
        token: MeshEngineHardRunToken
    ): MeshEngineRuntimeAwaitActiveResult {
        val currentSnapshot = mutableGateSnapshot.value
        if (
            currentSnapshot.hardRunEpoch != token.epoch ||
                currentSnapshot.state === MeshLinkState.Stopped ||
                currentSnapshot.state === MeshLinkState.Uninitialized
        ) {
            return MeshEngineRuntimeAwaitActiveResult.HardRunEnded
        }
        if (currentSnapshot.state === MeshLinkState.Running) {
            return MeshEngineRuntimeAwaitActiveResult.Active
        }
        val nextSnapshot = mutableGateSnapshot.first { snapshot ->
            snapshot.hardRunEpoch != token.epoch || snapshot.state !== MeshLinkState.Paused
        }
        return if (
            nextSnapshot.hardRunEpoch == token.epoch && nextSnapshot.state === MeshLinkState.Running
        ) {
            MeshEngineRuntimeAwaitActiveResult.Active
        } else {
            MeshEngineRuntimeAwaitActiveResult.HardRunEnded
        }
    }

    override suspend fun awaitInterruption(
        token: MeshEngineHardRunToken
    ): MeshEngineRuntimeInterruption {
        val currentSnapshot = mutableGateSnapshot.value
        if (
            currentSnapshot.hardRunEpoch != token.epoch ||
                currentSnapshot.state === MeshLinkState.Stopped ||
                currentSnapshot.state === MeshLinkState.Uninitialized
        ) {
            return MeshEngineRuntimeInterruption.HardRunEnded
        }
        if (currentSnapshot.state === MeshLinkState.Paused) {
            return MeshEngineRuntimeInterruption.Paused
        }
        val nextSnapshot = mutableGateSnapshot.first { snapshot ->
            snapshot.hardRunEpoch != token.epoch || snapshot.state !== MeshLinkState.Running
        }
        return if (
            nextSnapshot.hardRunEpoch == token.epoch && nextSnapshot.state === MeshLinkState.Paused
        ) {
            MeshEngineRuntimeInterruption.Paused
        } else {
            MeshEngineRuntimeInterruption.HardRunEnded
        }
    }

    override fun beginHardRun(): MeshEngineHardRunToken {
        val nextEpoch = currentHardRunEpoch() + 1L
        updateLifecycleState(state = MeshLinkState.Running, hardRunEpoch = nextEpoch)
        return MeshEngineHardRunToken(nextEpoch)
    }

    override fun setLifecycleState(state: MeshLinkState): Unit {
        updateLifecycleState(state = state, hardRunEpoch = currentHardRunEpoch())
    }

    @Suppress("LongParameterList")
    override fun emitDiagnostic(
        code: DiagnosticCode,
        severity: DiagnosticSeverity,
        stage: String,
        peerSuffix: String?,
        reason: DiagnosticReason?,
        metadata: Map<String, String>,
    ): Unit {
        val event =
            DiagnosticEvent(
                code = code,
                severity = severity,
                stage = stage,
                peerSuffix = peerSuffix,
                reason = reason,
                metadata = metadata,
            )
        mutableDiagnostics.tryEmit(event)
        diagnosticSink?.emit(event)
    }

    private fun updateLifecycleState(state: MeshLinkState, hardRunEpoch: Long): Unit {
        mutableState.value = state
        mutableGateSnapshot.value =
            MeshEngineRuntimeGateSnapshot(state = state, hardRunEpoch = hardRunEpoch)
    }
}
