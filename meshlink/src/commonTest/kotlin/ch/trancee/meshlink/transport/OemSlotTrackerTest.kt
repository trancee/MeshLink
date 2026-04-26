package ch.trancee.meshlink.transport

import ch.trancee.meshlink.storage.InMemorySecureStorage
import kotlin.test.Test
import kotlin.test.assertEquals

class OemSlotTrackerTest {

    // ── effectiveSlots: no storage entry returns initialMaxSlots ─────────────

    @Test
    fun noEntryReturnsInitialMaxSlots() {
        val storage = InMemorySecureStorage()
        val tracker = OemSlotTracker(storage = storage, initialMaxSlots = 4, clock = { 1_000L })
        assertEquals(4, tracker.effectiveSlots("Samsung|Galaxy S22|33"))
    }

    // ── recordFailure: single failure reduces by 1 ────────────────────────────

    @Test
    fun singleFailureReducesSlotsByOne() {
        val storage = InMemorySecureStorage()
        val tracker = OemSlotTracker(storage = storage, initialMaxSlots = 4, clock = { 1_000L })
        tracker.recordFailure("Samsung|Galaxy S22|33")
        assertEquals(3, tracker.effectiveSlots("Samsung|Galaxy S22|33"))
    }

    // ── recordFailure: floor at 1 ─────────────────────────────────────────────

    @Test
    fun floorAtOneAfterMultipleFailures() {
        val storage = InMemorySecureStorage()
        var time = 1_000L
        val tracker = OemSlotTracker(storage = storage, initialMaxSlots = 3, clock = { time })
        tracker.recordFailure("key") // 3 → 2
        time = 2_000L
        tracker.recordFailure("key") // 2 → 1
        time = 3_000L
        tracker.recordFailure("key") // 1 → 1 (floor)
        assertEquals(1, tracker.effectiveSlots("key"))
    }

    // ── persistence: survives new instance with same storage ──────────────────

    @Test
    fun persistenceSurvivesNewInstance() {
        val storage = InMemorySecureStorage()
        val tracker1 = OemSlotTracker(storage = storage, initialMaxSlots = 4, clock = { 1_000L })
        tracker1.recordFailure("Samsung|Galaxy S22|33")
        val tracker2 = OemSlotTracker(storage = storage, initialMaxSlots = 4, clock = { 2_000L })
        assertEquals(3, tracker2.effectiveSlots("Samsung|Galaxy S22|33"))
    }

    // ── 30-day expiry: resets to initialMaxSlots before applying failure ──────

    @Test
    fun thirtyDayExpiryResetsBeforeApplyingFailure() {
        val storage = InMemorySecureStorage()
        val thirtyDays = 30L * 24L * 60L * 60L * 1000L
        var time = 1_000L
        val tracker = OemSlotTracker(storage = storage, initialMaxSlots = 4, clock = { time })
        tracker.recordFailure("key") // → 3, stored at t=1000
        assertEquals(3, tracker.effectiveSlots("key"))
        // Advance by exactly 30 days — expiry triggers reset to 4, then reduces to 3
        time = 1_000L + thirtyDays
        tracker.recordFailure("key")
        assertEquals(3, tracker.effectiveSlots("key"))
    }

    @Test
    fun oneMillisecondBeforeExpiryDoesNotReset() {
        val storage = InMemorySecureStorage()
        val thirtyDays = 30L * 24L * 60L * 60L * 1000L
        var time = 1_000L
        val tracker = OemSlotTracker(storage = storage, initialMaxSlots = 4, clock = { time })
        tracker.recordFailure("key") // → 3, stored at t=1000
        // 1 ms short of 30 days: no reset, currentSlots = 3 → newSlots = 2
        time = 1_000L + thirtyDays - 1L
        tracker.recordFailure("key")
        assertEquals(2, tracker.effectiveSlots("key"))
    }

    // ── multiple OEM keys are tracked independently ───────────────────────────

    @Test
    fun multipleOemKeysAreIndependent() {
        val storage = InMemorySecureStorage()
        val tracker = OemSlotTracker(storage = storage, initialMaxSlots = 4, clock = { 1_000L })
        tracker.recordFailure("Samsung|Galaxy S22|33")
        assertEquals(3, tracker.effectiveSlots("Samsung|Galaxy S22|33"))
        assertEquals(4, tracker.effectiveSlots("Xiaomi|Redmi Note 11|31"))
    }
}
