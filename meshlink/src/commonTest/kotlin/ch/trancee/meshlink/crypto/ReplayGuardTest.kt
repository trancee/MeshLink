package ch.trancee.meshlink.crypto

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplayGuardTest {

    // ── Case 1: counter zero ──────────────────────────────────────────────────

    @Test
    fun counterZeroAlwaysRejected() {
        val guard = ReplayGuard()
        assertFalse(guard.check(0uL))
    }

    // ── Case 2: counter advances window ──────────────────────────────────────

    @Test
    fun firstValidCounterAccepted() {
        val guard = ReplayGuard()
        assertTrue(guard.check(1uL))
    }

    @Test
    fun sequentialCountersAccepted() {
        val guard = ReplayGuard()
        assertTrue(guard.check(1uL))
        assertTrue(guard.check(2uL))
        assertTrue(guard.check(3uL))
        assertTrue(guard.check(4uL))
        assertTrue(guard.check(5uL))
    }

    // ── Case 4: duplicate within window ──────────────────────────────────────

    @Test
    fun duplicateCounterRejected() {
        val guard = ReplayGuard()
        assertTrue(guard.check(10uL))
        assertFalse(guard.check(10uL))
    }

    // ── Case 3: in-window counter not yet seen ────────────────────────────────

    @Test
    fun windowAdvanceOldCounterInWindowAccepted() {
        // After N=10, advance to N=20. Counter 15 is in [N-63..N] = [-43..20] and unseen.
        val guard = ReplayGuard()
        assertTrue(guard.check(10uL))
        assertTrue(guard.check(20uL)) // N=20; counter 10 now sits at bit 10
        assertTrue(guard.check(15uL)) // in window, not seen → accept
        assertFalse(guard.check(15uL)) // now seen → duplicate
        assertFalse(guard.check(10uL)) // in window, already seen → duplicate
    }

    // ── Case 5: counter below left edge of window ────────────────────────────

    @Test
    fun windowAdvanceOldCounterOutsideWindowRejected() {
        // After N=65, window=[2..65]. Counter 1 sits at pos=64 → too old.
        val guard = ReplayGuard()
        assertTrue(guard.check(1uL))
        assertTrue(guard.check(65uL)) // shift=64 → bitmap zeroed, only bit 0 (counter 65) set
        assertFalse(guard.check(1uL)) // pos=64 ≥ 64 → too old
    }

    // ── Window boundary: left edge exactly at N-63 ───────────────────────────

    @Test
    fun counterAtLeftEdgeOfWindowAccepted() {
        // After N=64, window=[1..64]. Counter 1 is at pos=63 — the leftmost slot.
        val guard = ReplayGuard()
        assertTrue(guard.check(64uL)) // N=64
        assertTrue(guard.check(1uL))  // pos=63 — left edge, not seen → accept
        assertFalse(guard.check(1uL)) // pos=63 — duplicate
    }

    @Test
    fun counterOneBelowWindowLeftEdgeRejected() {
        // After N=65, window=[2..65]. Counter 1 → pos=64 ≥ 64 → too old.
        val guard = ReplayGuard()
        assertTrue(guard.check(65uL)) // N=65, window=[2..65]
        assertFalse(guard.check(1uL)) // pos=64 ≥ 64 → rejected
    }

    // ── Large-gap advance: shift ≥ 64 zeroes the entire bitmap ───────────────

    @Test
    fun largeGapAdvanceResetsBitmapCorrectly() {
        // jump of 198 ≥ 64 → bitmap must be zeroed before setting bit 0 for counter 200
        val guard = ReplayGuard()
        assertTrue(guard.check(1uL))
        assertTrue(guard.check(2uL))
        assertTrue(guard.check(200uL)) // shift=198 ≥ 64 → bitmap=0 then bit 0 set
        assertFalse(guard.check(200uL)) // duplicate — bit 0 is set
    }

    @Test
    fun intermediateCountersRejectedAfterLargeGap() {
        // After large gap to 200, counters below window=[137..200] are too old.
        val guard = ReplayGuard()
        assertTrue(guard.check(1uL))
        assertTrue(guard.check(200uL)) // window=[137..200]
        assertFalse(guard.check(100uL)) // pos=100 ≥ 64 → too old
        assertFalse(guard.check(50uL))  // pos=150 ≥ 64 → too old
        assertFalse(guard.check(1uL))   // pos=199 ≥ 64 → too old
    }

    // ── Near-overflow counter values ──────────────────────────────────────────

    @Test
    fun nearMaxCounterValuesWorkCorrectly() {
        val guard = ReplayGuard()
        val max = ULong.MAX_VALUE
        assertTrue(guard.check(max - 1uL))   // N=max-1
        assertTrue(guard.check(max))          // N=max, shift=1
        assertFalse(guard.check(max))         // duplicate
        assertFalse(guard.check(max - 1uL))  // duplicate
        assertTrue(guard.check(max - 63uL))  // left edge: pos=63, not seen → accept
    }
}
