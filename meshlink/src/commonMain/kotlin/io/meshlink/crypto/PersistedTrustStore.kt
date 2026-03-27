package io.meshlink.crypto

import io.meshlink.storage.SecureStorage

/**
 * Wrapper around [TrustStore] that persists trust pins to [SecureStorage].
 *
 * Each pin is stored under key `trust_pin_{peerId}` with the format:
 *   byte 0     – [PinType] ordinal (0 = TOFI, 1 = EXPLICIT)
 *   bytes 1..n – public-key bytes
 *
 * A separate index key ([INDEX_KEY]) holds a UTF-8 comma-separated list of
 * all pinned peer IDs so that pins can be enumerated on startup without
 * requiring a `keys()` method on [SecureStorage].
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
                for (peerId in csv.split(",")) {
                    val data = storage.get(pinKey(peerId))
                    if (data != null && data.size > 1) {
                        pins[peerId] = data.copyOfRange(1, data.size)
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
            persistPin(peerId, publicKey, PinType.TOFI)
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
        } else {
            storage.put(INDEX_KEY, ids.joinToString(",").encodeToByteArray())
        }
    }

    enum class PinType { TOFI, EXPLICIT }

    companion object {
        internal const val INDEX_KEY = "trust_pin_index"

        private fun pinKey(peerId: String) = "trust_pin_$peerId"
    }
}
