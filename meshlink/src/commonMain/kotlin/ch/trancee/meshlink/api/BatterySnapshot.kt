package ch.trancee.meshlink.api

/**
 * Normalized battery state snapshot fed into MeshLink automatic power-policy logic.
 *
 * [level] is clamped into the inclusive `0.0..1.0` range during construction.
 */
internal class BatterySnapshot internal constructor(level: Float, internal val isCharging: Boolean) {
    internal val level: Float = level.coerceIn(0f, 1f)

    override fun toString(): String {
        return "BatterySnapshot(level=$level, isCharging=$isCharging)"
    }
}
