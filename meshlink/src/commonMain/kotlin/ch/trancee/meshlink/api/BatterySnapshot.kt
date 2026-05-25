package ch.trancee.meshlink.api

/**
 * Normalized battery state snapshot fed into MeshLink automatic power-policy logic.
 *
 * [level] is clamped into the inclusive `0.0..1.0` range during construction.
 */
public class BatterySnapshot public constructor(level: Float, public val isCharging: Boolean) {
    public val level: Float = level.coerceIn(0f, 1f)

    override fun toString(): String {
        return "BatterySnapshot(level=$level, isCharging=$isCharging)"
    }
}
