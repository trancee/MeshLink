package ch.trancee.meshlink.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeshHashFilterTest {

    // ── computeMeshHash: known FNV-1a vectors ─────────────────────────────────

    @Test
    fun knownVectorApp1() {
        // Act
        val result = MeshHashFilter.computeMeshHash("app1")

        // Assert — "app1" → FNV-1a 32-bit XOR-fold → 0xF70B
        assertEquals(0xF70Bu.toUShort(), result)
    }

    @Test
    fun knownVectorTest() {
        // Act
        val result = MeshHashFilter.computeMeshHash("test")

        // Assert — "test" → FNV-1a 32-bit XOR-fold → 0xDE35
        assertEquals(0xDE35u.toUShort(), result)
    }

    @Test
    fun knownVectorA() {
        // Act
        val result = MeshHashFilter.computeMeshHash("a")

        // Assert — "a" → FNV-1a 32-bit XOR-fold → 0xCD20
        assertEquals(0xCD20u.toUShort(), result)
    }

    // ── computeMeshHash: zero substitution ───────────────────────────────────

    @Test
    fun zeroSubstitutedToOne() {
        // Act — "76349" produces raw XOR-fold 0x0000, which must be substituted to 0x0001
        val result = MeshHashFilter.computeMeshHash("76349")

        // Assert
        assertEquals(0x0001u.toUShort(), result)
    }

    // ── matches: universal broadcast (0x0000) ─────────────────────────────────

    @Test
    fun universalHashMatchesAnyLocalHash() {
        // Arrange — payloadMeshHash = 0x0000 is a universal broadcast
        val universalHash = 0x0000u.toUShort()

        // Act & Assert
        assertTrue(MeshHashFilter.matches(universalHash, 0x1234u.toUShort()))
        assertTrue(MeshHashFilter.matches(universalHash, 0xF70Bu.toUShort()))
    }

    // ── matches: exact match ──────────────────────────────────────────────────

    @Test
    fun sameNonZeroHashMatches() {
        // Arrange
        val hash = 0xF70Bu.toUShort()

        // Act
        val result = MeshHashFilter.matches(hash, hash)

        // Assert
        assertTrue(result)
    }

    // ── matches: mismatch ─────────────────────────────────────────────────────

    @Test
    fun differentNonZeroHashesDoNotMatch() {
        // Arrange
        val payloadHash = 0xF70Bu.toUShort()
        val localHash = 0xDE35u.toUShort()

        // Act
        val result = MeshHashFilter.matches(payloadHash, localHash)

        // Assert
        assertFalse(result)
    }

    @Test
    fun differentHashesNoMatchAlternativePair() {
        // Arrange
        val payloadHash = 0x1234u.toUShort()
        val localHash = 0x5678u.toUShort()

        // Act
        val result = MeshHashFilter.matches(payloadHash, localHash)

        // Assert
        assertFalse(result)
    }
}
