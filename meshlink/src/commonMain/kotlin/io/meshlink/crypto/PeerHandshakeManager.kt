package io.meshlink.crypto

import io.meshlink.diagnostics.DiagnosticCode
import io.meshlink.diagnostics.DiagnosticSink
import io.meshlink.diagnostics.Severity
import io.meshlink.util.ByteArrayKey
import io.meshlink.util.diagnosticPeerId
import io.meshlink.util.toKey
import io.meshlink.wire.WireCodec

/**
 * Manages Noise XX handshakes for multiple peers.
 * Each peer connection gets its own handshake state machine.
 *
 * Messages are raw Noise XX bytes — callers are responsible for
 * wrapping/unwrapping with [WireCodec.encodeHandshake]/[WireCodec.decodeHandshake].
 */
class PeerHandshakeManager(
    private val crypto: CryptoProvider,
    private val localStaticKeyPair: CryptoKeyPair,
    private val localPayload: ByteArray = byteArrayOf(),
    private val diagnosticSink: DiagnosticSink? = null,
    /** When true, peer IDs in diagnostics are hashed to prevent traffic analysis. */
    private val redactPeerIds: Boolean = false,
) {
    // peerId → handshake state machine
    private val handshakes = mutableMapOf<ByteArrayKey, NoiseXXHandshake>()

    // peerId → completed session keys
    private val sessionKeys = mutableMapOf<ByteArrayKey, NoiseXXHandshake.HandshakeResult>()

    /**
     * Initiate a handshake with the given peer (we are the initiator).
     * Returns the first handshake message (msg1) to send.
     */
    fun initiateHandshake(peerId: ByteArray, payload: ByteArray = byteArrayOf()): ByteArray? {
        val key = peerId.toKey()
        if (sessionKeys.containsKey(key)) return null // already complete

        val peerLabel = diagnosticPeerId(peerId, redactPeerIds)
        diagnosticSink?.emit(
            DiagnosticCode.HANDSHAKE_EVENT,
            Severity.INFO,
            "initiating Noise XX handshake (role=initiator, peer=$peerLabel…)",
        )
        val hs = NoiseXXHandshake.initiator(crypto, localStaticKeyPair)
        handshakes[key] = hs
        val msg1 = hs.writeMessage(payload)
        diagnosticSink?.emit(
            DiagnosticCode.HANDSHAKE_EVENT,
            Severity.INFO,
            "→ msg1 sent (step=0, ${msg1.size}B ephemeral key, peer=$peerLabel…)",
        )
        return WireCodec.encodeHandshake(step = 0u, noiseMessage = msg1)
    }

    /**
     * Handle an incoming handshake message from a peer.
     * Returns the response message to send back, or null if handshake is complete.
     */
    fun handleIncoming(fromPeerId: ByteArray, wireData: ByteArray): ByteArray? {
        val key = fromPeerId.toKey()
        val peerLabel = diagnosticPeerId(fromPeerId, redactPeerIds)
        val hsMsg = WireCodec.decodeHandshake(wireData)

        // If we receive a step-0 message but already have an in-progress initiator
        // handshake for this peer, both sides raced to initiate. Reset to responder
        // to resolve the collision.
        if (hsMsg.step.toInt() == 0 && key in handshakes) {
            diagnosticSink?.emit(
                DiagnosticCode.HANDSHAKE_EVENT,
                Severity.INFO,
                "⚠ handshake collision detected — resetting to responder (peer=$peerLabel…)",
            )
            handshakes[key] = NoiseXXHandshake.responder(crypto, localStaticKeyPair)
        }

        val isNewResponder = key !in handshakes

        val hs = handshakes.getOrPut(key) {
            NoiseXXHandshake.responder(crypto, localStaticKeyPair)
        }

        val role = if (isNewResponder && hsMsg.step.toInt() == 0) "responder" else "initiator"
        diagnosticSink?.emit(
            DiagnosticCode.HANDSHAKE_EVENT,
            Severity.INFO,
            "← msg received (step=${hsMsg.step}, ${hsMsg.noiseMessage.size}B, role=$role, peer=$peerLabel…)",
        )

        // Read the incoming message
        hs.readMessage(hsMsg.noiseMessage)

        if (hs.isComplete) {
            sessionKeys[key] = hs.finalize()
            handshakes.remove(key)
            diagnosticSink?.emit(
                DiagnosticCode.HANDSHAKE_EVENT,
                Severity.INFO,
                "✅ handshake complete (role=$role, peer=$peerLabel…) — session keys derived",
            )
            return null
        }

        // Write our response (responder sends localPayload in msg2, initiator in msg3)
        val response = hs.writeMessage(localPayload)
        val responseStep = hsMsg.step.toInt() + 1

        diagnosticSink?.emit(
            DiagnosticCode.HANDSHAKE_EVENT,
            Severity.INFO,
            "→ msg sent (step=$responseStep, ${response.size}B, role=$role, peer=$peerLabel…)",
        )

        if (hs.isComplete) {
            sessionKeys[key] = hs.finalize()
            handshakes.remove(key)
            diagnosticSink?.emit(
                DiagnosticCode.HANDSHAKE_EVENT,
                Severity.INFO,
                "✅ handshake complete after response (role=$role, peer=$peerLabel…) — session keys derived",
            )
        }

        return WireCodec.encodeHandshake(step = responseStep.toUByte(), noiseMessage = response)
    }

    /** Whether the handshake with this peer is complete. */
    fun isComplete(peerId: ByteArray): Boolean = peerId.toKey() in sessionKeys

    /** Get session keys for a peer, or null if handshake not complete. */
    fun getSessionKeys(peerId: ByteArray): NoiseXXHandshake.HandshakeResult? =
        sessionKeys[peerId.toKey()]

    /** Get the peer's handshake payload, or null if handshake not complete. */
    fun getPeerPayload(peerId: ByteArray): ByteArray? =
        sessionKeys[peerId.toKey()]?.peerPayload
}
