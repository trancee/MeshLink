package ch.trancee.meshlink.engine

import ch.trancee.meshlink.power.PowerTier
import ch.trancee.meshlink.transfer.ChunkSizePolicy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PowerTierCodecTest {

    // ── encode ─────────────────────────────────────────────────────────────────

    @Test
    fun `encode PERFORMANCE returns 0x00`() {
        assertContentEquals(byteArrayOf(0x00), PowerTierCodec.encode(PowerTier.PERFORMANCE))
    }

    @Test
    fun `encode BALANCED returns 0x01`() {
        assertContentEquals(byteArrayOf(0x01), PowerTierCodec.encode(PowerTier.BALANCED))
    }

    @Test
    fun `encode POWER_SAVER returns 0x02`() {
        assertContentEquals(byteArrayOf(0x02), PowerTierCodec.encode(PowerTier.POWER_SAVER))
    }

    // ── decode — happy paths ───────────────────────────────────────────────────

    @Test
    fun `decode 0x00 returns PERFORMANCE`() {
        assertEquals(PowerTier.PERFORMANCE, PowerTierCodec.decode(byteArrayOf(0x00)))
    }

    @Test
    fun `decode 0x01 returns BALANCED`() {
        assertEquals(PowerTier.BALANCED, PowerTierCodec.decode(byteArrayOf(0x01)))
    }

    @Test
    fun `decode 0x02 returns POWER_SAVER`() {
        assertEquals(PowerTier.POWER_SAVER, PowerTierCodec.decode(byteArrayOf(0x02)))
    }

    // ── decode — negative / fallback ───────────────────────────────────────────

    @Test
    fun `decode unknown byte 0xFF returns BALANCED`() {
        assertEquals(PowerTier.BALANCED, PowerTierCodec.decode(byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun `decode empty array returns BALANCED`() {
        assertEquals(PowerTier.BALANCED, PowerTierCodec.decode(byteArrayOf()))
    }

    // ── round-trip ─────────────────────────────────────────────────────────────

    @Test
    fun `encode then decode is identity for all tiers`() {
        for (tier in PowerTier.entries) {
            assertEquals(tier, PowerTierCodec.decode(PowerTierCodec.encode(tier)))
        }
    }

    // ── HandshakeConfig default values ────────────────────────────────────────

    @Test
    fun `HandshakeConfig default values`() {
        val cfg = HandshakeConfig()
        assertEquals(10, cfg.maxConcurrentHandshakes)
        assertEquals(1_000L, cfg.rateLimitWindowMs)
    }

    @Test
    fun `HandshakeConfig custom values override defaults`() {
        val cfg = HandshakeConfig(maxConcurrentHandshakes = 5, rateLimitWindowMs = 500L)
        assertEquals(5, cfg.maxConcurrentHandshakes)
        assertEquals(500L, cfg.rateLimitWindowMs)
    }

    // ── MeshEngineConfig default values ───────────────────────────────────────

    @Test
    fun `MeshEngineConfig default values are consistent`() {
        val cfg = MeshEngineConfig()
        assertEquals(5_000L, cfg.routing.helloIntervalMillis)
        assertEquals(244, cfg.transfer.chunkSize)
        assertEquals(0.80f, cfg.power.performanceThreshold)
        assertEquals(10, cfg.handshake.maxConcurrentHandshakes)
        assertEquals(ChunkSizePolicy.GATT.size, cfg.chunkSize.size)
    }

    @Test
    fun `MeshEngineConfig custom chunkSize`() {
        val cfg = MeshEngineConfig(chunkSize = ChunkSizePolicy.L2CAP)
        assertEquals(ChunkSizePolicy.L2CAP.size, cfg.chunkSize.size)
    }

    @Test
    fun `MeshEngineConfig custom handshake config`() {
        val cfg = MeshEngineConfig(handshake = HandshakeConfig(maxConcurrentHandshakes = 3))
        assertEquals(3, cfg.handshake.maxConcurrentHandshakes)
    }
}
