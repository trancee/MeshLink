package io.meshlink.platform

import io.meshlink.util.currentTimeMillis

/**
 * Handles CoreBluetooth state preservation and restoration for iOS
 * background operation.
 *
 * When the system terminates the app and later re-launches it, CoreBluetooth
 * passes a restoration dictionary to `centralManager(_:willRestoreState:)`.
 * This class extracts the previously-connected peripheral identifiers and
 * marks them for fast-path reconnection.
 *
 * **Security rule:** after restoration we never trust old Noise session state.
 * Every restored peer must complete a fresh Noise XX handshake.
 */
class StatePreservation(
    private val clock: () -> Long = { currentTimeMillis() },
) {
    /**
     * A peer that was connected before the app was terminated.
     *
     * @property peripheralId CoreBluetooth peripheral UUID string.
     * @property needsHandshake Always `true` after restoration — old Noise
     *   session keys are discarded.
     */
    data class RestoredPeer(
        val peripheralId: String,
        val needsHandshake: Boolean = true,
    )

    /** Peers that should be reconnected after restoration. */
    private val restoredPeers = mutableListOf<RestoredPeer>()

    /** Peers we are currently tracking (for [saveState]). */
    private val activePeers = mutableListOf<String>()

    /** Timestamp of the last restoration, or `null` if never restored. */
    var lastRestorationTimestamp: Long? = null
        private set

    /**
     * Called from the `CBCentralManagerDelegate` when CoreBluetooth delivers
     * the restoration dictionary.
     *
     * Extracts peripheral identifiers from the
     * `CBCentralManagerRestoredStatePeripheralsKey` list and records them for
     * fast-path reconnection.
     *
     * @param dict The restoration dictionary provided by CoreBluetooth.
     *   Expected key: `"peripherals"` → `List<String>` of peripheral UUIDs.
     */
    fun willRestoreState(dict: Map<String, Any>) {
        lastRestorationTimestamp = clock()
        restoredPeers.clear()

        @Suppress("UNCHECKED_CAST")
        val peripheralIds = dict["peripherals"] as? List<String> ?: return

        for (id in peripheralIds) {
            restoredPeers.add(RestoredPeer(peripheralId = id, needsHandshake = true))
        }
    }

    /**
     * Returns the list of peers that need reconnection after restoration.
     * Each peer is flagged as requiring a fresh Noise XX handshake.
     */
    fun peersToReconnect(): List<RestoredPeer> = restoredPeers.toList()

    /**
     * Called after a restored peer has been successfully reconnected and
     * the Noise XX handshake has completed.
     */
    fun markReconnected(peripheralId: String) {
        restoredPeers.removeAll { it.peripheralId == peripheralId }
    }

    /** Track a peer as currently active (for future [saveState] calls). */
    fun trackPeer(peripheralId: String) {
        if (peripheralId !in activePeers) {
            activePeers.add(peripheralId)
        }
    }

    /** Stop tracking a peer (disconnected or lost). */
    fun untrackPeer(peripheralId: String) {
        activePeers.remove(peripheralId)
    }

    /**
     * Saves the current peer list for CoreBluetooth state preservation.
     *
     * The returned map should be passed to
     * `CBCentralManager(delegate:queue:options:)` via
     * `CBCentralManagerOptionRestoreIdentifierKey`.
     */
    fun saveState(): Map<String, Any> = mapOf(
        "peripherals" to activePeers.toList(),
    )
}
