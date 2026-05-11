package ch.trancee.meshlink.engine

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal class DeliveryRetryScheduler
internal constructor(
    private val topologyVersion: StateFlow<Long>,
    private val random: Random = Random.Default,
) {
    internal suspend fun awaitRetry(
        attempt: Int,
        remainingBudget: Duration,
        lastObservedTopologyVersion: Long,
    ): RetryWakeup {
        if (remainingBudget <= Duration.ZERO) {
            return RetryWakeup.DeadlineExpired(lastObservedTopologyVersion)
        }
        val retryDelay = retryDelayFor(attempt).coerceAtMost(remainingBudget)
        val observedTopologyVersion =
            withTimeoutOrNull(retryDelay) {
                topologyVersion.first { version -> version > lastObservedTopologyVersion }
            }
        return if (observedTopologyVersion != null) {
            RetryWakeup.TopologyChanged(observedTopologyVersion)
        } else {
            RetryWakeup.TimerElapsed(topologyVersion.value)
        }
    }

    private fun retryDelayFor(attempt: Int): Duration {
        var baseDelay = BASE_RETRY_DELAY
        repeat(attempt.coerceAtMost(MAX_SHIFTED_ATTEMPTS)) {
            baseDelay = (baseDelay * 2).coerceAtMost(MAX_RETRY_DELAY)
        }
        val jitterFactor = 0.85 + random.nextDouble() * 0.30
        return (baseDelay * jitterFactor).coerceAtMost(MAX_RETRY_DELAY)
    }

    private companion object {
        private const val MAX_SHIFTED_ATTEMPTS: Int = 5
        private val BASE_RETRY_DELAY: Duration = 100.milliseconds
        private val MAX_RETRY_DELAY: Duration = 1_000.milliseconds
    }
}

internal sealed class RetryWakeup {
    internal class TimerElapsed internal constructor(internal val topologyVersion: Long) :
        RetryWakeup()

    internal class TopologyChanged internal constructor(internal val topologyVersion: Long) :
        RetryWakeup()

    internal class DeadlineExpired internal constructor(internal val topologyVersion: Long) :
        RetryWakeup()
}
