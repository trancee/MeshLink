package io.meshlink.transfer

import io.meshlink.transfer.ChunkSizePolicy.PowerMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChunkSizePolicyTest {

    private val policy = ChunkSizePolicy()
    private val header = 21

    @Test
    fun performanceModeWithLargeMtu() {
        assertEquals(8192, policy.effectiveChunkSize(PowerMode.PERFORMANCE, 16384))
    }

    @Test
    fun performanceModeWithSmallMtu() {
        val mtu = 512
        assertEquals(mtu - header, policy.effectiveChunkSize(PowerMode.PERFORMANCE, mtu))
    }

    @Test
    fun balancedModeLimitsTo4096() {
        assertEquals(4096, policy.effectiveChunkSize(PowerMode.BALANCED, 16384))
    }

    @Test
    fun powerSaverModeLimitsTo1024() {
        assertEquals(1024, policy.effectiveChunkSize(PowerMode.POWER_SAVER, 16384))
    }

    @Test
    fun mtuExactlyAtModeLimitPlusHeader() {
        // MTU = 8192 + 21 = 8213 → payload exactly 8192
        assertEquals(8192, policy.effectiveChunkSize(PowerMode.PERFORMANCE, 8192 + header))
        assertEquals(4096, policy.effectiveChunkSize(PowerMode.BALANCED, 4096 + header))
        assertEquals(1024, policy.effectiveChunkSize(PowerMode.POWER_SAVER, 1024 + header))
    }

    @Test
    fun mtuSmallerThanAllModes() {
        val mtu = 200
        val expected = mtu - header
        assertEquals(expected, policy.effectiveChunkSize(PowerMode.PERFORMANCE, mtu))
        assertEquals(expected, policy.effectiveChunkSize(PowerMode.BALANCED, mtu))
        assertEquals(expected, policy.effectiveChunkSize(PowerMode.POWER_SAVER, mtu))
    }

    @Test
    fun verySmallMtuReturnsMinimumOne() {
        assertEquals(1, policy.effectiveChunkSize(PowerMode.PERFORMANCE, header))
        assertEquals(1, policy.effectiveChunkSize(PowerMode.PERFORMANCE, header - 5))
        assertEquals(1, policy.effectiveChunkSize(PowerMode.BALANCED, 0))
    }

    @Test
    fun maxPayloadForModeReturnsCorrectValues() {
        assertEquals(8192, policy.maxPayloadForMode(PowerMode.PERFORMANCE))
        assertEquals(4096, policy.maxPayloadForMode(PowerMode.BALANCED))
        assertEquals(1024, policy.maxPayloadForMode(PowerMode.POWER_SAVER))
    }

    @Test
    fun customPolicyValues() {
        val custom = ChunkSizePolicy(
            headerSize = 10,
            performanceMaxPayload = 2000,
            balancedMaxPayload = 1000,
            powerSaverMaxPayload = 500,
        )
        assertEquals(2000, custom.effectiveChunkSize(PowerMode.PERFORMANCE, 5000))
        assertEquals(990, custom.effectiveChunkSize(PowerMode.BALANCED, 1000))
        assertEquals(500, custom.maxPayloadForMode(PowerMode.POWER_SAVER))
    }

    @Test
    fun allModesWithSameMtuProduceExpectedOrdering() {
        val mtu = 16384
        val perf = policy.effectiveChunkSize(PowerMode.PERFORMANCE, mtu)
        val balanced = policy.effectiveChunkSize(PowerMode.BALANCED, mtu)
        val saver = policy.effectiveChunkSize(PowerMode.POWER_SAVER, mtu)

        assertTrue(perf > balanced, "PERFORMANCE ($perf) should exceed BALANCED ($balanced)")
        assertTrue(balanced > saver, "BALANCED ($balanced) should exceed POWER_SAVER ($saver)")
    }
}
