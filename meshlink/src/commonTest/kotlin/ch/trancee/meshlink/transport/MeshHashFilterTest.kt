package ch.trancee.meshlink.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeshHashFilterTest {

    // ── computeMeshHash: known FNV-1a vectors ─────────────────────────────────

    @Test
    fun knownVectorApp1() {
        // "app1" → FNV-1a 32-bit XOR-fold → 0xF70B (independently verified)
        assertEquals(0xF70Bu.toUShort(), MeshHashFilter.computeMeshHash("app1"))
    }

    @Test
    fun knownVectorTest() {
        // "test" → FNV-1a 32-bit XOR-fold → 0xDE35 (independently verified)
        assertEquals(0xDE35u.toUShort(), MeshHashFilter.computeMeshHash("test"))
    }

    @Test
    fun knownVectorA() {
        // "a" → FNV-1a 32-bit XOR-fold → 0xCD20 (independently verified)
        assertEquals(0xCD20u.toUShort(), MeshHashFilter.computeMeshHash("a"))
    }

    // ── computeMeshHash: zero substitution ───────────────────────────────────

    @Test
    fun zeroSubstitutedToOne() {
        // "76349" produces raw XOR-fold 0x0000, which must be substituted to 0x0001.
        // This tests the true branch of: if (result == 0.toUShort()) return 1.toUShort()
        assertEquals(0x0001u.toUShort(), MeshHashFilter.computeMeshHash("76349"))
    }

    // ── matches: universal broadcast (0x0000) ─────────────────────────────────

    @Test
    fun universalHashMatchesAnyLocalHash() {
        // payloadMeshHash = 0x0000 is a universal broadcast → matches anything
        assertTrue(MeshHashFilter.matches(0x0000u.toUShort(), 0x1234u.toUShort()))
        assertTrue(MeshHashFilter.matches(0x0000u.toUShort(), 0xF70Bu.toUShort()))
    }

    // ── matches: exact match ──────────────────────────────────────────────────

    @Test
    fun sameNonZeroHashMatches() {
        // Non-zero payloadMeshHash that equals localMeshHash → true
        // Tests the false/true path: first condition false, second condition true
        assertTrue(MeshHashFilter.matches(0xF70Bu.toUShort(), 0xF70Bu.toUShort()))
    }

    // ── matches: mismatch ─────────────────────────────────────────────────────

    @Test
    fun differentNonZeroHashesDoNotMatch() {
        // Non-zero payloadMeshHash that differs from localMeshHash → false
        // Tests the false/false path: both conditions false
        assertFalse(MeshHashFilter.matches(0xF70Bu.toUShort(), 0xDE35u.toUShort()))
    }

    @Test
    fun differentHashesNoMatchAlternativePair() {
        assertFalse(MeshHashFilter.matches(0x1234u.toUShort(), 0x5678u.toUShort()))
    }
}
