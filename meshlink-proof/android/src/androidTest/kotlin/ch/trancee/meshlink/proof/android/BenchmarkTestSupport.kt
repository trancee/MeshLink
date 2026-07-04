package ch.trancee.meshlink.proof.android

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue

internal object BenchmarkTestSupport {
    private const val PACKAGE_NAME: String = "ch.trancee.meshlink.proof.android"
    private const val PROOF_LOG_FILE_NAME: String = "proof.log"
    private const val EXTRA_APP_ID: String = "meshlink.appId"
    private const val EXTRA_POWER_MODE: String = "meshlink.powerMode"
    private const val EXTRA_BENCHMARK_PAYLOAD_BYTES: String = "meshlink.benchmarkPayloadBytes"
    private const val EXTRA_BENCHMARK_COLD_START: String = "meshlink.benchmarkColdStart"
    private const val EXTRA_BENCHMARK_TRANSPORT: String = "meshlink.benchmarkTransport"

    fun startProofApp(
        appId: String,
        powerMode: String? = null,
        benchmarkPayloadBytes: Int? = null,
        benchmarkColdStart: Boolean = false,
        benchmarkTransport: String? = null,
    ): ActivityScenario<android.app.Activity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        requireBluetoothEnabled(context)
        ensureTargetRuntimePermissionsGranted()
        val intent = Intent().setClassName(PACKAGE_NAME, "$PACKAGE_NAME.MainActivity").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_APP_ID, appId)
            powerMode?.let { value -> putExtra(EXTRA_POWER_MODE, value) }
            benchmarkPayloadBytes?.let { value -> putExtra(EXTRA_BENCHMARK_PAYLOAD_BYTES, value) }
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

    fun requirePeerBenchmarksEnabled(): Unit {
        val enabled =
            InstrumentationRegistry.getArguments().getString(PEER_BENCHMARKS_ARGUMENT) == "true"
        assumeTrue(
            "Requires a nearby proof peer and -e $PEER_BENCHMARKS_ARGUMENT true",
            enabled,
        )
    }

    fun requireL2capRouteBenchmarkSupported(): Unit {
        assumeTrue(
            "Requires Android 14+ L2CAP client sockets for the fast route benchmark",
            Build.VERSION.SDK_INT >= 34,
        )
    }

    private fun requireBluetoothEnabled(context: Context): Unit {
        val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
        val enabled = bluetoothManager?.adapter?.isEnabled == true
        assumeTrue(
            "Requires Bluetooth to be turned on before running proof benchmarks",
            enabled,
        )
    }

    private fun ensureTargetRuntimePermissionsGranted(): Unit {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val uiAutomation = instrumentation.uiAutomation
        requiredTargetRuntimePermissions().forEach { permission ->
            if (targetContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                return@forEach
            }
            val grantFailure =
                runCatching {
                    if (Build.VERSION.SDK_INT >= 28) {
                        runCatching {
                            uiAutomation.adoptShellPermissionIdentity(GRANT_RUNTIME_PERMISSIONS_PERMISSION)
                        }.recoverCatching { uiAutomation.adoptShellPermissionIdentity() }
                        uiAutomation.grantRuntimePermission(PACKAGE_NAME, permission)
                        runCatching { uiAutomation.dropShellPermissionIdentity() }
                    } else {
                        val descriptor =
                            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                                "pm grant $PACKAGE_NAME $permission"
                            )
                        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { input ->
                            input.readText()
                        }
                    }
                }.exceptionOrNull()
            check(waitForTargetPermissionGrant(targetContext, permission)) {
                val failureSuffix =
                    grantFailure?.let { error ->
                        val message = error.message?.takeIf(String::isNotBlank)
                        if (message == null) {
                            "${error::class.simpleName}"
                        } else {
                            "${error::class.simpleName}: $message"
                        }
                    } ?: "permission remained denied"
                "Failed to grant $permission to $PACKAGE_NAME: $failureSuffix"
            }
        }
    }

    private fun adoptGrantRuntimePermissionsIdentity(uiAutomation: android.app.UiAutomation) {
        runCatching {
            uiAutomation.adoptShellPermissionIdentity(GRANT_RUNTIME_PERMISSIONS_PERMISSION)
        }.recoverCatching {
            uiAutomation.adoptShellPermissionIdentity()
        }.getOrElse { error ->
            throw IllegalStateException(
                "Failed to adopt shell permission identity for runtime grants",
                error,
            )
        }
    }

    private fun waitForTargetPermissionGrant(context: Context, permission: String): Boolean {
        val deadline = SystemClock.elapsedRealtime() + PERMISSION_SETTLE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                return true
            }
            SystemClock.sleep(PERMISSION_SETTLE_POLL_MS)
        }
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requiredTargetRuntimePermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= 31) {
            listOf(
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.BLUETOOTH_ADVERTISE",
            )
        } else {
            listOf("android.permission.ACCESS_FINE_LOCATION")
        }
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
    private const val PEER_BENCHMARKS_ARGUMENT: String = "meshlinkBenchmarkEnablePeerTests"
    private const val GRANT_RUNTIME_PERMISSIONS_PERMISSION: String =
        "android.permission.GRANT_RUNTIME_PERMISSIONS"
    private const val PERMISSION_SETTLE_TIMEOUT_MS: Long = 2_000L
    private const val PERMISSION_SETTLE_POLL_MS: Long = 100L
}
