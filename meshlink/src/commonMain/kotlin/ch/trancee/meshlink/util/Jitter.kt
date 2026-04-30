package ch.trancee.meshlink.util

import kotlin.random.Random

/**
 * Returns a random jitter value in the range `[0, maxMillis)`.
 *
 * Used to desynchronize periodic timers (routing hellos, retry backoffs, ACK deadlines) across
 * nodes that may have started simultaneously. Without jitter, all nodes in a freshly-booted mesh
 * fire timers at the same instant, causing a thundering herd.
 *
 * @param maxMillis Upper bound (exclusive). If ≤ 0, returns 0 (no jitter).
 */
internal fun jitterMillis(maxMillis: Long): Long =
    if (maxMillis <= 0L) 0L else Random.nextLong(maxMillis)
