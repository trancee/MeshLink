package ch.trancee.meshlink.power

data class PowerConfig(
    val performanceThreshold: Float = 0.80f,
    val powerSaverThreshold: Float = 0.30f,
    val hysteresisPercent: Float = 0.02f,
    val hysteresisDelayMs: Long = 30_000L,
    val batteryPollIntervalMs: Long = 5_000L,
    val evictionGracePeriodMs: Long = 30_000L,
    val maxEvictionGracePeriodMs: Long = 120_000L,
    val minThroughputBytesPerSec: Float = 1024f,
    val bootstrapDurationMs: Long = 30_000L,
    val performanceMaxConnections: Int = 6,
    val balancedMaxConnections: Int = 4,
    val powerSaverMaxConnections: Int = 2,
)
