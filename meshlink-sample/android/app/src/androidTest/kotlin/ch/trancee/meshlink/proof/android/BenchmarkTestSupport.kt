package ch.trancee.meshlink.proof.android

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry

internal object BenchmarkTestSupport {
    private const val PACKAGE_NAME: String = "ch.trancee.meshlink.proof.android"
    private const val PROOF_LOG_FILE_NAME: String = "proof.log"
    private const val EXTRA_APP_ID: String = "meshlink.appId"
    private const val EXTRA_POWER_MODE: String = "meshlink.powerMode"
    private const val EXTRA_BENCHMARK_PAYLOAD_BYTES: String = "meshlink.benchmarkPayloadBytes"
    private const val EXTRA_BENCHMARK_BATTERY_LEVEL: String = "meshlink.benchmarkBatteryLevel"
    private const val EXTRA_BENCHMARK_IS_CHARGING: String = "meshlink.benchmarkIsCharging"
    private const val EXTRA_BENCHMARK_COLD_START: String = "meshlink.benchmarkColdStart"
    private const val EXTRA_BENCHMARK_TRANSPORT: String = "meshlink.benchmarkTransport"

    fun startProofApp(
        appId: String,
        powerMode: String? = null,
        benchmarkPayloadBytes: Int? = null,
        benchmarkBatteryLevel: Float? = null,
        benchmarkIsCharging: Boolean? = null,
        benchmarkColdStart: Boolean = false,
        benchmarkTransport: String? = null,
    ): ActivityScenario<MainActivity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_APP_ID, appId)
            powerMode?.let { value -> putExtra(EXTRA_POWER_MODE, value) }
            benchmarkPayloadBytes?.let { value -> putExtra(EXTRA_BENCHMARK_PAYLOAD_BYTES, value) }
            benchmarkBatteryLevel?.let { value -> putExtra(EXTRA_BENCHMARK_BATTERY_LEVEL, value) }
            benchmarkIsCharging?.let { value -> putExtra(EXTRA_BENCHMARK_IS_CHARGING, value) }
            if (benchmarkColdStart) {
                putExtra(EXTRA_BENCHMARK_COLD_START, true)
            }
            benchmarkTransport?.let { value -> putExtra(EXTRA_BENCHMARK_TRANSPORT, value) }
        }
        return ActivityScenario.launch(intent)
    }

    fun forceStopTargetApp(): Unit {
        executeShell("am force-stop $PACKAGE_NAME")
        SystemClock.sleep(250)
    }

    fun clearProofLog(): Unit {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteFile(PROOF_LOG_FILE_NAME)
    }

    fun waitForLogLine(text: String, timeoutMs: Long): String {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val match = readProofLogLines().lastOrNull { line -> line.contains(text) }
            if (match != null) {
                return match
            }
            SystemClock.sleep(200)
        }
        error("Timed out waiting for proof log line containing '$text'")
    }

    fun extractElapsedMs(logLine: String): Long {
        val match = ELAPSED_MS_REGEX.find(logLine) ?: error("Missing elapsedMs in '$logLine'")
        return match.groupValues[1].toLong()
    }

    fun extractThroughputKilobytesPerSecond(logLine: String): Double {
        val match = THROUGHPUT_REGEX.find(logLine) ?: error("Missing throughputKBps in '$logLine'")
        return match.groupValues[1].toDouble()
    }

    fun extractResult(logLine: String): String {
        val match = RESULT_REGEX.find(logLine) ?: error("Missing result in '$logLine'")
        return match.groupValues[1]
    }

    private fun readProofLogLines(): List<String> {
        return runCatching {
                InstrumentationRegistry.getInstrumentation()
                    .targetContext
                    .openFileInput(PROOF_LOG_FILE_NAME)
                    .bufferedReader()
                    .use { reader -> reader.readLines() }
            }
            .getOrElse { emptyList() }
    }

    private fun executeShell(command: String): String {
        val descriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { input ->
            input.readText()
        }
    }

    private val ELAPSED_MS_REGEX = Regex("elapsedMs=(\\d+)")
    private val THROUGHPUT_REGEX = Regex("throughputKBps=([0-9]+(?:\\.[0-9]+)?)")
    private val RESULT_REGEX = Regex("result=([^ ]+)")
}
