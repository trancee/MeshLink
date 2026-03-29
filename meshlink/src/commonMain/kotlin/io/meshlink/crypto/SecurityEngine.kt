package io.meshlink.crypto

import io.meshlink.model.KeyChangeEvent
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
    private val rotationFreshnessWindowMs: Long = 30_000L,
) {
    private var localKeyPair: CryptoKeyPair = crypto.generateX25519KeyPair()
    private var broadcastKeyPair: CryptoKeyPair = crypto.generateEd25519KeyPair()
    private var sealer: NoiseKSealer = NoiseKSealer(crypto)

    private val peerPublicKeys = mutableMapOf<String, ByteArray>()
    private val lastRotationTimestampMs = mutableMapOf<String, ULong>()

    private val handshakeManager = PeerHandshakeManager(
        crypto,
        crypto.generateX25519KeyPair(),
        localPayload = handshakePayload,
    )

    // --- Public key accessors ---

    val localPublicKey: ByteArray get() = localKeyPair.publicKey
    val localBroadcastPublicKey: ByteArray get() = broadcastKeyPair.publicKey

    fun peerPublicKey(peerHex: String): ByteArray? = peerPublicKeys[peerHex]

    // --- E2E encryption ---

    fun seal(recipientHex: String, payload: ByteArray): SealResult {
        val recipientKey = peerPublicKeys[recipientHex]
            ?: return SealResult.UnknownRecipient
        return SealResult.Sealed(sealer.seal(recipientKey, payload))
    }

    fun unseal(payload: ByteArray): UnsealResult {
        if (payload.size < 48) return UnsealResult.TooShort(payload)
        return try {
            UnsealResult.Decrypted(sealer.unseal(localKeyPair.privateKey, payload))
        } catch (_: Exception) {
            // Expected for wrong-key, tampered data, or unknown sender.
            // Caller (InboundValidator) emits DECRYPTION_FAILED diagnostic.
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

    fun handleHandshakeMessage(fromPeerId: ByteArray, wireData: ByteArray): ByteArray? =
        handshakeManager.handleIncoming(fromPeerId, wireData)

    fun initiateHandshake(peerId: ByteArray): ByteArray? =
        handshakeManager.initiateHandshake(peerId, handshakePayload)

    fun isHandshakeComplete(peerId: ByteArray): Boolean =
        handshakeManager.isComplete(peerId)

    // --- Replay protection ---
    // Note: Replay guards are independent of crypto (pure counter-based) and remain
    // in MeshLink. SecurityEngine does not own replay protection.

    // --- Peer key management ---

    fun registerPeerKey(peerHex: String, publicKey: ByteArray): KeyRegistrationResult {
        val previousKey = peerPublicKeys[peerHex]
        if (previousKey != null && previousKey.contentEquals(publicKey)) {
            return KeyRegistrationResult.Unchanged
        }
        peerPublicKeys[peerHex] = publicKey
        return if (previousKey != null) {
            KeyRegistrationResult.Changed(previousKey)
        } else {
            KeyRegistrationResult.New
        }
    }

    fun handleRotationAnnouncement(
        fromPeerHex: String,
        announcement: RotationAnnouncement.RotationMessage,
    ): RotationResult {
        if (!RotationAnnouncement.verify(announcement, crypto)) {
            return RotationResult.Rejected
        }

        // Timestamp freshness: reject if outside ±window of current time
        val now = clock().toULong()
        val ts = announcement.timestampMs
        if (now > ts && (now - ts) > rotationFreshnessWindowMs.toULong()) {
            return RotationResult.Stale
        }
        if (ts > now && (ts - now) > rotationFreshnessWindowMs.toULong()) {
            return RotationResult.Stale
        }

        // Reject if timestamp is not newer than the last seen rotation from this peer
        val lastTs = lastRotationTimestampMs[fromPeerHex]
        if (lastTs != null && ts <= lastTs) {
            return RotationResult.Stale
        }

        val knownKey = peerPublicKeys[fromPeerHex]
            ?: return RotationResult.UnknownPeer
        if (!knownKey.contentEquals(announcement.oldX25519Key)) {
            return RotationResult.Rejected
        }
        peerPublicKeys[fromPeerHex] = announcement.newX25519Key
        lastRotationTimestampMs[fromPeerHex] = ts
        return RotationResult.Accepted(
            KeyChangeEvent(
                peerId = io.meshlink.util.hexToBytes(fromPeerHex),
                previousKey = announcement.oldX25519Key.copyOf(),
                newKey = announcement.newX25519Key.copyOf(),
            )
        )
    }

    // --- Identity rotation ---

    fun rotateIdentity() {
        localKeyPair = crypto.generateX25519KeyPair()
        sealer = NoiseKSealer(crypto)
        broadcastKeyPair = crypto.generateEd25519KeyPair()
    }

    // --- State management ---

    fun clear() {
        peerPublicKeys.clear()
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
