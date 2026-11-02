package meshlink.routingsim.sim

import meshlink.routingsim.routing.RoutingSizes

/**
 * Stage E wire-format flags, combined with the stage D flags by "+" (e.g. "F+K2+T4+I8"):
 * - T<n>: routing tag of n bytes (default 8).
 * - I<n>: message ID of n bytes (default 16).
 * - M<k>x<f>: every message is exactly k fragments of f bytes on the wire (per-hop reassembly unless CT).
 * - CT: cut-through, a relay forwards each fragment as it arrives instead of reassembling first.
 */
object StageEVariant {
    private val tag = Regex("T([0-9]+)")
    private val id = Regex("I([0-9]+)")
    private val msg = Regex("M([0-9]+)x([0-9]+)")

    fun handles(flag: String) = flag == "CT" || tag.matches(flag) || id.matches(flag) || msg.matches(flag)

    private fun flags(variant: String) = if (variant.isEmpty()) emptyList() else variant.split('+')

    fun sizes(variant: String): RoutingSizes {
        var t = 8
        var m = 16
        for (f in flags(variant)) {
            tag.matchEntire(f)?.let { t = it.groupValues[1].toInt() }
            id.matchEntire(f)?.let { m = it.groupValues[1].toInt() }
        }
        return RoutingSizes(tagBytes = t, messageIdBytes = m)
    }

    /** (fragments, fragment bytes) or null for the normal traffic sizes. */
    fun fragments(variant: String): Pair<Int, Int>? =
        flags(variant).firstNotNullOfOrNull { f -> msg.matchEntire(f)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() } }

    fun cutThrough(variant: String) = "CT" in flags(variant)
}
