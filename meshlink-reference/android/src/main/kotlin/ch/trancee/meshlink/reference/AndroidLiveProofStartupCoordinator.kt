package ch.trancee.meshlink.reference

import android.util.Log
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * Android-local coordinator for the live-proof startup request.
 *
 * This keeps the launch path on Android primitives instead of the shared automation contract,
 * which avoids loading shared automation config types during app startup.
 */
internal class AndroidLiveProofStartupCoordinator(
    private val meshLinkController: ReferenceMeshLinkController,
    private val scope: CoroutineScope,
    private val mode: String,
    private val role: String,
    private val appId: String,
) {
    private var liveProofStartupRequested: Boolean = false

    fun startIfNeeded(): Unit {
        if (mode != MainActivity.AUTOMATION_MODE_LIVE_PROOF) {
            return
        }
        if (liveProofStartupRequested) {
            return
        }

        liveProofStartupRequested = true
        Log.i(
            "MeshLinkReferenceAutomation",
            "REFERENCE_AUTOMATION startup.coordinator.requested mode=$mode role=$role appId=$appId",
        )
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            Log.i(
                "MeshLinkReferenceAutomation",
                "REFERENCE_AUTOMATION startup.coordinator.dispatch mode=$mode role=$role",
            )
            meshLinkController.start()
        }
    }
}
