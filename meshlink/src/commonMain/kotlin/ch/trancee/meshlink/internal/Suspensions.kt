package ch.trancee.meshlink.internal

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** How often the check runs, in ms. */
private const val CHECK_MILLIS = 10_000L

/** The shortest lateness reported as a suspension, in ms. */
private const val SUSPENDED_MILLIS = 30_000L

/**
 * The app's suspensions (ADR 0020, `ML8002`): a check every 10 s, measured on [continuousMillis], a
 * clock that counts the time the app and the device slept. A check that comes [SUSPENDED_MILLIS] or
 * more late emits its lateness, a lower bound of the suspension, since a suspended app runs no timer.
 */
internal fun suspensions(continuousMillis: () -> Long): Flow<Long> =
    flow {
        var last = continuousMillis()
        while (true) {
            delay(CHECK_MILLIS)
            val now = continuousMillis()
            val late = now - last - CHECK_MILLIS
            if (late >= SUSPENDED_MILLIS) emit(late)
            last = now
        }
    }
