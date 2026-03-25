package io.meshlink.util

import kotlin.test.Test
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
}
