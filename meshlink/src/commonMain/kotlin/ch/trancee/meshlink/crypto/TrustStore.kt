package ch.trancee.meshlink.crypto

import ch.trancee.meshlink.storage.SecureStorage

/**
 * Trust mode governing how [TrustStore] reacts when a peer's key changes.
 *
 * - [STRICT]: Key changes are always rejected. A [KeyChangeEvent] is emitted via [onKeyChange] if a
 *   callback is provided. The app must call [TrustStore.repinKey] explicitly to accept a new key.
 * - [PROMPT]: Key changes invoke the [onKeyChange] callback. The callback's return value determines
 *   whether the new key is pinned (`true`) or rejected (`false`). If no callback is provided,
 *   PROMPT behaves identically to STRICT.
 */
internal enum class TrustMode {
    STRICT,
    PROMPT,
}

/**
 * Carries the peer identifier and both the old and new key when a pinned key changes.
 *
 * Equality is defined solely on [peerKeyHash] so events for the same peer compare equal regardless
 * of which particular key change triggered them.
 */
internal class KeyChangeEvent(
    val peerKeyHash: ByteArray,
    val oldKey: ByteArray,
    val newKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is KeyChangeEvent && peerKeyHash.contentEquals(other.peerKeyHash)

    override fun hashCode(): Int = peerKeyHash.contentHashCode()
}

/**
 * TOFU (Trust On First Use) key store per spec §6.3.
 *
 * Pins a peer's X25519 static public key on the first successful Noise XX handshake. Subsequent
 * calls to [pinKey] with the same peer hash compare the supplied key against the stored pin using
 * [constantTimeEquals] to prevent timing side-channels.
 *
 * Pinned keys are persisted in [storage] under `"meshlink.trust.<keyHashHex>"` where `<keyHashHex>`
 * is the lowercase hex encoding of the 12-byte peer Key Hash.
 *
 * **Thread-safety:** not thread-safe. Callers must synchronise externally.
 */
internal class TrustStore(
    private val storage: SecureStorage,
    private val mode: TrustMode = TrustMode.STRICT,
    private val onKeyChange: ((KeyChangeEvent) -> Boolean)? = null,
) {
    /**
     * Returns the [SecureStorage] key for [peerKeyHash]: `"meshlink.trust.<hex>"`.
     *
     * The hex string is 24 lowercase characters (12 bytes × 2 nibbles each).
     */
    internal fun storageKey(peerKeyHash: ByteArray): String {
        val sb = StringBuilder()
        for (b in peerKeyHash) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v shr 4])
            sb.append("0123456789abcdef"[v and 0xF])
        }
        return "meshlink.trust.$sb"
    }

    /**
     * Pins [staticPublicKey] for [peerKeyHash].
     *
     * - No existing pin: stores the key and returns `true`.
     * - Existing pin matches [staticPublicKey] (constant-time): no-op, returns `true`.
     * - Existing pin differs — a [KeyChangeEvent] is raised:
     *     - [TrustMode.STRICT]: notifies [onKeyChange] if provided; always returns `false`.
     *     - [TrustMode.PROMPT] with callback: repins and returns `true` if callback returns `true`;
     *       returns `false` otherwise.
     *     - [TrustMode.PROMPT] without callback: returns `false`.
     *
     * @return `true` if the pin was accepted or unchanged; `false` if rejected.
     */
    fun pinKey(peerKeyHash: ByteArray, staticPublicKey: ByteArray): Boolean {
        val key = storageKey(peerKeyHash)
        val existing = storage.get(key)
        if (existing == null) {
            storage.put(key, staticPublicKey)
            return true
        }
        if (constantTimeEquals(existing, staticPublicKey)) {
            return true
        }
        val event = KeyChangeEvent(peerKeyHash, existing, staticPublicKey)
        if (mode == TrustMode.PROMPT) {
            val callback = onKeyChange
            if (callback != null) {
                if (callback(event)) {
                    storage.put(key, staticPublicKey)
                    return true
                }
                return false
            }
            return false
        }
        // STRICT mode: notify via callback if provided, then always reject.
        val callback = onKeyChange
        if (callback != null) {
            callback(event)
        }
        return false
    }

    /** Returns the pinned X25519 static public key for [peerKeyHash], or `null` if not pinned. */
    fun getPinnedKey(peerKeyHash: ByteArray): ByteArray? = storage.get(storageKey(peerKeyHash))

    /**
     * Forcefully replaces (or creates) the pin for [peerKeyHash] with [newKey].
     *
     * Use in [TrustMode.STRICT] after manual out-of-band key verification to accept a new key
     * without going through the callback flow.
     */
    fun repinKey(peerKeyHash: ByteArray, newKey: ByteArray) {
        storage.put(storageKey(peerKeyHash), newKey)
    }

    /** Removes the pin for [peerKeyHash]. No-op if the peer is not pinned. */
    fun removePinForPeer(peerKeyHash: ByteArray) {
        storage.remove(storageKey(peerKeyHash))
    }
}
