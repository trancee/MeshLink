package ch.trancee.meshlink

/**
 * How a node picks its density class (ADR 0008 decisions 2 and 8): from its estimates of local
 * density, or pinned to one class.
 */
public sealed interface Density {
    /** The node classifies its local density from what it hears, and adapts as it changes. */
    public data object Automatic : Density

    /**
     * The node always behaves as [densityClass]. Pinning [DensityClass.Low] floods everywhere for the
     * lowest latency, at about 2 to 4 times the load of the busiest phone (ADR 0008 decision 8).
     */
    public data class Pinned(
        /** The class the node keeps. */
        public val densityClass: DensityClass,
    ) : Density {
        override fun toString(): String = "Pinned($densityClass)"
    }
}

/** A node's local density class (ADR 0008 decision 2) and the link budget d it sets (decision 1). */
public enum class DensityClass(
    internal val links: Int,
) {
    /** Under about 10 peers in range: the node floods and keeps up to 6 links. */
    Low(LOW_LINKS),

    /** About 10 to 50 peers in range: the node routes and keeps up to 5 links. */
    Medium(DENSE_LINKS),

    /** About 50 peers in range or more: the node routes and keeps up to 5 links. */
    High(DENSE_LINKS),
}

private const val LOW_LINKS = 6
private const val DENSE_LINKS = 5
