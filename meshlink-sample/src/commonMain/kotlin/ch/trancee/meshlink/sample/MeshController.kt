package ch.trancee.meshlink.sample

import ch.trancee.meshlink.api.DiagnosticEvent
import ch.trancee.meshlink.api.MeshHealthSnapshot
import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.api.MeshLinkConfig
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.MessagePriority
import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.api.ReceivedMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Compose-friendly controller that wraps [MeshLinkApi] and exposes all observable state as
 * [StateFlow]s ready for direct collection in Composables.
 *
 * An instance is created once per app composition and lives for the duration of the
 * [CoroutineScope] provided at construction time. The scope is typically
 * `rememberCoroutineScope()` from the root [App] composable, so all collectors are cancelled
 * automatically when the composition leaves the tree.
 *
 * @param meshLink Platform-specific [MeshLinkApi] instance, created by [createPlatformMeshLink].
 *   On Android this is backed by [ch.trancee.meshlink.transport.AndroidBleTransport]; on iOS by
 *   [ch.trancee.meshlink.transport.IosBleTransport].
 * @param config The [MeshLinkConfig] that was used to create [meshLink]. Exposed so UI screens
 *   can display configuration values without needing access to private MeshLink internals.
 * @param scope Lifecycle-scoped [CoroutineScope] used to collect MeshLink event flows.
 */
class MeshController(
    /** Underlying MeshLink API instance. Exposed for advanced consumers; prefer the typed flows. */
    val meshLink: MeshLinkApi,
    /**
     * The [MeshLinkConfig] used to construct this controller's [MeshLinkApi] instance. Exposed so
     * UI screens can display configuration values (appId, maxMessageSize, trustMode) without
     * needing access to private MeshLink internals.
     */
    val config: MeshLinkConfig,
    private val scope: CoroutineScope,
) {

    // ── Lifecycle state ────────────────────────────────────────────────────

    /** Current [MeshLinkState] as a [StateFlow]. Always reflects the latest engine state. */
    val state: StateFlow<MeshLinkState> = meshLink.state

    // ── Message flow ───────────────────────────────────────────────────────

    private val _messages = MutableStateFlow<List<ReceivedMessage>>(emptyList())

    /**
     * Ordered list of all [ReceivedMessage]s received since the engine last started.
     * New messages are appended; the list is capped at [MAX_MESSAGES] entries.
     */
    val messages: StateFlow<List<ReceivedMessage>> = _messages.asStateFlow()

    // ── Peer flow ──────────────────────────────────────────────────────────

    private val _peers = MutableStateFlow<List<PeerEvent>>(emptyList())

    /**
     * Running log of [PeerEvent]s ([PeerEvent.Found] and [PeerEvent.Lost]) since the engine
     * last started.
     */
    val peers: StateFlow<List<PeerEvent>> = _peers.asStateFlow()

    // ── Diagnostic events ──────────────────────────────────────────────────

    private val _diagnosticEvents = MutableStateFlow<List<DiagnosticEvent>>(emptyList())

    /**
     * Ring-buffer of the most recent [DiagnosticEvent]s, capped at [MAX_DIAGNOSTIC_EVENTS].
     * Newest events are at the tail of the list.
     */
    val diagnosticEvents: StateFlow<List<DiagnosticEvent>> = _diagnosticEvents.asStateFlow()

    // ── Health snapshot ────────────────────────────────────────────────────

    private val _healthSnapshot = MutableStateFlow<MeshHealthSnapshot?>(null)

    /** Most recent [MeshHealthSnapshot] emitted by the engine, or `null` before the first tick. */
    val healthSnapshot: StateFlow<MeshHealthSnapshot?> = _healthSnapshot.asStateFlow()

    // ── Init ───────────────────────────────────────────────────────────────

    init {
        // Collect incoming messages and append, capping at MAX_MESSAGES.
        scope.launch {
            meshLink.messages.collect { msg ->
                _messages.update { existing ->
                    (existing + msg).takeLast(MAX_MESSAGES)
                }
            }
        }

        // Collect peer events (Found / Lost) — keep a running log.
        scope.launch {
            meshLink.peers.collect { event ->
                _peers.update { existing ->
                    (existing + event).takeLast(MAX_PEER_EVENTS)
                }
            }
        }

        // Collect diagnostic events — ring-buffer of last MAX_DIAGNOSTIC_EVENTS entries.
        scope.launch {
            meshLink.diagnosticEvents.collect { event ->
                _diagnosticEvents.update { existing ->
                    (existing + event).takeLast(MAX_DIAGNOSTIC_EVENTS)
                }
            }
        }

        // Collect health snapshots — keep only the latest.
        scope.launch {
            meshLink.meshHealthFlow.collect { snapshot ->
                _healthSnapshot.value = snapshot
            }
        }
    }

    // ── Convenience lifecycle wrappers ─────────────────────────────────────

    /** Starts the MeshLink engine (UNINITIALIZED / RECOVERABLE → RUNNING). */
    suspend fun start() = meshLink.start()

    /** Stops the MeshLink engine (RUNNING / PAUSED / RECOVERABLE → STOPPED). */
    suspend fun stop() = meshLink.stop()

    /** Pauses BLE advertising/scanning (RUNNING → PAUSED). */
    suspend fun pause() = meshLink.pause()

    /** Resumes BLE advertising/scanning (PAUSED → RUNNING). */
    suspend fun resume() = meshLink.resume()

    // ── Send helper ────────────────────────────────────────────────────────

    /**
     * UTF-8 encodes [text] and sends it as a unicast message to [recipient].
     *
     * @param recipient Key hash (12 bytes) of the target peer.
     * @param text Human-readable message text.
     */
    suspend fun send(recipient: ByteArray, text: String) {
        meshLink.send(recipient = recipient, payload = text.encodeToByteArray())
    }

    /**
     * UTF-8 encodes [text] and broadcasts it to all reachable peers.
     *
     * Uses [MeshLinkConfig.RoutingConfig.maxHops] as the hop limit so the broadcast
     * respects the app's configured mesh reach.
     *
     * @param text Human-readable message text.
     * @param priority Delivery priority. Defaults to [MessagePriority.NORMAL].
     */
    suspend fun broadcast(
        text: String,
        priority: MessagePriority = MessagePriority.NORMAL,
    ) {
        meshLink.broadcast(
            payload = text.encodeToByteArray(),
            maxHops = config.routing.maxHops,
            priority = priority,
        )
    }

    // ── Constants ──────────────────────────────────────────────────────────

    companion object {
        /** Maximum number of received messages retained in memory. */
        const val MAX_MESSAGES = 200

        /** Maximum number of peer events retained in memory. */
        const val MAX_PEER_EVENTS = 100

        /** Maximum number of diagnostic events retained in the UI ring-buffer. */
        const val MAX_DIAGNOSTIC_EVENTS = 100
    }
}
