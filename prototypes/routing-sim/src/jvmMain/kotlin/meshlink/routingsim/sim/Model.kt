package meshlink.routingsim.sim

/**
 * Calibration of the link-level model. Every value is cited in the README calibration table.
 * Times in milliseconds, throughput in bytes per second, power in dBm/dB.
 */
data class Calibration(
    val txPowerDbm: Double = 0.0,
    val pathLossAt1mDb: Double = 40.0,
    val pathLossExponent: Double = 3.0,
    val shadowingSigmaDb: Double = 4.0,
    val scanNoiseSigmaDb: Double = 2.0,
    val sensitivityDbm: Double = -88.0,
    val dropHysteresisDb: Double = 2.0,
    val sybilBoostDb: Double = 10.0,
    val connectMinMs: Long = 700,
    val connectMaxMs: Long = 1_200,
    val handshakeMinMs: Long = 55,
    val handshakeMaxMs: Long = 90,
    val firstAttemptFailure: Double = 0.3,
    val retryFailure: Double = 0.1,
    val retryWindowMs: Long = 30_000,
    val contentionFailurePerPeer: Double = 1.0 / 400.0,
    val failureCap: Double = 0.6,
    val contentionThroughputPeers: Double = 100.0,
    val throughputMinBps: Double = 62_000.0,
    val throughputMaxBps: Double = 89_000.0,
    val rttMinMs: Long = 34,
    val rttMaxMs: Long = 56,
    /** Mean of the exponential supervision-drop hazard; fitted to cairn link lifetimes (README). */
    val linkDropMeanMs: Double = 45_000.0,
    val scanHearProbability: Double = 0.8,
    val tickMs: Long = 2_000,
    val mobilityTickMs: Long = 2_000,
    val churnMeanUpMs: Double = 600_000.0,
    val churnOffMs: Long = 30_000,
    val iosWakeMs: Long = 10_000,
    val iosCycleMs: Long = 60_000,
    val iosBackgroundShare: Double = 0.5,
    val sybilBudget: Int = 16,
    val pedestrianSpeedMin: Double = 0.8,
    val pedestrianSpeedMax: Double = 1.8,
    val pauseMaxMs: Long = 30_000,
    val groupSize: Int = 8,
    val groupRadiusM: Double = 15.0,
    val corridorWidthM: Double = 10.0,
    val partitionWalkSpeed: Double = 1.4,
    val partitionSplitAtMs: Long = 240_000,
    val partitionReturnAtMs: Long = 480_000,
)

enum class Density(
    val label: String,
    val targetPeers: Double,
) {
    LOW("low", 6.0),
    MEDIUM("medium", 25.0),
    HIGH("high", 100.0),

    /** Stage C dynamic scenarios: density changes within the run (geometry in [VenueMobility]). */
    DYNAMIC("dynamic", 6.0),

    /** Stage C scenario E: crowd hovering at the low/medium boundary. */
    AT10("at10", 10.0),

    /** Stage C scenario E: crowd hovering at the medium/high boundary. */
    AT50("at50", 50.0),
}

enum class MobilityKind(
    val label: String,
) {
    STATIC("static"),
    RANDOM_WAYPOINT("waypoint"),
    GROUP("group"),
    CORRIDOR("corridor"),
    PARTITION_MERGE("partition-merge"),

    /** Stage C A: a crowd converges on a venue (low → medium → high over about 10 min). */
    ARRIVAL("arrival"),

    /** Stage C B: the crowd leaves the venue (high → low). */
    DEPARTURE("departure"),

    /** Stage C C: dense core and sparse edge in one mesh. */
    CORE_EDGE("core-edge"),

    /** Stage C D: two thirds of the phones join within 60 s (3× peers). */
    SURGE("surge"),
}

enum class PlatformMix(
    val label: String,
) {
    ANDROID("android"),
    HALF_IOS("half-ios"),
}

enum class StrategyKind(
    val label: String,
) {
    N0("N0"),
    N1N2("N1+N2"),
    N1N2_BRIDGE("N1+N2+bridge"),
}

data class Scenario(
    val density: Density,
    val nodes: Int,
    val mobility: MobilityKind,
    val platform: PlatformMix,
    val degree: Int,
    val sybilShare: Double = 0.0,
    val durationMs: Long = 900_000,
    val warmupMs: Long = 120_000,
    /** Overrides [Calibration.linkDropMeanMs] (seconds; 0 = no supervision drops) for the sensitivity sweep. */
    val dropMeanS: Int? = null,
) {
    /** Stage C: true-time window in which density transitions happen (null = none or the whole run). */
    val transitionWindowMs: LongRange?
        get() =
            when (mobility) {
                MobilityKind.ARRIVAL -> 60_000L..900_000L
                MobilityKind.DEPARTURE -> 240_000L..1_100_000L
                MobilityKind.SURGE -> VenueMobility.SURGE_AT_MS..VenueMobility.SURGE_AT_MS + 180_000L
                else -> null
            }

    val key: String
        get() =
            "${density.label}-n$nodes-${mobility.label}-${platform.label}-d$degree-sybil${(sybilShare * 100).toInt()}" +
                (dropMeanS?.let { "-drop$it" } ?: "")
}

/** Deterministic 64-bit mixing (SplitMix64 finalizer). */
fun mix64(x: Long): Long {
    var z = x + -0x61c8864680b583ebL
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    return z xor (z ushr 31)
}

fun seedOf(vararg parts: Any): Long {
    var h = 0x2545F4914F6CDD1DL
    for (p in parts) {
        for (c in p.toString()) h = mix64(h xor c.code.toLong())
        h = mix64(h xor 0x5bd1e995L)
    }
    return h
}
