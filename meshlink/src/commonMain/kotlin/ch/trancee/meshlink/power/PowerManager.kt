package ch.trancee.meshlink.power

import ch.trancee.meshlink.transfer.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * PowerManager is the public facade that wires together the power management subsystem:
 * - PowerModeEngine: battery-level → tier state machine with hysteresis
 * - ConnectionLimiter: per-peer slot tracking with configurable max
 * - TieredShedder: priority-aware eviction decisions
 * - GracefulDrainManager: grace-period eviction coordination
 *
 * Consumers subscribe to [tierChanges] (for scan/ad parameter updates) and [evictionRequests] (to
 * disconnect peers that were evicted).
 */
class PowerManager(
    private val scope: CoroutineScope,
    batteryMonitor: BatteryMonitor,
    clock: () -> Long,
    private val config: PowerConfig = PowerConfig(),
) {
    private val powerModeEngine = PowerModeEngine(scope, batteryMonitor, clock, config)
    private val connectionLimiter =
        ConnectionLimiter(
            maxConnections = PowerProfile.forTier(PowerTier.PERFORMANCE, config).maxConnections
        )
    private val tieredShedder = TieredShedder()
    private val gracefulDrainManager = GracefulDrainManager(clock, config, scope)

    private val _evictionRequests = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    /**
     * Emits a peerId every time a connection is evicted to make room for a higher-priority peer.
     */
    val evictionRequests: SharedFlow<ByteArray> = _evictionRequests.asSharedFlow()

    /** Re-emits tier transitions from PowerModeEngine; side-effects update ConnectionLimiter. */
    val tierChanges: SharedFlow<PowerTier> = powerModeEngine.tierChanges.asSharedFlow()

    /** The current power tier for direct inspection. */
    val currentTier: PowerTier
        get() = powerModeEngine.currentTier

    /** Returns the PowerProfile parameters for the current tier. */
    fun profile(): PowerProfile = PowerProfile.forTier(currentTier, config)

    /** Overrides battery-based tier selection with a fixed tier. Null restores automatic mode. */
    fun setCustomMode(tier: PowerTier?) {
        powerModeEngine.setCustomMode(tier)
    }

    /**
     * Attempts to establish a connection for [peerId] at the given [priority]. Returns true if the
     * slot was acquired, false if rejected.
     *
     * Decision flow:
     * - TieredShedder.evaluate() → Accept → tryAcquire slot
     * - TieredShedder.evaluate() → EvictAndAccept → emit eviction, release old, acquire new
     * - TieredShedder.evaluate() → Reject → return false
     */
    fun tryAcquireConnection(peerId: ByteArray, priority: Priority): Boolean {
        val maxConnections = PowerProfile.forTier(currentTier, config).maxConnections
        val decision =
            tieredShedder.evaluate(
                newPeerId = peerId,
                newPriority = priority,
                currentConnections = connectionLimiter.currentConnections(),
                maxConnections = maxConnections,
                minThroughputBytesPerSec = config.minThroughputBytesPerSec,
            )
        if (decision is EvictionDecision.Accept) {
            return connectionLimiter.tryAcquire(peerId, priority)
        }
        if (decision is EvictionDecision.EvictAndAccept) {
            _evictionRequests.tryEmit(decision.evictPeerId)
            connectionLimiter.release(decision.evictPeerId)
            return connectionLimiter.tryAcquire(peerId, priority)
        }
        return false
    }

    /** Releases the connection slot for [peerId]. No-op if not currently tracked. */
    fun releaseConnection(peerId: ByteArray) {
        connectionLimiter.release(peerId)
    }

    /**
     * Updates the transfer status for an active connection. Pass null to mark the connection as
     * idle.
     */
    fun updateConnectionStatus(peerId: ByteArray, status: TransferStatus?) {
        connectionLimiter.updateTransferStatus(peerId, status)
    }

    /** Cancels all grace-period timers and immediately evicts all in-grace peers. */
    fun cancelAllGrace() {
        gracefulDrainManager.cancelAllGrace()
    }

    /**
     * Called when the first connection is successfully established. Cancels the bootstrap timer and
     * re-evaluates the battery level for the real tier.
     */
    fun onFirstConnectionEstablished() {
        val job = bootstrapJob
        if (job != null) {
            job.cancel()
            bootstrapJob = null
        }
        powerModeEngine.onBootstrapEnd()
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    private var bootstrapJob: Job? = null

    init {
        // Start the battery-polling loop inside PowerModeEngine.
        powerModeEngine.start()

        // Bootstrap timer: auto-end bootstrap if no connection is established within the window.
        bootstrapJob = scope.launch {
            delay(config.bootstrapDurationMs)
            bootstrapJob = null
            powerModeEngine.onBootstrapEnd()
        }

        // React to tier changes: update max connections and drain excess connections.
        scope.launch {
            powerModeEngine.tierChanges.collect { newTier ->
                val newMax = PowerProfile.forTier(newTier, config).maxConnections
                connectionLimiter.updateMaxConnections(newMax)
                val current = connectionLimiter.connectionCount()
                val excess = current - newMax
                if (excess > 0) {
                    val toDrain =
                        connectionLimiter
                            .currentConnections()
                            .sortedBy { it.priority.ordinal }
                            .take(excess)
                    gracefulDrainManager.drain(toDrain) { evictedPeerId ->
                        connectionLimiter.release(evictedPeerId)
                        _evictionRequests.tryEmit(evictedPeerId)
                    }
                }
            }
        }
    }
}
