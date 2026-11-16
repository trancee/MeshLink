package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.internal.Clock

/** A [Clock] whose wall clock reads [wallClockMillis] and whose monotonic clock reads 0. */
internal class FixedClock(
    private val wallClockMillis: Long,
) : Clock {
    override fun monotonicMillis(): Long = 0

    override fun wallClockMillis(): Long = wallClockMillis

    companion object {
        /** A clock at [unixSeconds]. */
        fun atSeconds(unixSeconds: Long): FixedClock = FixedClock(unixSeconds * MILLIS_PER_SECOND)

        private const val MILLIS_PER_SECOND = 1_000L
    }
}
