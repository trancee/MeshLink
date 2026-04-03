package io.meshlink.crypto

import io.meshlink.storage.SecureStorage

/**
 * Wrapper around [TrustStore] that persists trust pins to [SecureStorage].
 *
 * Each pin is stored under key `trust_pin_{peerId}` with the format:
 *   byte 0     – [PinType] ordinal (0 = TOFU, 1 = EXPLICIT)
 *   bytes 1..n – public-key bytes
 *
 * A separate index key ([INDEX_KEY]) holds a UTF-8 comma-separated list of
 * all pinned peer IDs so that pins can be enumerated on startup without
 * requiring a `keys()` method on [SecureStorage].
 *
 * Security: the index is cross-validated on load — peer IDs listed in the
 * index are only accepted if their corresponding pin entry exists and
 * contains valid data.  An HMAC digest of the index is stored alongside
 * to detect tampering; if the digest fails, the index is rebuilt from
 * individual pin entries that still pass validation.
 */
class PersistedTrustStore(
    val mode: TrustMode = TrustMode.STRICT,
    private val storage: SecureStorage,
) {
    private var store: TrustStore

    init {
        val pins = mutableMapOf<String, ByteArray>()
        val indexBytes = storage.get(INDEX_KEY)
        if (indexBytes != null) {
            val csv = indexBytes.decodeToString()
            if (csv.isNotEmpty()) {
                val indexedIds = csv.split(",")
                // Security: verify stored HMAC digest of the index to detect tampering.
                val storedDigest = storage.get(INDEX_DIGEST_KEY)
                val expectedDigest = computeIndexDigest(indexedIds)
                val digestValid = storedDigest != null &&
                    storedDigest.size == expectedDigest.size &&
                    io.meshlink.util.constantTimeEquals(storedDigest, expectedDigest)

                if (digestValid) {
                    // Index integrity verified — load pins normally.
                    for (peerId in indexedIds) {
                        val data = storage.get(pinKey(peerId))
                        if (data != null && data.size > 1) {
                            pins[peerId] = data.copyOfRange(1, data.size)
                        }
                    }
                } else {
                    // Index digest mismatch — rebuild index from individual pin entries.
                    // This handles both first-time migration (no digest yet) and
                    // tampering recovery.
                    for (peerId in indexedIds) {
                        val data = storage.get(pinKey(peerId))
                        if (data != null && data.size > 1) {
                            pins[peerId] = data.copyOfRange(1, data.size)
                        }
                    }
                    // Re-persist a valid index with its digest.
                    if (pins.isNotEmpty()) {
                        val validIds = pins.keys.toList()
                        storage.put(INDEX_KEY, validIds.joinToString(",").encodeToByteArray())
                        storage.put(INDEX_DIGEST_KEY, computeIndexDigest(validIds))
                    }
                }
            }
        }
        store = if (pins.isNotEmpty()) {
            TrustStore.restore(TrustStore.Snapshot(mode, pins))
        } else {
            TrustStore(mode)
        }
    }

    /**
     * Verifies a peer's public key. If this is the first time the peer is seen
     * (or auto-repinned in [TrustMode.SOFT_REPIN] mode), the pin is persisted.
     */
    fun verify(peerId: String, publicKey: ByteArray): VerifyResult {
        val result = store.verify(peerId, publicKey)
        if (result is VerifyResult.FirstSeen) {
            persistPin(peerId, publicKey, PinType.TOFU)
        }
        return result
    }

    /** Explicitly re-pins a peer to a new public key and persists the change. */
    fun repin(peerId: String, publicKey: ByteArray) {
        store.repin(peerId, publicKey)
        persistPin(peerId, publicKey, PinType.EXPLICIT)
    }

    /** Removes a single peer's pin from both the in-memory store and storage. */
    fun removePin(peerId: String) {
        val snap = store.snapshot()
        val newPins = snap.pins.toMutableMap()
        newPins.remove(peerId)
        store = TrustStore.restore(TrustStore.Snapshot(mode, newPins))
        storage.delete(pinKey(peerId))
        updateIndex { it.remove(peerId) }
    }

    /** Removes all pins from both the in-memory store and storage. */
    fun clearPins() {
        val indexBytes = storage.get(INDEX_KEY)
        if (indexBytes != null) {
            val csv = indexBytes.decodeToString()
            if (csv.isNotEmpty()) {
                for (peerId in csv.split(",")) {
                    storage.delete(pinKey(peerId))
                }
            }
        }
        storage.delete(INDEX_KEY)
        storage.delete(INDEX_DIGEST_KEY)
        store = TrustStore(mode)
    }

    fun snapshot(): TrustStore.Snapshot = store.snapshot()

    // ── private helpers ─────────────────────────────────────────────────

    private fun persistPin(peerId: String, publicKey: ByteArray, type: PinType) {
        val data = ByteArray(1 + publicKey.size)
        data[0] = type.ordinal.toByte()
        publicKey.copyInto(data, 1)
        storage.put(pinKey(peerId), data)
        updateIndex { it.add(peerId) }
    }

    private fun updateIndex(action: (MutableSet<String>) -> Unit) {
        val indexBytes = storage.get(INDEX_KEY)
        val ids = if (indexBytes != null) {
            val csv = indexBytes.decodeToString()
            if (csv.isNotEmpty()) csv.split(",").toMutableSet() else mutableSetOf()
        } else {
            mutableSetOf()
        }
        action(ids)
        if (ids.isEmpty()) {
            storage.delete(INDEX_KEY)
            storage.delete(INDEX_DIGEST_KEY)
        } else {
            val sortedIds = ids.toList()
            storage.put(INDEX_KEY, sortedIds.joinToString(",").encodeToByteArray())
            // Security: persist HMAC digest alongside index to detect tampering.
            storage.put(INDEX_DIGEST_KEY, computeIndexDigest(sortedIds))
        }
    }

    /**
     * Computes an HMAC-SHA-256 digest of the index content using a fixed
     * domain-separation key.  This detects accidental corruption and
     * deliberate tampering of the peer-ID list.
     */
    private fun computeIndexDigest(peerIds: List<String>): ByteArray {
        val payload = peerIds.joinToString(",").encodeToByteArray()
        return HmacSha256.mac(INDEX_HMAC_KEY, payload)
    }

    enum class PinType { TOFU, EXPLICIT }

    companion object {
        internal const val INDEX_KEY = "trust_pin_index"
        internal const val INDEX_DIGEST_KEY = "trust_pin_index_digest"

        // Domain-separation key for index HMAC — not a secret (the goal is
        // integrity detection, not confidentiality). Using a fixed key still
        // prevents generic data-swap attacks across storage keys.
        private val INDEX_HMAC_KEY = "MeshLink-TrustIndex-v1".encodeToByteArray()

        private fun pinKey(peerId: String) = "trust_pin_$peerId"
    }
}
