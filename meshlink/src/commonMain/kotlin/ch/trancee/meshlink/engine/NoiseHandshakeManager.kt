package ch.trancee.meshlink.engine

import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.crypto.Identity
import ch.trancee.meshlink.crypto.TrustStore
import ch.trancee.meshlink.crypto.noise.NoiseXXInitiator
import ch.trancee.meshlink.crypto.noise.NoiseXXResponder
import ch.trancee.meshlink.transport.Logger
import ch.trancee.meshlink.wire.Handshake

/**
 * Manages concurrent Noise XX handshakes with remote peers.
 *
 * Tracks in-flight handshake states keyed by peer ID (as `List<Byte>` for content-equality
 * semantics, per MEM047/MEM098). Enforces a per-peer rate limit and a global concurrency ceiling.
 *
 * Callbacks [onHandshakeComplete] and [sendHandshake] must be set before calling any public method.
 *
 * **All public methods are NOT thread-safe.** Callers must ensure single-threaded or
 * externally-synchronised access.
 */
internal class NoiseHandshakeManager(
    private val localIdentity: Identity,
    private val cryptoProvider: CryptoProvider,
    private val trustStore: TrustStore,
    private val config: HandshakeConfig,
    private val clock: () -> Long,
    private val diagnosticSink: ch.trancee.meshlink.api.DiagnosticSinkApi =
        ch.trancee.meshlink.api.NoOpDiagnosticSink,
) {
    /**
     * Invoked when a handshake completes and [peerId] is authenticated.
     *
     * Note: signature omits the [ch.trancee.meshlink.crypto.noise.NoiseSession] because the session
     * keys are not consumed by [MeshEngine]'s wiring — only the peer identity matters for
     * connection slot acquisition and routing registration.
     */
    var onHandshakeComplete: ((peerId: ByteArray) -> Unit)? = null

    /** Invoked to dispatch a [Handshake] wire message to [peerId]. */
    var sendHandshake: ((peerId: ByteArray, handshakeMsg: Handshake) -> Unit)? = null

    // Active handshake states keyed by peerId.asList() (MEM047/MEM098 convention).
    private val activeHandshakes = HashMap<List<Byte>, NoiseXXState>()

    // Rate limiter: peerId.asList() → timestamp of last handshake initiation attempt.
    private val lastHandshakeAttempt = HashMap<List<Byte>, Long>()

    /**
     * Per-peer in-flight handshake state.
     *
     * Plain inner classes (not data classes) to avoid the auto-generated `!is` branch in `equals()`
     * that Kover cannot cover via the public API (MEM113/MEM047).
     */
    internal sealed class NoiseXXState {
        class Initiating(val initiator: NoiseXXInitiator, val startedAt: Long) : NoiseXXState()

        class Responding(val responder: NoiseXXResponder, val startedAt: Long) : NoiseXXState()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called when an advertisement is seen from [peerId] carrying [serviceData].
     *
     * - If the peer's key is already pinned in [TrustStore], calls [onHandshakeComplete] directly
     *   (reconnect shortcut — trust already established, no new Noise XX required).
     * - Otherwise initiates a Noise XX handshake subject to rate limiting and concurrency caps.
     */
    fun onAdvertisementSeen(peerId: ByteArray, serviceData: ByteArray) {
        // Reconnect shortcut: key already pinned, skip full handshake.
        if (trustStore.getPinnedKey(peerId) != null) {
            onHandshakeComplete?.invoke(peerId)
            return
        }

        val peerKey = peerId.asList()

        // Rate limit: skip if the last attempt is within the rolling window.
        val lastAttempt = lastHandshakeAttempt[peerKey]
        val now = clock()
        if (lastAttempt != null && now - lastAttempt < config.rateLimitWindowMillis) {
            return
        }

        // Concurrency cap: drop if we are already at the maximum.
        if (activeHandshakes.size >= config.maxConcurrentHandshakes) {
            return
        }

        lastHandshakeAttempt[peerKey] = now

        // Initiate Noise XX: write message 1 and register Initiating state.
        val initiator = NoiseXXInitiator(cryptoProvider, localIdentity.dhKeyPair)
        val msg1 = initiator.writeMessage1()
        activeHandshakes[peerKey] = NoiseXXState.Initiating(initiator, now)
        sendHandshake?.invoke(peerId, Handshake(step = 1u, noiseMessage = msg1))
    }

    /**
     * Dispatches an inbound [handshake] message from [peerId] to the appropriate step handler.
     *
     * - Step 1 (→ e): we are the responder — read msg1, send msg2.
     * - Step 2 (← e, ee, s, es): we are the initiator — read msg2, send msg3, finalize.
     * - Step 3 (→ s, se): we are the responder — read msg3, finalize.
     * - Any other step is silently dropped.
     */
    fun onInboundHandshake(peerId: ByteArray, handshake: Handshake) {
        when (handshake.step.toInt()) {
            1 -> handleStep1(peerId, handshake)
            2 -> handleStep2(peerId, handshake)
            3 -> handleStep3(peerId, handshake)
            else -> return
        }
    }

    // ── Private step handlers ─────────────────────────────────────────────────

    /** Step 1: we are the responder. Read msg1, write msg2, register Responding state. */
    private fun handleStep1(peerId: ByteArray, handshake: Handshake) {
        val peerKey = peerId.asList()
        val now = clock()

        // Reject if at concurrency cap.
        if (activeHandshakes.size >= config.maxConcurrentHandshakes) {
            return
        }

        val responder = NoiseXXResponder(cryptoProvider, localIdentity.dhKeyPair)
        try {
            responder.readMessage1(handshake.noiseMessage)
            val msg2 = responder.writeMessage2()
            activeHandshakes[peerKey] = NoiseXXState.Responding(responder, now)
            sendHandshake?.invoke(peerId, Handshake(step = 2u, noiseMessage = msg2))
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Malformed or unauthenticated message — discard silently.
            activeHandshakes.remove(peerKey)
        }
    }

    /** Step 2: we are the initiator. Read msg2 (`e, ee, s, es`), write msg3 (`s, se`), finalize. */
    private fun handleStep2(peerId: ByteArray, handshake: Handshake) {
        val peerKey = peerId.asList()
        val state = activeHandshakes[peerKey]
        if (state !is NoiseXXState.Initiating) return

        try {
            state.initiator.readMessage2(handshake.noiseMessage)
            val msg3 = state.initiator.writeMessage3()
            val session = state.initiator.finalize()
            activeHandshakes.remove(peerKey)
            trustStore.pinKey(peerId, session.getRemoteStaticKey())
            sendHandshake?.invoke(peerId, Handshake(step = 3u, noiseMessage = msg3))
            val elapsedInitiator = clock() - state.startedAt
            val peerHexI =
                peerId.take(4).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
            Logger.d(
                "MeshLink",
                "Noise XX complete (initiator) peer=$peerHexI... elapsed=${elapsedInitiator}ms",
            )
            onHandshakeComplete?.invoke(peerId)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            activeHandshakes.remove(peerKey)
        }
    }

    /** Step 3: we are the responder. Read msg3 (`s, se`), finalize. */
    private fun handleStep3(peerId: ByteArray, handshake: Handshake) {
        val peerKey = peerId.asList()
        val state = activeHandshakes[peerKey]
        if (state !is NoiseXXState.Responding) return

        try {
            state.responder.readMessage3(handshake.noiseMessage)
            val session = state.responder.finalize()
            activeHandshakes.remove(peerKey)
            trustStore.pinKey(peerId, session.getRemoteStaticKey())
            val elapsedResponder = clock() - state.startedAt
            val peerHexR =
                peerId.take(4).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
            Logger.d(
                "MeshLink",
                "Noise XX complete (responder) peer=$peerHexR... elapsed=${elapsedResponder}ms",
            )
            onHandshakeComplete?.invoke(peerId)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            activeHandshakes.remove(peerKey)
        }
    }
}
