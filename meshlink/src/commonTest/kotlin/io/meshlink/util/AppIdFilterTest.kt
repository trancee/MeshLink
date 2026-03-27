package io.meshlink.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppIdFilterTest {

    @Test
    fun matchingHashAcceptedNonMatchingDroppedNullAcceptsAll() {
        // Filter with specific appId
        val filter = AppIdFilter(appId = "com.example.chat")
        val matchingHash = AppIdFilter.hash("com.example.chat")
        val otherHash = AppIdFilter.hash("com.other.app")

        assertTrue(filter.accepts(matchingHash), "Matching appId hash should be accepted")
        assertFalse(filter.accepts(otherHash), "Non-matching appId hash should be dropped")

        // Null appId → accept all
        val noFilter = AppIdFilter(appId = null)
        assertTrue(noFilter.accepts(matchingHash), "Null appId should accept any hash")
        assertTrue(noFilter.accepts(otherHash), "Null appId should accept any hash")
        assertTrue(noFilter.accepts(null), "Null appId should accept null hash")

        // Non-null filter rejects null hash
        assertFalse(filter.accepts(null), "Non-null filter should reject null hash")
    }

    // --- Batch 13 Cycle 8: Empty string appId vs null ---

    @Test
    fun emptyStringAppIdFiltersNormally() {
        // Empty string is NOT null — it creates a filter with hash of ""
        val emptyFilter = AppIdFilter(appId = "")
        val emptyHash = AppIdFilter.hash("")
        val otherHash = AppIdFilter.hash("other")

        assertTrue(emptyFilter.accepts(emptyHash), "Empty appId hash matches itself")
        assertFalse(emptyFilter.accepts(otherHash), "Empty appId filter rejects non-matching")
        assertFalse(emptyFilter.accepts(null), "Empty appId filter rejects null hash")

        // Null appId is the true "no filter" — accepts everything
        val nullFilter = AppIdFilter(appId = null)
        assertTrue(nullFilter.accepts(emptyHash))
        assertTrue(nullFilter.accepts(otherHash))
        assertTrue(nullFilter.accepts(null))
        assertTrue(nullFilter.accepts(ByteArray(16))) // zeroed hash

        // Hash determinism: same input → same output
        assertEquals(AppIdFilter.hash("test").toList(), AppIdFilter.hash("test").toList())
    }
}
