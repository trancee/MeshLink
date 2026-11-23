package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus

/** Whether this status is final: `Delivered`, `Read`, `Expired` or `Failed` (ADR 0013 decision 6). */
internal val DeliveryStatus.terminal: Boolean
    get() =
        this is DeliveryStatus.Delivered ||
            this is DeliveryStatus.Read ||
            this is DeliveryStatus.Expired ||
            this is DeliveryStatus.Failed

/**
 * The status after this one once [next] is reported (ADR 0013 decisions 6 and 10): a final status never
 * changes, except that `Delivered` becomes `Read`, and `Queued` is never re-entered; `Sent` and `Held`
 * may alternate.
 */
internal fun DeliveryStatus.then(next: DeliveryStatus): DeliveryStatus {
    val read = this == DeliveryStatus.Delivered && next == DeliveryStatus.Read
    val kept = (terminal && !read) || next == DeliveryStatus.Queued
    return if (kept) this else next
}
