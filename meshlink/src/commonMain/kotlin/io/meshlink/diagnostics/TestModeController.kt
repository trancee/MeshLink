package io.meshlink.diagnostics

/**
 * Programmatic test mode interface for automated testing and diagnostics.
 * Provides controlled triggers for mesh behaviors that are normally async.
 *
 * IMPORTANT: This should only be used in test/debug builds.
 * Production builds should not expose this interface.
 */
class TestModeController(
    private val enabled: Boolean = false,
) {

    data class MeshSnapshot(
        val peerCount: Int,
        val routeCount: Int,
        val activeTransfers: Int,
        val pendingMessages: Int,
        val reassemblyBuffers: Int,
        val tombstoneCount: Int,
        val diagnosticEventCount: Int,
        val powerMode: String,
        val started: Boolean,
        val paused: Boolean,
    )

    sealed class TestAction {
        /** Force an immediate gossip round */
        data object ForceGossip : TestAction()

        /** Simulate a peer disconnection */
        data class SimulatePeerLoss(val peerIdHex: String) : TestAction()

        /** Inject a synthetic diagnostic event */
        data class InjectDiagnostic(val code: DiagnosticCode, val severity: Severity, val payload: String?) : TestAction()

        /** Force memory pressure at a given level (0-100) */
        data class ForceMemoryPressure(val level: Int) : TestAction()

        /** Request a snapshot of internal state */
        data object RequestSnapshot : TestAction()

        /** Clear all internal state (routing, transfers, etc.) */
        data object ClearState : TestAction()

        /** Force identity key rotation */
        data object ForceRotation : TestAction()
    }

    private val actionQueue = mutableListOf<TestAction>()
    private var lastSnapshot: MeshSnapshot? = null

    /**
     * Enqueue a test action. Returns false if test mode is disabled.
     */
    fun enqueue(action: TestAction): Boolean {
        if (!enabled) return false
        actionQueue.add(action)
        return true
    }

    /**
     * Drain all pending actions. Returns the list and clears the queue.
     */
    fun drainActions(): List<TestAction> {
        val drained = actionQueue.toList()
        actionQueue.clear()
        return drained
    }

    /**
     * Whether test mode is enabled.
     */
    fun isEnabled(): Boolean = enabled

    /**
     * Store a snapshot (called by MeshLink when RequestSnapshot is processed).
     */
    fun storeSnapshot(snapshot: MeshSnapshot) {
        lastSnapshot = snapshot
    }

    /**
     * Get the last stored snapshot.
     */
    fun lastSnapshot(): MeshSnapshot? = lastSnapshot

    /**
     * Number of pending actions.
     */
    fun pendingCount(): Int = actionQueue.size

    /**
     * Clear the action queue.
     */
    fun clearQueue() {
        actionQueue.clear()
    }
}
