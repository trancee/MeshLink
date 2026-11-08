package meshlink.routingsim.sim

import meshlink.routingsim.routing.RoutingParams
import meshlink.routingsim.routing.UnicastClassPolicy

/**
 * Stage D protocol variants, combined with "+" (e.g. "S5+K1+K3"):
 * - K1: conversation route lifetime 180 s after the last message.
 * - K2: end-to-end delivery ack that re-arms the routes on the path; one re-send without an ack.
 * - K3: cached reverse path (180 s) at every phone a message passes.
 * - S5 / S8: a phone whose own density estimate is below 5 (8) floods its own messages.
 * - F: full-range route request at once, alongside the data ring (no expanding ring).
 * - PIN: pinned low class (flood every message in every class, low-class retries; the adaptive broadcast floods).
 * - FG: faster Plumtree repair (graft timeout 150 ms, retry 100 ms instead of 500 / 250 ms).
 * - CU: Plumtree catch-up, announce messages received in the last 60 s on every new link.
 */
object StageDVariant {
    const val KEEP_MS = 180_000L

    val pinnedPolicy =
        UnicastClassPolicy(
            "PIN",
            ringHops = IntArray(3) { UnicastClassPolicy.FLOOD_ALL },
            linkUpRetry = BooleanArray(3) { true },
            retryMinMs = LongArray(3) { 2_000 },
            retryMaxMs = LongArray(3) { 30_000 },
        )

    fun apply(
        variant: String,
        p: RoutingParams,
    ): RoutingParams {
        if (variant.isEmpty()) return p
        var q = p
        for (flag in variant.split('+')) {
            q =
                when (flag) {
                    "K1" -> q.copy(convKeepMs = KEEP_MS)
                    "K2" -> q.copy(deliveryAck = true)
                    "K3" -> q.copy(reverseCacheMs = KEEP_MS)
                    "S5" -> q.copy(sparseFloodBelow = 5.0)
                    "S8" -> q.copy(sparseFloodBelow = 8.0)
                    "S10" -> q.copy(sparseFloodBelow = 10.0)
                    "F" -> q.copy(fullRangeDiscovery = true)
                    "PIN" -> q.copy(classPolicy = pinnedPolicy, pinFlood = true)
                    "FG" -> q.copy(graftTimeoutMs = 150, graftRetryMs = 100)
                    "CU" -> q.copy(catchUpMs = 60_000)
                    else -> if (StageEVariant.handles(flag) || StageFVariant.handles(flag)) q else error("unknown stage D variant flag $flag")
                }
        }
        return StageFVariant.apply(variant, q)
    }
}
