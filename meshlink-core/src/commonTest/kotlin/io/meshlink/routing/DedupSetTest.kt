package io.meshlink.routing

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DedupSetTest {

    @Test
    fun acceptFirstRejectDuplicateLruEviction() {
        val dedup = DedupSet(capacity = 3)

        // First time seeing each ID → accepted
        assertTrue(dedup.tryInsert("msg1"), "msg1 first time — accept")
        assertTrue(dedup.tryInsert("msg2"), "msg2 first time — accept")
        assertTrue(dedup.tryInsert("msg3"), "msg3 first time — accept")

        // Duplicates → rejected
        assertFalse(dedup.tryInsert("msg1"), "msg1 duplicate — reject")
        assertFalse(dedup.tryInsert("msg2"), "msg2 duplicate — reject")
        // Access order now: [msg3, msg1, msg2] — msg3 is LRU

        // Insert msg4 → evicts LRU (msg3)
        assertTrue(dedup.tryInsert("msg4"), "msg4 new — accept, evicts msg3 (LRU)")

        // msg3 was evicted — now accepted as new
        assertTrue(dedup.tryInsert("msg3"), "msg3 was evicted — accept as new")

        // msg4 should still be present
        assertFalse(dedup.tryInsert("msg4"), "msg4 still present — reject")
    }
}
