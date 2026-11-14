package ch.trancee.meshlink.internal

import kotlin.time.TimeSource
import kotlin.time.Clock as KotlinClock

/**
 * The only time source of the protocol core (ADR 0017 decision 1). Production injects the platform
 * clocks; tests inject a clock driven by the coroutine test scheduler, so timers run in virtual
 * time.
 */
public interface Clock {
    /** Milliseconds on a timeline with an arbitrary origin that never goes backward. */
    public fun monotonicMillis(): Long

    /** Milliseconds since the Unix epoch. It may jump when the user or network changes the time. */
    public fun wallClockMillis(): Long
}

/**
 * The operating system's clocks: wall time from `kotlin.time.Clock.System`, monotonic time from
 * `TimeSource.Monotonic`, counted from the first read. A platform adapter whose timers must keep
 * running while the device sleeps supplies its own [Clock].
 *
 * This is the one `commonMain` file that may read ambient time (`config/detekt/detekt.yml`).
 */
public object SystemClock : Clock {
    private val origin = TimeSource.Monotonic.markNow()

    override fun monotonicMillis(): Long = origin.elapsedNow().inWholeMilliseconds

    override fun wallClockMillis(): Long = KotlinClock.System.now().toEpochMilliseconds()
}
