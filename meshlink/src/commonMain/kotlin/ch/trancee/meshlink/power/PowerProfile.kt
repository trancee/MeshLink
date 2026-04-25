package ch.trancee.meshlink.power

data class PowerProfile(
    val scanDutyPercent: Int,
    val adIntervalMs: Long,
    val keepaliveMs: Long,
    val presenceTimeoutMs: Long,
    val sweepIntervalMs: Long,
    val maxConnections: Int,
) {
    companion object {
        fun forTier(tier: PowerTier, config: PowerConfig): PowerProfile =
            when (tier) {
                PowerTier.PERFORMANCE ->
                    PowerProfile(
                        scanDutyPercent = 80,
                        adIntervalMs = 250L,
                        keepaliveMs = 5_000L,
                        presenceTimeoutMs = 1_500L,
                        sweepIntervalMs = 1_250L,
                        maxConnections = config.performanceMaxConnections,
                    )
                PowerTier.BALANCED ->
                    PowerProfile(
                        scanDutyPercent = 50,
                        adIntervalMs = 500L,
                        keepaliveMs = 15_000L,
                        presenceTimeoutMs = 4_000L,
                        sweepIntervalMs = 2_500L,
                        maxConnections = config.balancedMaxConnections,
                    )
                PowerTier.POWER_SAVER ->
                    PowerProfile(
                        scanDutyPercent = 17,
                        adIntervalMs = 1_000L,
                        keepaliveMs = 30_000L,
                        presenceTimeoutMs = 10_000L,
                        sweepIntervalMs = 5_000L,
                        maxConnections = config.powerSaverMaxConnections,
                    )
            }
    }
}
