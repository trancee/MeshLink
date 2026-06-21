package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.platform.PlatformServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * Coordinates early startup for live-proof sessions before the Compose shell is relied on.
 *
 * The coordinator is intentionally small and idempotent: it can be asked to start more than once,
 * but it will only request mesh startup once per activity lifetime.
 */
public class ReferenceStartupCoordinator(
    private val platformServices: PlatformServices,
    private val scope: CoroutineScope,
) {
    private var liveProofStartupRequested: Boolean = false

    public fun startLiveProofIfNeeded(): Unit {
        val config = platformServices.automationConfig ?: return
        if (config.mode != AUTOMATION_MODE_LIVE_PROOF) {
            return
        }
        if (liveProofStartupRequested) {
            return
        }

        liveProofStartupRequested = true
        platformServices.emitAutomationLog(
            "REFERENCE_AUTOMATION startup.coordinator.requested mode=${config.mode} role=${config.role} appId=${config.appId} controller=${platformServices.meshLinkController::class.simpleName.orEmpty()}"
        )
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            platformServices.emitAutomationLog(
                "REFERENCE_AUTOMATION startup.coordinator.dispatch mode=${config.mode} role=${config.role} controller=${platformServices.meshLinkController::class.simpleName.orEmpty()}"
            )
            platformServices.meshLinkController.start()
        }
    }
}
