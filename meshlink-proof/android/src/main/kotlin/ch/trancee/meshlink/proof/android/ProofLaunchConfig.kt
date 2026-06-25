package ch.trancee.meshlink.proof.android

import android.content.Intent
import ch.trancee.meshlink.config.PowerMode
import java.util.Locale

internal data class ProofLaunchConfig(
    val appId: String,
    val powerMode: PowerMode = PowerMode.Automatic,
    val benchmarkPayloadBytes: Int? = null,
    val benchmarkBatteryLevel: Float? = null,
    val benchmarkIsCharging: Boolean? = null,
    val benchmarkColdStart: Boolean = false,
    val benchmarkTransport: String? = null,
) {
    val powerModeLabel: String
        get() = powerMode.logLabel()

    companion object {
        private const val DEFAULT_APP_ID: String = "demo.meshlink"
        private const val EXTRA_APP_ID: String = "meshlink.appId"
        private const val EXTRA_POWER_MODE: String = "meshlink.powerMode"
        private const val EXTRA_BENCHMARK_PAYLOAD_BYTES: String = "meshlink.benchmarkPayloadBytes"
        private const val EXTRA_BENCHMARK_BATTERY_LEVEL: String = "meshlink.benchmarkBatteryLevel"
        private const val EXTRA_BENCHMARK_IS_CHARGING: String = "meshlink.benchmarkIsCharging"
        private const val EXTRA_BENCHMARK_COLD_START: String = "meshlink.benchmarkColdStart"
        private const val EXTRA_BENCHMARK_TRANSPORT: String = "meshlink.benchmarkTransport"

        fun fromIntent(intent: Intent?): ProofLaunchConfig {
            return ProofLaunchConfig(
                appId = intent?.getStringExtra(EXTRA_APP_ID) ?: DEFAULT_APP_ID,
                powerMode = parsePowerMode(intent?.getStringExtra(EXTRA_POWER_MODE)),
                benchmarkPayloadBytes =
                    intent?.getIntExtra(EXTRA_BENCHMARK_PAYLOAD_BYTES, 0)?.takeIf { it > 0 },
                benchmarkBatteryLevel =
                    intent
                        ?.takeIf { it.hasExtra(EXTRA_BENCHMARK_BATTERY_LEVEL) }
                        ?.getFloatExtra(EXTRA_BENCHMARK_BATTERY_LEVEL, 0f),
                benchmarkIsCharging =
                    intent
                        ?.takeIf { it.hasExtra(EXTRA_BENCHMARK_IS_CHARGING) }
                        ?.getBooleanExtra(EXTRA_BENCHMARK_IS_CHARGING, false),
                benchmarkColdStart =
                    intent?.getBooleanExtra(EXTRA_BENCHMARK_COLD_START, false) ?: false,
                benchmarkTransport = intent?.getStringExtra(EXTRA_BENCHMARK_TRANSPORT),
            )
        }

        private fun parsePowerMode(rawValue: String?): PowerMode {
            return when (rawValue?.lowercase(Locale.US)) {
                "performance" -> PowerMode.Performance
                "balanced" -> PowerMode.Balanced
                "powersaver" -> PowerMode.PowerSaver
                else -> PowerMode.Automatic
            }
        }
    }
}

internal fun PowerMode.logLabel(): String {
    return when (this) {
        PowerMode.Automatic -> "Automatic"
        PowerMode.Performance -> "Performance"
        PowerMode.Balanced -> "Balanced"
        PowerMode.PowerSaver -> "PowerSaver"
    }
}
