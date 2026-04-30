package ch.trancee.meshlink.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LruMapTest {

    // ── get / set basics ─────────────────────────────────────────────────────

    @Test
    fun `set and get returns stored value`() {
        val map = LruMap<String, Int>(4)
        map["a"] = 1
        assertEquals(1, map["a"])
    }

    @Test
    fun `get returns null for missing key`() {
        val map = LruMap<String, Int>(4)
        assertNull(map["missing"])
    }

    @Test
    fun `set overwrites existing value`() {
        val map = LruMap<String, Int>(4)
        map["a"] = 1
        map["a"] = 2
        assertEquals(2, map["a"])
        assertEquals(1, map.size)
    }

    // ── LRU eviction ─────────────────────────────────────────────────────────

    @Test
    fun `evicts eldest entry when capacity exceeded via set`() {
        val map = LruMap<String, Int>(2)
        map["a"] = 1
        map["b"] = 2
        map["c"] = 3 // should evict "a"
        assertNull(map["a"])
        assertEquals(2, map["b"])
        assertEquals(3, map["c"])
        assertEquals(2, map.size)
    }

    @Test
    fun `get promotes entry to most recently used`() {
        val map = LruMap<String, Int>(2)
        map["a"] = 1
        map["b"] = 2
        // Access "a" — promotes it; "b" is now LRU
        assertEquals(1, map["a"])
        map["c"] = 3 // should evict "b" (LRU)
        assertNull(map["b"])
        assertEquals(1, map["a"])
        assertEquals(3, map["c"])
    }

    // ── getOrPut ─────────────────────────────────────────────────────────────

    @Test
    fun `getOrPut inserts when key absent`() {
        val map = LruMap<String, Int>(4)
        val result = map.getOrPut("x") { 42 }
        assertEquals(42, result)
        assertEquals(42, map["x"])
    }

    @Test
    fun `getOrPut returns existing value without calling factory`() {
        val map = LruMap<String, Int>(4)
        map["x"] = 10
        var called = false
        val result =
            map.getOrPut("x") {
                called = true
                99
            }
        assertEquals(10, result)
        assertFalse(called)
    }

    @Test
    fun `getOrPut promotes existing entry`() {
        val map = LruMap<String, Int>(2)
        map["a"] = 1
        map["b"] = 2
        // Access "a" via getOrPut — promotes it
        map.getOrPut("a") { 99 }
        map["c"] = 3 // evicts "b" (now LRU)
        assertNull(map["b"])
        assertEquals(1, map["a"])
    }

    @Test
    fun `getOrPut evicts eldest when at capacity`() {
        val map = LruMap<String, Int>(2)
        map["a"] = 1
        map["b"] = 2
        map.getOrPut("c") { 3 } // evicts "a"
        assertNull(map["a"])
        assertEquals(3, map["c"])
    }

    // ── remove ───────────────────────────────────────────────────────────────

    @Test
    fun `remove returns value and decreases size`() {
        val map = LruMap<String, Int>(4)
        map["a"] = 1
        assertEquals(1, map.remove("a"))
        assertEquals(0, map.size)
        assertNull(map["a"])
    }

    @Test
    fun `remove returns null for missing key`() {
        val map = LruMap<String, Int>(4)
        assertNull(map.remove("missing"))
    }

    // ── containsKey ──────────────────────────────────────────────────────────

    @Test
    fun `containsKey returns true for present key`() {
        val map = LruMap<String, Int>(4)
        map["a"] = 1
        assertTrue(map.containsKey("a"))
    }

    @Test
    fun `containsKey returns false for absent key`() {
        val map = LruMap<String, Int>(4)
        assertFalse(map.containsKey("missing"))
    }

    // ── edge: maxSize = 1 ────────────────────────────────────────────────────

    @Test
    fun `maxSize 1 evicts on every new insert`() {
        val map = LruMap<String, Int>(1)
        map["a"] = 1
        map["b"] = 2
        assertNull(map["a"])
        assertEquals(2, map["b"])
        assertEquals(1, map.size)
    }
}
