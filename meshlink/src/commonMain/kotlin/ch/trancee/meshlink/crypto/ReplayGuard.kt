package ch.trancee.meshlink.crypto

/**
 * Per-sender replay protection using a 64-bit sliding window per RFC 9147 §4.5.3.
 *
 * Tracks the highest-seen 64-bit counter [highestCounter] plus a 64-bit [bitmap] covering
 * `[highestCounter - 63, highestCounter]`. Bit 0 of the bitmap represents [highestCounter], bit k
 * represents `highestCounter - k`, bit 63 represents the left edge.
 *
 * **Rejection is silent.** No diagnostic is emitted on rejection to prevent traffic analysis
 * side-channels (RFC 9147 §6.4).
 *
 * **Thread-safety:** not thread-safe. Callers must synchronise externally.
 *
 * **Persistence:** in-memory only in M001. Batch-persist hooks are deferred to M002+.
 */
internal class ReplayGuard {
    private var highestCounter: ULong = 0uL
    private var bitmap: ULong = 0uL

    /**
     * Checks whether [counter] should be accepted, updating state if accepted.
     *
     * Returns `true` on acceptance. Returns `false` silently on rejection.
     *
     * @param counter 64-bit unsigned counter from the incoming record.
     * @return `true` if accepted; `false` if rejected (zero, duplicate, or too old).
     */
    fun check(counter: ULong): Boolean {
        // Case 1: Counter zero is always rejected (RFC 9147 §6.5 R4).
        if (counter == 0uL) return false

        // Case 2: Counter advances the window — shift bitmap and update highest.
        if (counter > highestCounter) {
            val shift = counter - highestCounter
            // Kotlin/JVM masks shift amounts to 63 bits for ULong, so `bitmap shl 64` would
            // produce the same value as `bitmap shl 0` rather than 0. Explicitly zero the bitmap
            // when the entire 64-entry window is displaced by the incoming counter.
            bitmap = if (shift >= 64uL) 0uL else bitmap shl shift.toInt()
            bitmap = bitmap or 1uL
            highestCounter = counter
            return true
        }

        // Cases 3–5: Counter is at or below the current highest.
        val pos = highestCounter - counter // bit position; pos == 0 means counter == highestCounter
        if (pos >= 64uL) return false // Case 5: below left edge of window — too old

        // Cases 3/4: Within window [highestCounter-63, highestCounter].
        val bit = 1uL shl pos.toInt()
        if (bitmap and bit != 0uL) return false // Case 4: already seen — duplicate
        bitmap = bitmap or bit // Case 3: not yet seen — accept and record
        return true
    }
}
