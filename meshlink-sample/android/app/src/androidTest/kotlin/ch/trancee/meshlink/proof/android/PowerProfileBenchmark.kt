package ch.trancee.meshlink.proof.android

import androidx.test.ext.junit.runners.AndroidJUnit4
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
        } finally {
            scenario.close()
            BenchmarkTestSupport.clearProofLog()
        }
    }

    private companion object {
        private const val POWER_APP_ID: String = "demo.meshlink.benchmark.power"
    }
}
