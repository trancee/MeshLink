package ch.trancee.meshlink.crypto

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplayGuardTest {

    // ── Case 1: counter zero ──────────────────────────────────────────────────

    @Test
    fun counterZeroAlwaysRejected() {
        // Arrange
        val guard = ReplayGuard()

        // Act
        val result = guard.check(0uL)

        // Assert
        assertFalse(result)
    }

    // ── Case 2: counter advances window ──────────────────────────────────────

    @Test
    fun firstValidCounterAccepted() {
        // Arrange
        val guard = ReplayGuard()

        // Act
        val result = guard.check(1uL)

        // Assert
        assertTrue(result)
    }

    @Test
    fun sequentialCountersAllAccepted() {
        // Arrange
        val guard = ReplayGuard()
        guard.check(1uL)
        guard.check(2uL)
        guard.check(3uL)
        guard.check(4uL)

        // Act
        val result = guard.check(5uL)

        // Assert
        assertTrue(result)
    }

    // ── Case 4: duplicate within window ──────────────────────────────────────

    @Test
    fun duplicateCounterRejected() {
        // Arrange
        val guard = ReplayGuard()
        guard.check(10uL)

        // Act
        val result = guard.check(10uL)

        // Assert
        assertFalse(result)
    }

    // ── Case 3: in-window counter not yet seen ────────────────────────────────

    @Test
    fun unseenCounterWithinWindowAccepted() {
        // Arrange — advance to N=20; counter 15 is in [N-63..N] and unseen
        val guard = ReplayGuard()
        guard.check(10uL)
        guard.check(20uL)

        // Act
        val result = guard.check(15uL)

        // Assert
        assertTrue(result)
    }

    @Test
    fun previouslySeenCounterWithinWindowRejected() {
        // Arrange — advance to N=20, then mark counter 15 as seen
        val guard = ReplayGuard()
        guard.check(10uL)
        guard.check(20uL)
        guard.check(15uL)

        // Act
        val result = guard.check(15uL)

        // Assert
        assertFalse(result)
    }

    @Test
    fun previouslyAdvancedCounterWithinWindowRejected() {
        // Arrange — counter 10 was seen during advance to N=20
        val guard = ReplayGuard()
        guard.check(10uL)
        guard.check(20uL)

        // Act
        val result = guard.check(10uL)

        // Assert
        assertFalse(result)
    }

    // ── Case 5: counter below left edge of window ────────────────────────────

    @Test
    fun counterOutsideWindowRejectedAfterAdvance() {
        // Arrange — after N=65, window=[2..65]. Counter 1 at pos=64 → too old.
        val guard = ReplayGuard()
        guard.check(1uL)
        guard.check(65uL)

        // Act
        val result = guard.check(1uL)

        // Assert
        assertFalse(result)
    }

    // ── Window boundary: left edge exactly at N-63 ───────────────────────────

    @Test
    fun counterAtLeftEdgeOfWindowAccepted() {
        // Arrange — after N=64, window=[1..64]. Counter 1 is at pos=63 (leftmost slot).
        val guard = ReplayGuard()
        guard.check(64uL)

        // Act
        val result = guard.check(1uL)

        // Assert
        assertTrue(result)
    }

    @Test
    fun counterAtLeftEdgeDuplicateRejected() {
        // Arrange — counter 1 already seen at left edge
        val guard = ReplayGuard()
        guard.check(64uL)
        guard.check(1uL)

        // Act
        val result = guard.check(1uL)

        // Assert
        assertFalse(result)
    }

    @Test
    fun counterOneBelowWindowLeftEdgeRejected() {
        // Arrange — after N=65, window=[2..65]. Counter 1 → pos=64 ≥ 64 → too old.
        val guard = ReplayGuard()
        guard.check(65uL)

        // Act
        val result = guard.check(1uL)

        // Assert
        assertFalse(result)
    }

    // ── Large-gap advance: shift ≥ 64 zeroes the entire bitmap ───────────────

    @Test
    fun largeGapAdvanceAcceptsNewCounter() {
        // Arrange — jump of 198 ≥ 64 → bitmap zeroed, only bit 0 (counter 200) set
        val guard = ReplayGuard()
        guard.check(1uL)
        guard.check(2uL)

        // Act
        val result = guard.check(200uL)

        // Assert
        assertTrue(result)
    }

    @Test
    fun largeGapAdvanceDuplicateRejected() {
        // Arrange — counter 200 already seen after large gap
        val guard = ReplayGuard()
        guard.check(1uL)
        guard.check(2uL)
        guard.check(200uL)

        // Act
        val result = guard.check(200uL)

        // Assert
        assertFalse(result)
    }

    @Test
    fun counterBelowWindowAfterLargeGapRejected() {
        // Arrange — after large gap to 200, window=[137..200]. Counter 100 is too old.
        val guard = ReplayGuard()
        guard.check(1uL)
        guard.check(200uL)

        // Act
        val result = guard.check(100uL)

        // Assert
        assertFalse(result)
    }

    @Test
    fun veryOldCounterAfterLargeGapRejected() {
        // Arrange — after large gap to 200, counter 50 at pos=150 ≥ 64 → too old
        val guard = ReplayGuard()
        guard.check(1uL)
        guard.check(200uL)

        // Act
        val result = guard.check(50uL)

        // Assert
        assertFalse(result)
    }

    // ── Near-overflow counter values ──────────────────────────────────────────

    @Test
    fun nearMaxCounterAdvanceAccepted() {
        // Arrange
        val guard = ReplayGuard()
        val max = ULong.MAX_VALUE
        guard.check(max - 1uL)

        // Act
        val result = guard.check(max)

        // Assert
        assertTrue(result)
    }

    @Test
    fun nearMaxCounterDuplicateRejected() {
        // Arrange
        val guard = ReplayGuard()
        val max = ULong.MAX_VALUE
        guard.check(max - 1uL)
        guard.check(max)

        // Act
        val result = guard.check(max)

        // Assert
        assertFalse(result)
    }

    @Test
    fun nearMaxCounterPreviouslySeenRejected() {
        // Arrange — max-1 was seen, then max advanced window
        val guard = ReplayGuard()
        val max = ULong.MAX_VALUE
        guard.check(max - 1uL)
        guard.check(max)

        // Act
        val result = guard.check(max - 1uL)

        // Assert
        assertFalse(result)
    }

    @Test
    fun nearMaxCounterLeftEdgeAccepted() {
        // Arrange — max-63 is at left edge of window, not previously seen
        val guard = ReplayGuard()
        val max = ULong.MAX_VALUE
        guard.check(max - 1uL)
        guard.check(max)

        // Act
        val result = guard.check(max - 63uL)

        // Assert
        assertTrue(result)
    }
}
