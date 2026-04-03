package io.meshlink.crypto

import io.meshlink.diagnostics.DiagnosticSink
import io.meshlink.model.KeyChangeEvent
import io.meshlink.util.ByteArrayKey
import io.meshlink.util.toKey
import io.meshlink.util.zeroize
import io.meshlink.wire.RotationAnnouncement

/**
 * Consolidates security operations: E2E encryption/decryption, signature
 * signing/verification, Noise XX handshake management, and peer key lifecycle.
 *
 * Hides: per-peer key maps, NoiseKSealer mechanics, handshake state machines,
 * and the "try unseal, fallback to plaintext" pattern.
 */
class SecurityEngine(
    private val crypto: CryptoProvider,
    private val handshakePayload: ByteArray = byteArrayOf(),
    private val clock: () -> Long = { 0L },
    private val rotationFreshnessWindowMillis: Long = 30_000L,
    diagnosticSink: DiagnosticSink? = null,
    redactPeerIds: Boolean = false,
) {
    private var localKeyPair: CryptoKeyPair = crypto.generateX25519KeyPair()
    private var broadcastKeyPair: CryptoKeyPair = crypto.generateEd25519KeyPair()
    private var sealer: NoiseKSealer = NoiseKSealer(crypto)

    private val peerPublicKeys = mutableMapOf<ByteArrayKey, ByteArray>()
    private val sessionSecrets = mutableMapOf<ByteArrayKey, ByteArray>()
    private val lastRotationTimestampMillis = mutableMapOf<ByteArrayKey, ULong>()

    private val handshakeManager = PeerHandshakeManager(
        crypto,
        localKeyPair,
        localPayload = handshakePayload,
        diagnosticSink = diagnosticSink,
        redactPeerIds = redactPeerIds,
    )

    // --- Public key accessors ---

    val localPublicKey: ByteArray get() = localKeyPair.publicKey
    val localBroadcastPublicKey: ByteArray get() = broadcastKeyPair.publicKey

    fun peerPublicKey(peerId: ByteArrayKey): ByteArray? = peerPublicKeys[peerId]

    // --- E2E encryption ---

    fun seal(recipientId: ByteArrayKey, payload: ByteArray): SealResult {
        val recipientKey = peerPublicKeys[recipientId]
            ?: return SealResult.UnknownRecipient
        val sessionSecret = sessionSecrets[recipientId]
        return SealResult.Sealed(sealer.seal(recipientKey, payload, sessionSecret))
    }

    fun unseal(payload: ByteArray, senderPeerId: ByteArrayKey? = null): UnsealResult {
        if (payload.size < 48) return UnsealResult.TooShort(payload)
        // Try with session secret first (if sender is known and has a session)
        val sessionSecret = senderPeerId?.let { sessionSecrets[it] }
        if (sessionSecret != null) {
            try {
                return UnsealResult.Decrypted(
                    sealer.unseal(localKeyPair.privateKey, payload, sessionSecret)
                )
            } catch (_: Exception) {
                // Session key mismatch — fall through to try without
            }
        }
        // Fallback: try without session secret (pure Noise K)
        return try {
            UnsealResult.Decrypted(sealer.unseal(localKeyPair.privateKey, payload))
        } catch (_: Exception) {
            UnsealResult.Failed(payload)
        }
    }

    // --- Signatures ---

    fun sign(data: ByteArray): SignedPayload {
        val signature = crypto.sign(broadcastKeyPair.privateKey, data)
        return SignedPayload(signature, broadcastKeyPair.publicKey)
    }

    fun verify(signerPublicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        crypto.verify(signerPublicKey, data, signature)

    // --- Handshake ---

    fun handleHandshakeMessage(fromPeerId: ByteArray, wireData: ByteArray): ByteArray? {
        val response = handshakeManager.handleIncoming(fromPeerId, wireData)
        registerCompletedHandshakeKey(fromPeerId)
        return response
    }

    fun initiateHandshake(peerId: ByteArray): ByteArray? {
        val response = handshakeManager.initiateHandshake(peerId, handshakePayload)
        registerCompletedHandshakeKey(peerId)
        return response
    }

    private fun registerCompletedHandshakeKey(peerId: ByteArray) {
        val key = peerId.toKey()
        if (peerPublicKeys.containsKey(key)) return
        val result = handshakeManager.getSessionKeys(peerId) ?: return
        peerPublicKeys[key] = result.remoteStaticKey
        sessionSecrets[key] = result.sessionSecret
    }

    fun isHandshakeComplete(peerId: ByteArray): Boolean =
        handshakeManager.isComplete(peerId)

    // --- Replay protection ---
    // Note: Replay guards are independent of crypto (pure counter-based) and remain
    // in MeshLink. SecurityEngine does not own replay protection.

    // --- Peer key management ---

    fun registerPeerKey(peerId: ByteArrayKey, publicKey: ByteArray): KeyRegistrationResult {
        val previousKey = peerPublicKeys[peerId]
        if (previousKey != null && previousKey.contentEquals(publicKey)) {
            return KeyRegistrationResult.Unchanged
        }
        peerPublicKeys[peerId] = publicKey
        return if (previousKey != null) {
            KeyRegistrationResult.Changed(previousKey)
        } else {
            KeyRegistrationResult.New
        }
    }

    fun handleRotationAnnouncement(
        fromPeerId: ByteArrayKey,
        announcement: RotationAnnouncement.RotationMessage,
    ): RotationResult {
        val signablePayload = RotationAnnouncement.buildSignablePayload(
            announcement.oldX25519Key,
            announcement.newX25519Key,
            announcement.oldEd25519Key,
            announcement.newEd25519Key,
            announcement.timestampMillis,
        )
        if (!crypto.verify(announcement.oldEd25519Key, signablePayload, announcement.signature)) {
            return RotationResult.Rejected
        }

        // Timestamp freshness: reject if outside ±window of current time
        val now = clock().toULong()
        val ts = announcement.timestampMillis
        if (now > ts && (now - ts) > rotationFreshnessWindowMillis.toULong()) {
            return RotationResult.Stale
        }
        if (ts > now && (ts - now) > rotationFreshnessWindowMillis.toULong()) {
            return RotationResult.Stale
        }

        // Reject if timestamp is not newer than the last seen rotation from this peer
        val lastTs = lastRotationTimestampMillis[fromPeerId]
        if (lastTs != null && ts <= lastTs) {
            return RotationResult.Stale
        }

        val knownKey = peerPublicKeys[fromPeerId]
            ?: return RotationResult.UnknownPeer
        if (!knownKey.contentEquals(announcement.oldX25519Key)) {
            return RotationResult.Rejected
        }
        peerPublicKeys[fromPeerId] = announcement.newX25519Key
        lastRotationTimestampMillis[fromPeerId] = ts
        return RotationResult.Accepted(
            KeyChangeEvent(
                peerId = fromPeerId.bytes,
                previousKey = announcement.oldX25519Key.copyOf(),
                newKey = announcement.newX25519Key.copyOf(),
            )
        )
    }

    // --- Identity rotation ---

    fun rotateIdentity() {
        // Security: zeroize old key material before replacing.
        // Reduces the window for memory-dump attacks.
        zeroize(localKeyPair.privateKey)
        zeroize(broadcastKeyPair.privateKey)
        localKeyPair = crypto.generateX25519KeyPair()
        sealer = NoiseKSealer(crypto)
        broadcastKeyPair = crypto.generateEd25519KeyPair()
        // Clear stale session state from old identity
        clear()
    }

    // --- State management ---

    fun clear() {
        // Security: zeroize session secrets before removing references.
        // Prevents old secrets from lingering in heap memory.
        sessionSecrets.values.forEach { zeroize(it) }
        peerPublicKeys.clear()
        sessionSecrets.clear()
    }
}

// --- Sealed result types ---

sealed interface SealResult {
    data class Sealed(val ciphertext: ByteArray) : SealResult
    data object UnknownRecipient : SealResult
}

sealed interface UnsealResult {
    data class Decrypted(val plaintext: ByteArray) : UnsealResult
    data class Failed(val originalPayload: ByteArray) : UnsealResult
    data class TooShort(val originalPayload: ByteArray) : UnsealResult
}

data class SignedPayload(
    val signature: ByteArray,
    val signerPublicKey: ByteArray,
)

sealed interface KeyRegistrationResult {
    data object New : KeyRegistrationResult
    data class Changed(val previousKey: ByteArray) : KeyRegistrationResult
    data object Unchanged : KeyRegistrationResult
}

sealed interface RotationResult {
    data class Accepted(val event: KeyChangeEvent) : RotationResult
    data object Rejected : RotationResult
    data object Stale : RotationResult
    data object UnknownPeer : RotationResult
}
