package ch.trancee.meshlink.engine

import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

internal sealed class MeshEngineRuntimeTimedWaitResult<out T> {
    internal class Completed<T> internal constructor(internal val value: T) :
        MeshEngineRuntimeTimedWaitResult<T>()

    internal data object TimedOut : MeshEngineRuntimeTimedWaitResult<Nothing>()

    internal data object HardRunEnded : MeshEngineRuntimeTimedWaitResult<Nothing>()
}

private sealed class MeshEngineRuntimeTimedWaitSelection<out T> {
    internal class Completed<T> internal constructor(internal val value: T?) :
        MeshEngineRuntimeTimedWaitSelection<T>()

    internal class Interrupted
    internal constructor(internal val interruption: MeshEngineRuntimeInterruption) :
        MeshEngineRuntimeTimedWaitSelection<Nothing>()
}

internal suspend fun <T> waitWithRuntimeGate(
    runtimeGate: MeshEngineRuntimeGate,
    hardRunToken: MeshEngineHardRunToken,
    maximumActiveWait: Duration,
    awaitChange: suspend (Duration) -> T?,
): MeshEngineRuntimeTimedWaitResult<T> {
    var remainingActiveWait = maximumActiveWait
    while (remainingActiveWait > ZERO) {
        when (runtimeGate.awaitActive(hardRunToken)) {
            MeshEngineRuntimeAwaitActiveResult.Active -> Unit
            MeshEngineRuntimeAwaitActiveResult.HardRunEnded -> {
                return MeshEngineRuntimeTimedWaitResult.HardRunEnded
            }
        }

        val activeSliceStartedAt = TimeSource.Monotonic.markNow()
        val selection = coroutineScope {
            val changeDeferred =
                async(start = CoroutineStart.UNDISPATCHED) { awaitChange(remainingActiveWait) }
            val interruptionDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runtimeGate.awaitInterruption(hardRunToken)
                }
            val result =
                select<MeshEngineRuntimeTimedWaitSelection<T>> {
                    changeDeferred.onAwait { value ->
                        MeshEngineRuntimeTimedWaitSelection.Completed(value)
                    }
                    interruptionDeferred.onAwait { interruption ->
                        MeshEngineRuntimeTimedWaitSelection.Interrupted(interruption)
                    }
                }
            changeDeferred.cancel()
            interruptionDeferred.cancel()
            result
        }

        remainingActiveWait =
            (remainingActiveWait - activeSliceStartedAt.elapsedNow()).coerceAtLeast(ZERO)
        when (selection) {
            is MeshEngineRuntimeTimedWaitSelection.Completed -> {
                return if (selection.value != null) {
                    MeshEngineRuntimeTimedWaitResult.Completed(selection.value)
                } else {
                    MeshEngineRuntimeTimedWaitResult.TimedOut
                }
            }
            is MeshEngineRuntimeTimedWaitSelection.Interrupted -> {
                if (selection.interruption === MeshEngineRuntimeInterruption.HardRunEnded) {
                    return MeshEngineRuntimeTimedWaitResult.HardRunEnded
                }
            }
        }
    }
    return MeshEngineRuntimeTimedWaitResult.TimedOut
}
