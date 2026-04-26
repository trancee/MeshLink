package ch.trancee.meshlink.power

data class PowerProfile(
    val scanDutyPercent: Int,
    val adIntervalMillis: Long,
    val keepaliveMillis: Long,
    val presenceTimeoutMillis: Long,
    val sweepIntervalMillis: Long,
    val maxConnections: Int,
) {
    companion object {
        fun forTier(tier: PowerTier, config: PowerConfig): PowerProfile =
            when (tier) {
                PowerTier.PERFORMANCE ->
                    PowerProfile(
                        scanDutyPercent = 80,
                        adIntervalMillis = 250L,
                        keepaliveMillis = 5_000L,
                        presenceTimeoutMillis = 1_500L,
                        sweepIntervalMillis = 1_250L,
                        maxConnections = config.performanceMaxConnections,
                    )
                PowerTier.BALANCED ->
                    PowerProfile(
                        scanDutyPercent = 50,
                        adIntervalMillis = 500L,
                        keepaliveMillis = 15_000L,
                        presenceTimeoutMillis = 4_000L,
                        sweepIntervalMillis = 2_500L,
                        maxConnections = config.balancedMaxConnections,
                    )
                PowerTier.POWER_SAVER ->
                    PowerProfile(
                        scanDutyPercent = 17,
                        adIntervalMillis = 1_000L,
                        keepaliveMillis = 30_000L,
                        presenceTimeoutMillis = 10_000L,
                        sweepIntervalMillis = 5_000L,
                        maxConnections = config.powerSaverMaxConnections,
                    )
            }
    }
}
