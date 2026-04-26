package ch.trancee.meshlink.power

data class PowerConfig(
    val performanceThreshold: Float = 0.80f,
    val powerSaverThreshold: Float = 0.30f,
    val hysteresisPercent: Float = 0.02f,
    val hysteresisDelayMillis: Long = 30_000L,
    val batteryPollIntervalMillis: Long = 5_000L,
    val evictionGracePeriodMillis: Long = 30_000L,
    val maxEvictionGracePeriodMillis: Long = 120_000L,
    val minThroughputBytesPerSec: Float = 1024f,
    val bootstrapDurationMillis: Long = 30_000L,
    val performanceMaxConnections: Int = 6,
    val balancedMaxConnections: Int = 4,
    val powerSaverMaxConnections: Int = 2,
)
