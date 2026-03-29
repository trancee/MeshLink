package io.meshlink.power

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdvertisingPolicyTest {

    private val policy = AdvertisingPolicy()

    @Test
    fun performanceIntervalIs250ms() {
        assertEquals(250L, policy.advertisingIntervalMillis(AdvertisingPolicy.PowerMode.PERFORMANCE))
    }

    @Test
    fun balancedIntervalIs500ms() {
        assertEquals(500L, policy.advertisingIntervalMillis(AdvertisingPolicy.PowerMode.BALANCED))
    }

    @Test
    fun powerSaverIntervalIs1000ms() {
        assertEquals(1000L, policy.advertisingIntervalMillis(AdvertisingPolicy.PowerMode.POWER_SAVER))
    }

    @Test
    fun performanceScanDutyIs90Percent() {
        assertEquals(90, policy.scanDutyPercent(AdvertisingPolicy.PowerMode.PERFORMANCE))
    }

    @Test
    fun balancedScanDutyIs50Percent() {
        assertEquals(50, policy.scanDutyPercent(AdvertisingPolicy.PowerMode.BALANCED))
    }

    @Test
    fun powerSaverScanDutyIs15Percent() {
        assertEquals(15, policy.scanDutyPercent(AdvertisingPolicy.PowerMode.POWER_SAVER))
    }

    @Test
    fun scanWindowCalculation() {
        assertEquals(50L, policy.scanWindowMillis(AdvertisingPolicy.PowerMode.BALANCED, 100L))
    }

    @Test
    fun isAggressiveDiscoveryTrueOnlyForPerformance() {
        assertTrue(policy.isAggressiveDiscovery(AdvertisingPolicy.PowerMode.PERFORMANCE))
        assertFalse(policy.isAggressiveDiscovery(AdvertisingPolicy.PowerMode.BALANCED))
        assertFalse(policy.isAggressiveDiscovery(AdvertisingPolicy.PowerMode.POWER_SAVER))
    }

    @Test
    fun customPolicyValuesOverrideDefaults() {
        val custom = AdvertisingPolicy(
            performanceAdvIntervalMillis = 100L,
            balancedAdvIntervalMillis = 300L,
            powerSaverAdvIntervalMillis = 2000L,
            performanceScanDutyPercent = 95,
            balancedScanDutyPercent = 60,
            powerSaverScanDutyPercent = 10,
        )
        assertEquals(100L, custom.advertisingIntervalMillis(AdvertisingPolicy.PowerMode.PERFORMANCE))
        assertEquals(300L, custom.advertisingIntervalMillis(AdvertisingPolicy.PowerMode.BALANCED))
        assertEquals(2000L, custom.advertisingIntervalMillis(AdvertisingPolicy.PowerMode.POWER_SAVER))
        assertEquals(95, custom.scanDutyPercent(AdvertisingPolicy.PowerMode.PERFORMANCE))
        assertEquals(60, custom.scanDutyPercent(AdvertisingPolicy.PowerMode.BALANCED))
        assertEquals(10, custom.scanDutyPercent(AdvertisingPolicy.PowerMode.POWER_SAVER))
    }

    @Test
    fun intervalsIncreaseMonotonically() {
        val perf = policy.advertisingIntervalMillis(AdvertisingPolicy.PowerMode.PERFORMANCE)
        val bal = policy.advertisingIntervalMillis(AdvertisingPolicy.PowerMode.BALANCED)
        val saver = policy.advertisingIntervalMillis(AdvertisingPolicy.PowerMode.POWER_SAVER)
        assertTrue(perf < bal, "Performance interval should be less than Balanced")
        assertTrue(bal < saver, "Balanced interval should be less than PowerSaver")
    }

    @Test
    fun dutyCyclesDecreaseMonotonically() {
        val perf = policy.scanDutyPercent(AdvertisingPolicy.PowerMode.PERFORMANCE)
        val bal = policy.scanDutyPercent(AdvertisingPolicy.PowerMode.BALANCED)
        val saver = policy.scanDutyPercent(AdvertisingPolicy.PowerMode.POWER_SAVER)
        assertTrue(perf > bal, "Performance duty should be greater than Balanced")
        assertTrue(bal > saver, "Balanced duty should be greater than PowerSaver")
    }

    @Test
    fun scanWindowWithZeroIntervalReturnsZero() {
        assertEquals(0L, policy.scanWindowMillis(AdvertisingPolicy.PowerMode.PERFORMANCE, 0L))
        assertEquals(0L, policy.scanWindowMillis(AdvertisingPolicy.PowerMode.BALANCED, 0L))
        assertEquals(0L, policy.scanWindowMillis(AdvertisingPolicy.PowerMode.POWER_SAVER, 0L))
    }
}
