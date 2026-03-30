package io.meshlink.transport

import io.meshlink.util.ByteArrayKey
import io.meshlink.util.currentTimeMillis
import io.meshlink.util.toKey

/**
 * Manages L2CAP CoC channel lifecycle for each peer.
 *
 * Delegates the actual platform-level channel creation to a caller-supplied
 * [channelFactory]. When a factory call fails, the manager retries up to
 * [maxRetries] times with exponential back-off and, after repeated failures,
 * engages a circuit breaker that marks the peer as GATT-only for
 * [circuitBreakerCooldownMillis] milliseconds.
 *
 * @param channelFactory  Suspending function that opens a platform L2CAP
 *   channel for the given peer ID, or returns `null` / throws if the peer
 *   does not support L2CAP.
 * @param maxRetries              Number of retry attempts before giving up.
 * @param initialBackoffMillis        Base delay for the first retry.
 * @param circuitBreakerFailures  Number of failures within [circuitBreakerWindowMillis]
 *   that trip the circuit breaker.
 * @param circuitBreakerWindowMillis  Sliding window for counting failures.
 * @param circuitBreakerCooldownMillis Duration a tripped breaker stays open.
 * @param clock                   Wall-clock source (injectable for tests).
 * @param delayFn                 Suspending delay (injectable for tests).
 */
class L2capManager(
    private val channelFactory: suspend (peerId: ByteArray) -> L2capChannel? = { null },
    private val maxRetries: Int = 3,
    private val initialBackoffMillis: Long = 200L,
    private val circuitBreakerFailures: Int = 3,
    private val circuitBreakerWindowMillis: Long = 5 * 60 * 1_000L,
    private val circuitBreakerCooldownMillis: Long = 30 * 60 * 1_000L,
    private val clock: () -> Long = { currentTimeMillis() },
    private val delayFn: suspend (ms: Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {

    // Peer key → open channel
    private val channels = mutableMapOf<ByteArrayKey, L2capChannel>()

    // Per-peer circuit-breaker state
    private val failureTimestamps = mutableMapOf<ByteArrayKey, MutableList<Long>>()
    private val circuitOpenUntil = mutableMapOf<ByteArrayKey, Long>()

    /**
     * Try to open an L2CAP channel to [peerId].
     *
     * Returns the channel on success, or `null` when:
     * - the circuit breaker is open (peer marked GATT-only), or
     * - all retry attempts failed.
     */
    suspend fun openChannel(peerId: ByteArray): L2capChannel? {
        val key = peerId.toKey()

        // Check circuit breaker
        val openUntil = circuitOpenUntil[key]
        if (openUntil != null && clock() < openUntil) {
            return null
        }
        // If the cooldown has elapsed, reset the breaker
        if (openUntil != null) {
            circuitOpenUntil.remove(key)
            failureTimestamps.remove(key)
        }

        // If a channel is already open for this peer, return it
        val existing = channels[key]
        if (existing != null && existing.isOpen) return existing

        // Attempt to open with retries + exponential back-off
        var lastException: Throwable? = null
        for (attempt in 0..maxRetries) {
            try {
                val channel = channelFactory(peerId)
                if (channel != null) {
                    channels[key] = channel
                    return channel
                }
            } catch (e: Throwable) {
                lastException = e
            }
            if (attempt < maxRetries) {
                val delay = initialBackoffMillis * (1L shl attempt)
                delayFn(delay)
            }
        }

        // All attempts failed → record failure for circuit breaker
        recordFailure(key)
        return null
    }

    /** Return the existing open channel for [peerId], or `null`. */
    fun getChannel(peerId: ByteArray): L2capChannel? {
        val ch = channels[peerId.toKey()]
        return if (ch != null && ch.isOpen) ch else null
    }

    /** Close and remove the channel for [peerId]. */
    fun closeChannel(peerId: ByteArray) {
        val key = peerId.toKey()
        channels.remove(key)?.close()
    }

    // --- internals ---

    private fun recordFailure(key: ByteArrayKey) {
        val now = clock()
        val timestamps = failureTimestamps[key]
        if (timestamps != null) {
            timestamps.add(now)
        } else {
            failureTimestamps[key] = mutableListOf(now)
        }
        // Evict old entries outside the window
        val cutoff = now - circuitBreakerWindowMillis
        failureTimestamps[key]?.removeAll { it <= cutoff }

        val count = failureTimestamps[key]?.size ?: 0
        if (count >= circuitBreakerFailures) {
            circuitOpenUntil[key] = now + circuitBreakerCooldownMillis
        }
    }
}
