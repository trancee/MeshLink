package ch.trancee.meshlink.proof.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PowerProfileBenchmark {
    @Test
    fun lowBatteryPowerSaverModeClampsScanDutyCycle(): Unit {
        // Arrange
        BenchmarkTestSupport.clearProofLog()
        val scenario =
            BenchmarkTestSupport.startProofApp(
                appId = POWER_APP_ID,
                powerMode = "powersaver",
                benchmarkBatteryLevel = 0.50f,
                benchmarkIsCharging = false,
            )

        try {
            // Act
            val logLine = BenchmarkTestSupport.waitForLogLine("DIAG POWER_MODE_CHANGED", 10_000)

            // Assert
            assertTrue(
                "Expected POWER_MODE_CHANGED to report the POWER_SAVER tier",
                logLine.contains("tier=POWER_SAVER"),
            )
            assertTrue(
                "Expected LOW-power scan duty cycle to stay at or below 5 percent",
                logLine.contains("scanDutyCyclePercent=5"),
            )
            assertTrue(
                "Expected LOW-power maintained connection interval to stay at or above 500 ms",
                logLine.contains("connectionIntervalMillis=500"),
            )
        } finally {
            scenario.close()
            BenchmarkTestSupport.clearProofLog()
        }
    }

    @Test
    fun lowBatteryPowerSaverModeDelivers256ByteMessageWithinFiveSeconds(): Unit {
        // Arrange
        BenchmarkTestSupport.requirePeerBenchmarksEnabled()
        BenchmarkTestSupport.clearProofLog()
        val scenario =
            BenchmarkTestSupport.startProofApp(
                appId = POWER_DELIVERY_APP_ID,
                powerMode = "powersaver",
                benchmarkPayloadBytes = 256,
                benchmarkBatteryLevel = 0.50f,
                benchmarkIsCharging = false,
            )

        try {
            // Act
            val logLine =
                BenchmarkTestSupport.waitForLogLine(
                    "BENCHMARK transport bytes=",
                    LOW_POWER_DELIVERY_RESULT_TIMEOUT_MS,
                )
            val elapsedMs = BenchmarkTestSupport.extractElapsedMs(logLine)
            val result = BenchmarkTestSupport.extractResult(logLine)

            // Assert
            assertEquals(
                "LOW-power delivery benchmarks require a nearby proof peer running appId=$POWER_DELIVERY_APP_ID",
                "Sent",
                result,
            )
            assertTrue(
                "Expected LOW-power 256-byte delivery <= 5000 ms, but observed $elapsedMs ms",
                elapsedMs <= 5_000,
            )
        } finally {
            scenario.close()
            BenchmarkTestSupport.clearProofLog()
        }
    }

    private companion object {
        private const val POWER_APP_ID: String = "demo.meshlink.benchmark.power"
        private const val POWER_DELIVERY_APP_ID: String = "demo.meshlink.benchmark.power.delivery"
        private const val LOW_POWER_DELIVERY_RESULT_TIMEOUT_MS: Long = 60_000L
    }
}
