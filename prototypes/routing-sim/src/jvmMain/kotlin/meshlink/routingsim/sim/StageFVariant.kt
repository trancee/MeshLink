package meshlink.routingsim.sim

import meshlink.routingsim.routing.RoutingParams

/**
 * Stage F flags, combined with the stage D/E flags by "+" (e.g. "F+K2+T8+I12+H+CV+GA2"):
 * - H: ADR 0014 full catch-up (A1): holding quota for others, catch-up summaries, claims, tombstones, H(k).
 * - CV: ADR 0016 per-conversation tags (A2). CVL: the A2L fallback (keep the previous-epoch tag while it works).
 * - LK: ADR 0018 certificate lookups with a cold cache (A3).
 * - LG: ADR 0015 large messages (A4): 2% of chat messages 16–128 KiB, never flooded, never held by relays.
 * - LD: diagnostic, not pre-registered: relays forward only the first lookup reply per request (salt).
 * - RL: per-incoming-link token bucket for route requests and lookups (1/s, burst 5).
 * - ATK: this instance carries the misbehaving phone's lookups (rate-limit test).
 * - G<name>: arm group; a node's hold quota is shared by the unicast and broadcast instances of one group.
 */
object StageFVariant {
    private val group = Regex("G([A-Za-z0-9]+)")
    private val plain = setOf("H", "CV", "CVL", "LK", "LD", "LG", "RL", "ATK")

    fun handles(flag: String) = flag in plain || group.matches(flag)

    private fun flags(variant: String) = if (variant.isEmpty()) emptyList() else variant.split('+')

    fun has(
        variant: String,
        flag: String,
    ) = flag in flags(variant)

    fun group(variant: String): String = flags(variant).firstNotNullOfOrNull { group.matchEntire(it)?.groupValues?.get(1) } ?: ""

    fun apply(
        variant: String,
        p: RoutingParams,
    ): RoutingParams {
        var q = p
        for (f in flags(variant)) {
            q =
                when (f) {
                    "H" -> q.copy(holdForOthers = true)
                    "CV", "CVL" -> q.copy(convTags = true)
                    "LK" -> q.copy(certLookup = true)
                    "LD" -> q.copy(lookupReplyDedupe = true)
                    "LG" -> q.copy(largeMessages = true)
                    "RL" -> q.copy(rateLimitPerS = 1.0, rateLimitBurst = 5.0)
                    else -> q
                }
        }
        return q
    }
}
