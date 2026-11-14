package ch.trancee.meshlink.internal

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
