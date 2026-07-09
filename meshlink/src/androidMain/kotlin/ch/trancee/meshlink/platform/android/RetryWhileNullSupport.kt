package ch.trancee.meshlink.platform.android

import kotlinx.coroutines.delay

/**
 * Repeatedly invokes [attemptOperation] until it returns a non-null result or [maxAttempts] have
 * been made, waiting [delayMillis] between attempts. Used for platform calls that are known to
 * transiently return null right after adapter/service state changes (e.g.
 * `BluetoothManager.openGattServer()` returning null immediately after a fresh app install or
 * Bluetooth adapter state change) rather than failing permanently.
 *
 * [onRetry] is invoked once per failed-but-not-yet-exhausted attempt (with the 1-based attempt
 * number that just failed) so callers can log a distinct, diagnosable line per retry without this
 * helper taking a required logging dependency.
 *
 * Returns null if every attempt returned null.
 */
// detekt's RedundantSuspendModifier check cannot prove delay() below is reached on every path
// (it is only called on a failed-but-not-yet-exhausted attempt), but this function is genuinely
// suspending on the real retry path and must stay suspend to be callable from GattNotifyServer's
// suspend start().
@Suppress("RedundantSuspendModifier")
internal suspend fun <T : Any> retryWhileNull(
    maxAttempts: Int,
    delayMillis: Long,
    onRetry: (attempt: Int) -> Unit = {},
    attemptOperation: () -> T?,
): T? {
    require(maxAttempts >= 1) { "maxAttempts must be at least 1, was $maxAttempts" }
    var result: T? = null
    var attempt = 0
    while (result == null && attempt < maxAttempts) {
        attempt += 1
        result = attemptOperation()
        if (result == null && attempt < maxAttempts) {
            onRetry(attempt)
            delay(delayMillis)
        }
    }
    return result
}
