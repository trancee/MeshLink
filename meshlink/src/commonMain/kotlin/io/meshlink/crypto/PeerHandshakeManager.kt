package io.meshlink.crypto

import io.meshlink.util.toHex
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
) {
    // peerId hex → handshake state machine
    private val handshakes = mutableMapOf<String, NoiseXXHandshake>()

    // peerId hex → completed session keys
    private val sessionKeys = mutableMapOf<String, NoiseXXHandshake.HandshakeResult>()

    /**
     * Initiate a handshake with the given peer (we are the initiator).
     * Returns the first handshake message (msg1) to send.
     */
    fun initiateHandshake(peerId: ByteArray, payload: ByteArray = byteArrayOf()): ByteArray? {
        val key = peerId.toHex()
        if (sessionKeys.containsKey(key)) return null // already complete

        val hs = NoiseXXHandshake.initiator(crypto, localStaticKeyPair)
        handshakes[key] = hs
        val msg1 = hs.writeMessage(payload)
        return WireCodec.encodeHandshake(step = 0u, noiseMessage = msg1)
    }

    /**
     * Handle an incoming handshake message from a peer.
     * Returns the response message to send back, or null if handshake is complete.
     */
    fun handleIncoming(fromPeerId: ByteArray, wireData: ByteArray): ByteArray? {
        val key = fromPeerId.toHex()
        val hsMsg = WireCodec.decodeHandshake(wireData)

        val hs = handshakes.getOrPut(key) {
            // First message from this peer — we're the responder
            NoiseXXHandshake.responder(crypto, localStaticKeyPair)
        }

        // Read the incoming message
        hs.readMessage(hsMsg.noiseMessage)

        if (hs.isComplete) {
            sessionKeys[key] = hs.finalize()
            handshakes.remove(key)
            return null
        }

        // Write our response (responder sends localPayload in msg2, initiator in msg3)
        val response = hs.writeMessage(localPayload)
        val responseStep = hsMsg.step.toInt() + 1

        if (hs.isComplete) {
            sessionKeys[key] = hs.finalize()
            handshakes.remove(key)
        }

        return WireCodec.encodeHandshake(step = responseStep.toUByte(), noiseMessage = response)
    }

    /** Whether the handshake with this peer is complete. */
    fun isComplete(peerId: ByteArray): Boolean = peerId.toHex() in sessionKeys

    /** Get session keys for a peer, or null if handshake not complete. */
    fun getSessionKeys(peerId: ByteArray): NoiseXXHandshake.HandshakeResult? =
        sessionKeys[peerId.toHex()]

    /** Get the peer's handshake payload, or null if handshake not complete. */
    fun getPeerPayload(peerId: ByteArray): ByteArray? =
        sessionKeys[peerId.toHex()]?.peerPayload
}
