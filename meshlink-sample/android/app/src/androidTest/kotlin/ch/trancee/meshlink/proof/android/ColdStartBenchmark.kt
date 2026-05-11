package ch.trancee.meshlink.proof.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ColdStartBenchmark {
    @Test
    fun coldStartReachesMeshStartedWithinTarget(): Unit {
        // Arrange
        BenchmarkTestSupport.clearProofLog()
        val scenario =
            BenchmarkTestSupport.startProofApp(
                appId = COLD_START_APP_ID,
                benchmarkColdStart = true,
            )

        try {
            // Act
            val logLine = BenchmarkTestSupport.waitForLogLine("BENCHMARK coldStart", 10_000)
            val elapsedMs = BenchmarkTestSupport.extractElapsedMs(logLine)

            // Assert
            assertTrue(
                "Expected cold start < 500 ms, but observed $elapsedMs ms",
                elapsedMs < 500,
            )
        } finally {
            scenario.close()
            BenchmarkTestSupport.clearProofLog()
        }
    }

    private companion object {
        private const val COLD_START_APP_ID: String = "demo.meshlink.benchmark.coldstart"
    }
}
