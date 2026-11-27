package ch.trancee.meshlink.internal

/**
 * PROTOTYPE (ticket 73), never merged: picks the low-class retry rule and counts frames written per
 * kind. Rules: d61 (main), route (a low-class resend goes on an active route, else wide flood),
 * slow (low class uses the high-class retry schedule, 5 s to 60 s), cap3 (at most 3 timed retries in
 * the low class), nolink (no new-link retries in the low class), routeslow (route + slow).
 */
public object ProtoRetry {
    public var rule: String = "d61"
    /** Where counts go; the density test keeps one map per seed thread. */
    public var sink: (String, Int) -> Unit = { _, _ -> }

    public fun count(
        key: String,
        n: Int,
    ) {
        sink(key, n)
    }

    public val route: Boolean get() = rule == "route" || rule == "routeslow"
    public val slow: Boolean get() = rule == "slow" || rule == "routeslow"
    public val cap: Boolean get() = rule == "cap3"
    public val noLink: Boolean get() = rule == "nolink"
}
