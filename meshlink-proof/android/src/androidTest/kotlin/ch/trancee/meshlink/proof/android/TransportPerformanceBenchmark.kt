package ch.trancee.meshlink.proof.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@ProofBenchmarkTest
class TransportPerformanceBenchmark {
    @Test
    fun latency256ByteSendStaysWithinTarget(): Unit {
        // Arrange
        BenchmarkTestSupport.requirePeerBenchmarksEnabled()
        BenchmarkTestSupport.clearProofLog()
        val scenario =
            BenchmarkTestSupport.startProofApp(
                appId = LATENCY_APP_ID,
                benchmarkPayloadBytes = 256,
            )

        try {
            // Act
            val logLine = BenchmarkTestSupport.waitForLogLine("BENCHMARK transport bytes=", 20_000)
            val elapsedMs = BenchmarkTestSupport.extractElapsedMs(logLine)
            val result = BenchmarkTestSupport.extractResult(logLine)

            // Assert
            assertEquals(
                "Latency benchmarks require a nearby proof peer running appId=$LATENCY_APP_ID",
                "Sent",
                result,
            )
            assertTrue(
                "Expected 256-byte latency <= 50 ms, but observed $elapsedMs ms",
                elapsedMs <= 50,
            )
        } finally {
            scenario.close()
            BenchmarkTestSupport.clearProofLog()
        }
    }

    @Test
    fun throughput64KiBStaysWithinTarget(): Unit {
        // Arrange
        BenchmarkTestSupport.requirePeerBenchmarksEnabled()
        BenchmarkTestSupport.clearProofLog()
        val scenario =
            BenchmarkTestSupport.startProofApp(
                appId = THROUGHPUT_APP_ID,
                benchmarkPayloadBytes = 64 * 1024,
            )

        try {
            // Act
            val logLine = BenchmarkTestSupport.waitForLogLine("BENCHMARK transport bytes=", 30_000)
            val throughputKilobytesPerSecond =
                BenchmarkTestSupport.extractThroughputKilobytesPerSecond(logLine)
            val result = BenchmarkTestSupport.extractResult(logLine)

            // Assert
            assertEquals(
                "Throughput benchmarks require a nearby proof peer running appId=$THROUGHPUT_APP_ID",
                "Sent",
                result,
            )
            assertTrue(
                "Expected throughput >= 80 KB/s, but observed $throughputKilobytesPerSecond KB/s",
                throughputKilobytesPerSecond >= 80.0,
            )
        } finally {
            scenario.close()
            BenchmarkTestSupport.clearProofLog()
        }
    }

    private companion object {
        private const val LATENCY_APP_ID: String = "demo.meshlink.benchmark.latency"
        private const val THROUGHPUT_APP_ID: String = "demo.meshlink.benchmark.throughput"
    }
}
