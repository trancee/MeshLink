package ch.trancee.meshlink

/**
 * How long a node rests between its 4 s scan windows in each [DensityClass] (ADR 0011 amendment
 * "power policy"; ADR 0008, scan schedule). These are foreground values: the operating system sets
 * background scanning, so no value promises background discovery latency.
 */
public enum class Power(
    private val low: Long,
    private val medium: Long,
    private val high: Long,
) {
    /** Rests 4 s at low density, 12 s at medium and 16 s at high. */
    Balanced(BALANCED_LOW_OFF, BALANCED_MEDIUM_OFF, BALANCED_HIGH_OFF),

    /**
     * Rests 2.5 s at low density, 8 s at medium and 12 s at high: faster discovery for more scanning.
     * 2.5 s, not 2 s, keeps scan starts 6.5 s apart, under Android's limit of 5 starts per 30 s.
     */
    Responsive(RESPONSIVE_LOW_OFF, RESPONSIVE_MEDIUM_OFF, RESPONSIVE_HIGH_OFF),
    ;

    /** How long a central of class [density] rests after a scan window. */
    internal fun offMillis(density: DensityClass): Long =
        when (density) {
            DensityClass.Low -> low
            DensityClass.Medium -> medium
            DensityClass.High -> high
        }
}

private const val BALANCED_LOW_OFF = 4_000L
private const val BALANCED_MEDIUM_OFF = 12_000L
private const val BALANCED_HIGH_OFF = 16_000L
private const val RESPONSIVE_LOW_OFF = 2_500L
private const val RESPONSIVE_MEDIUM_OFF = 8_000L
private const val RESPONSIVE_HIGH_OFF = 12_000L
