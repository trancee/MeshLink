package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus

/** Whether this status is final: `Delivered`, `Expired` or `Failed` (ADR 0013 decision 6). */
internal val DeliveryStatus.terminal: Boolean
    get() = this is DeliveryStatus.Delivered || this is DeliveryStatus.Expired || this is DeliveryStatus.Failed

/**
 * The status after this one once [next] is reported (ADR 0013 decision 6): a final status never
 * changes and `Queued` is never re-entered; `Sent` and `Held` may alternate.
 */
internal fun DeliveryStatus.then(next: DeliveryStatus): DeliveryStatus {
    val kept = terminal || next == DeliveryStatus.Queued
    return if (kept) this else next
}
