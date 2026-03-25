package io.meshlink.crypto

/**
 * Per-sender replay protection with a 64-entry sliding window.
 *
 * Outbound: `advance()` returns the next counter (pre-increment).
 * Inbound: `check(counter)` returns true if the counter is fresh.
 */
class ReplayGuard private constructor(
    private var outboundCounter: ULong,
    private var highestSeen: ULong,
    private var windowBitmask: ULong,
) {
    constructor() : this(0u, 0u, 0u)

    fun advance(): ULong = ++outboundCounter

    fun check(counter: ULong): Boolean {
        if (counter == 0uL) return false

        if (counter > highestSeen) {
            val shift = (counter - highestSeen).toInt().coerceAtMost(64)
            windowBitmask = if (shift >= 64) 0u else windowBitmask shr shift
            windowBitmask = windowBitmask or (1uL shl 63)
            highestSeen = counter
            return true
        }

        val age = (highestSeen - counter).toInt()
        if (age >= 64) return false

        val bit = 63 - age
        if (windowBitmask and (1uL shl bit) != 0uL) return false
        windowBitmask = windowBitmask or (1uL shl bit)
        return true
    }

    data class Snapshot(
        val outboundCounter: ULong,
        val highestSeen: ULong,
        val windowBitmask: ULong,
    )

    fun snapshot(): Snapshot = Snapshot(outboundCounter, highestSeen, windowBitmask)

    companion object {
        fun restore(snapshot: Snapshot): ReplayGuard =
            ReplayGuard(snapshot.outboundCounter, snapshot.highestSeen, snapshot.windowBitmask)
    }
}
