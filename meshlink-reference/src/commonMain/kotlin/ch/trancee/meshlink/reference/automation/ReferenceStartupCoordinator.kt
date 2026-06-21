package ch.trancee.meshlink.reference.automation

import kotlinx.coroutines.CoroutineScope

/**
 * Coordinates early startup for live-proof sessions before the Compose shell is relied on.
 *
 * The coordinator is intentionally small and idempotent: it can be asked to start more than once,
 * but it will only request mesh startup once per activity lifetime.
 */
public class ReferenceStartupCoordinator(private val scope: CoroutineScope) {
    private var liveProofStartupRequested: Boolean = false

    public fun startLiveProofIfNeeded(): Unit {
        if (liveProofStartupRequested) {
            return
        }
        liveProofStartupRequested = true
    }
}
