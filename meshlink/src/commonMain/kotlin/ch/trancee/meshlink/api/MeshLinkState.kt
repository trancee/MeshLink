package ch.trancee.meshlink.api

import ch.trancee.meshlink.messaging.CoverageIgnore
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ── MeshLinkState ────────────────────────────────────────────────────────────

/**
 * Lifecycle states for the MeshLink engine.
 *
 * State transitions are enforced by [MeshLinkStateMachine]. See spec §13.5 for the full transition
 * table.
 *
 * - [UNINITIALIZED]: Initial state. [MeshLinkApi.start] has not been called.
 * - [RUNNING]: Engine is active and connected to the BLE mesh.
 * - [PAUSED]: BLE advertising/scanning is suspended. Resume via [MeshLinkApi.resume].
 * - [STOPPED]: Permanently stopped. Absorbing state — no further transitions.
 * - [RECOVERABLE]: Transient failure (BLE reset, storage I/O error, permission revoked). Retry via
 *   [MeshLinkApi.start].
 * - [TERMINAL]: Permanent failure. Oscillation bound exceeded, missing CryptoProvider, invalid
 *   config, or permanent storage failure. No further operation possible.
 */
public enum class MeshLinkState {
    UNINITIALIZED,
    RUNNING,
    PAUSED,
    STOPPED,
    RECOVERABLE,
    TERMINAL,
}

// ── LifecycleEvent (internal) ────────────────────────────────────────────────

/** Internal events that drive [MeshLinkStateMachine] transitions. */
internal sealed class LifecycleEvent {
    /** [MeshLinkApi.start] succeeded. UNINITIALIZED/RECOVERABLE → RUNNING. */
    data object StartSuccess : LifecycleEvent()

    /**
     * [MeshLinkApi.start] failed. UNINITIALIZED → TERMINAL; RECOVERABLE → RECOVERABLE or TERMINAL.
     */
    data object StartFailure : LifecycleEvent()

    /** [MeshLinkApi.stop] called. RUNNING/PAUSED/RECOVERABLE → STOPPED. */
    data object Stop : LifecycleEvent()

    /** [MeshLinkApi.pause] called. RUNNING → PAUSED. */
    data object Pause : LifecycleEvent()

    /** [MeshLinkApi.resume] called. PAUSED → RUNNING. */
    data object Resume : LifecycleEvent()

    /** Transient failure detected (BLE stack, storage I/O). RUNNING → RECOVERABLE. */
    data object TransientFailure : LifecycleEvent()

    /** Short display name for diagnostic messages. */
    @get:CoverageIgnore // `simpleName` is always non-null for sealed class objects; ?: branch
    // unreachable
    internal val displayName: String
        get() = this::class.simpleName ?: "UnknownEvent"
}

// ── TransitionResult (internal) ──────────────────────────────────────────────

/** Result of a [MeshLinkStateMachine.transition] call. */
internal sealed class TransitionResult {
    /** The transition was valid and the state was updated. */
    data object Success : TransitionResult()

    /**
     * The transition was invalid for the current state. [state] is unchanged. A diagnostic event
     * with [DiagnosticCode.INVALID_STATE_TRANSITION] has been emitted via the [onDiagnostic]
     * callback.
     */
    data class InvalidTransition(val from: MeshLinkState, val event: LifecycleEvent) :
        TransitionResult()

    /**
     * The oscillation bound was exceeded: [MeshLinkStateMachine.OSCILLATION_MAX_FAILURES] failed
     * [LifecycleEvent.StartFailure] calls from [MeshLinkState.RECOVERABLE] within
     * [MeshLinkStateMachine.OSCILLATION_WINDOW_MS]. State has been set to [MeshLinkState.TERMINAL].
     */
    data object OscillationTerminal : TransitionResult()
}

// ── MeshLinkStateMachine (internal) ──────────────────────────────────────────

/**
 * Lifecycle FSM for MeshLink. Enforces the transition table from spec §13.5.
 *
 * **Not thread-safe.** Callers must serialize concurrent [transition] calls (e.g., via
 * `kotlinx.coroutines.sync.Mutex`).
 *
 * **Oscillation bound:** [OSCILLATION_MAX_FAILURES] failed [LifecycleEvent.StartFailure] calls from
 * [MeshLinkState.RECOVERABLE] within [OSCILLATION_WINDOW_MS] milliseconds automatically transitions
 * the state to [MeshLinkState.TERMINAL].
 *
 * @param nowMillis Returns current elapsed time in milliseconds (monotonic). Injected for
 *   deterministic testing.
 * @param onDiagnostic Invoked with a [DiagnosticEvent] when an invalid transition is attempted.
 */
internal class MeshLinkStateMachine(
    private val nowMillis: () -> Long = DEFAULT_CLOCK,
    private val onDiagnostic: (DiagnosticEvent) -> Unit = {},
) {
    private val _state: MutableStateFlow<MeshLinkState> =
        MutableStateFlow(MeshLinkState.UNINITIALIZED)

    /** Observable lifecycle state. */
    val state: StateFlow<MeshLinkState> = _state.asStateFlow()

    /**
     * Timestamps (from [nowMillis]) of failed [LifecycleEvent.StartFailure] calls while in
     * [MeshLinkState.RECOVERABLE], used to evaluate the oscillation bound.
     */
    private val failedStartTimestamps: ArrayDeque<Long> = ArrayDeque()

    companion object {
        /** Default monotonic clock: elapsed milliseconds since class load. */
        private val MONOTONIC_ORIGIN = TimeSource.Monotonic.markNow()

        internal val DEFAULT_CLOCK: () -> Long = {
            MONOTONIC_ORIGIN.elapsedNow().inWholeMilliseconds
        }

        /** Sliding window (ms) for the oscillation bound. */
        internal const val OSCILLATION_WINDOW_MS: Long = 60_000L

        /**
         * Maximum failed [LifecycleEvent.StartFailure] calls from [MeshLinkState.RECOVERABLE]
         * within [OSCILLATION_WINDOW_MS] before the engine transitions to [MeshLinkState.TERMINAL].
         */
        internal const val OSCILLATION_MAX_FAILURES: Int = 3
    }

    /**
     * Applies [event] to the current state.
     *
     * - On valid transitions: updates [state] and returns [TransitionResult.Success] (or
     *   [TransitionResult.OscillationTerminal] when the oscillation bound is hit).
     * - On invalid transitions: emits [DiagnosticEvent.InvalidStateTransition] via [onDiagnostic],
     *   leaves [state] unchanged, and returns [TransitionResult.InvalidTransition].
     */
    fun transition(event: LifecycleEvent): TransitionResult {
        val current = _state.value
        return when (event) {
            is LifecycleEvent.StartSuccess -> onStartSuccess(current)
            is LifecycleEvent.StartFailure -> onStartFailure(current)
            is LifecycleEvent.Stop -> onStop(current)
            is LifecycleEvent.Pause -> onPause(current)
            is LifecycleEvent.Resume -> onResume(current)
            is LifecycleEvent.TransientFailure -> onTransientFailure(current)
        }
    }

    private fun onStartSuccess(current: MeshLinkState): TransitionResult =
        when (current) {
            MeshLinkState.UNINITIALIZED,
            MeshLinkState.RECOVERABLE -> {
                failedStartTimestamps.clear()
                _state.value = MeshLinkState.RUNNING
                TransitionResult.Success
            }
            else -> invalidTransition(current, LifecycleEvent.StartSuccess)
        }

    private fun onStartFailure(current: MeshLinkState): TransitionResult =
        when (current) {
            MeshLinkState.UNINITIALIZED -> {
                _state.value = MeshLinkState.TERMINAL
                TransitionResult.Success
            }
            MeshLinkState.RECOVERABLE -> {
                pruneOldFailures()
                failedStartTimestamps.addLast(nowMillis())
                if (failedStartTimestamps.size >= OSCILLATION_MAX_FAILURES) {
                    failedStartTimestamps.clear()
                    _state.value = MeshLinkState.TERMINAL
                    TransitionResult.OscillationTerminal
                } else {
                    TransitionResult.Success
                }
            }
            else -> invalidTransition(current, LifecycleEvent.StartFailure)
        }

    private fun onStop(current: MeshLinkState): TransitionResult =
        when (current) {
            MeshLinkState.RUNNING,
            MeshLinkState.PAUSED,
            MeshLinkState.RECOVERABLE -> {
                failedStartTimestamps.clear()
                _state.value = MeshLinkState.STOPPED
                TransitionResult.Success
            }
            else -> invalidTransition(current, LifecycleEvent.Stop)
        }

    private fun onPause(current: MeshLinkState): TransitionResult =
        when (current) {
            MeshLinkState.RUNNING -> {
                _state.value = MeshLinkState.PAUSED
                TransitionResult.Success
            }
            else -> invalidTransition(current, LifecycleEvent.Pause)
        }

    private fun onResume(current: MeshLinkState): TransitionResult =
        when (current) {
            MeshLinkState.PAUSED -> {
                _state.value = MeshLinkState.RUNNING
                TransitionResult.Success
            }
            else -> invalidTransition(current, LifecycleEvent.Resume)
        }

    private fun onTransientFailure(current: MeshLinkState): TransitionResult =
        when (current) {
            MeshLinkState.RUNNING -> {
                _state.value = MeshLinkState.RECOVERABLE
                TransitionResult.Success
            }
            else -> invalidTransition(current, LifecycleEvent.TransientFailure)
        }

    private fun invalidTransition(from: MeshLinkState, event: LifecycleEvent): TransitionResult {
        onDiagnostic(
            DiagnosticEvent(
                code = DiagnosticCode.INVALID_STATE_TRANSITION,
                severity = DiagnosticCode.INVALID_STATE_TRANSITION.severity,
                monotonicMillis = nowMillis(),
                wallClockMillis = nowMillis(),
                droppedCount = 0,
                payload = DiagnosticPayload.InvalidStateTransition(from.name, event.displayName),
            )
        )
        return TransitionResult.InvalidTransition(from, event)
    }

    /**
     * Removes failure timestamps that fall outside the [OSCILLATION_WINDOW_MS] sliding window.
     * Called before recording a new failure in [onStartFailure].
     */
    private fun pruneOldFailures() {
        val cutoff = nowMillis() - OSCILLATION_WINDOW_MS
        while (failedStartTimestamps.isNotEmpty() && failedStartTimestamps.first() < cutoff) {
            failedStartTimestamps.removeFirst()
        }
    }
}
