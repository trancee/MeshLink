package ch.trancee.meshlink.transport

import ch.trancee.meshlink.storage.SecureStorage

/**
 * In-memory cache for per-OEM L2CAP capability probe results, valid for the session lifetime.
 *
 * Both platforms contribute entries using a platform-specific composite key:
 * - Android: `"manufacturer|model|SDK_INT"`
 * - iOS: `"Apple|model|osVersion"`
 *
 * Results are cached so each OEM device family is probed at most once per process lifetime,
 * avoiding repeated connection attempts on hardware known to mishandle L2CAP CoC.
 *
 * Use [persist] / [restore] to survive process restarts: [persist] serializes all entries as
 * `"key:bool,..."` UTF-8 under storage key `"oemL2capProbeCache"`, and [restore] re-populates the
 * cache from that blob.
 *
 * Thread-safety: not synchronized — callers must confine access to a single coroutine dispatcher or
 * provide external synchronization where concurrent writes are possible.
 */
internal class OemL2capProbeCache {

    private val cache = HashMap<String, Boolean>()
    private val persistenceKey = "oemL2capProbeCache"

    /** Returns the cached L2CAP capability for [oemKey], or `null` if no probe result is stored. */
    fun get(oemKey: String): Boolean? = cache[oemKey]

    /**
     * Stores or overwrites the L2CAP capability probe result for [oemKey].
     *
     * @param oemKey Platform-specific OEM identifier composite key.
     * @param l2capCapable `true` if L2CAP CoC succeeded on this OEM; `false` if it must be skipped.
     */
    fun put(oemKey: String, l2capCapable: Boolean) {
        cache[oemKey] = l2capCapable
    }

    /** Removes all cached entries. Useful for testing or when the BLE stack is reset. */
    fun clear() {
        cache.clear()
    }

    /** Number of OEM entries currently cached. */
    val size: Int
        get() = cache.size

    /**
     * Serializes all cached entries as `"key:bool,..."` UTF-8 and writes them to [storage] under
     * key `"oemL2capProbeCache"`.
     *
     * An empty cache produces an empty `ByteArray`. Paired with [restore] to survive process
     * restarts.
     *
     * @param storage Persistent key-value store to write to.
     */
    fun persist(storage: SecureStorage) {
        val text = cache.entries.joinToString(",") { (key, value) -> "$key:$value" }
        storage.put(persistenceKey, text.encodeToByteArray())
    }

    /**
     * Populates the cache from the blob stored under key `"oemL2capProbeCache"` in [storage].
     *
     * A missing or empty blob is a no-op. Malformed entries (no colon separator, or a value that is
     * neither `"true"` nor `"false"`) are silently skipped.
     *
     * @param storage Persistent key-value store to read from.
     */
    fun restore(storage: SecureStorage) {
        val bytes = storage.get(persistenceKey) ?: return
        if (bytes.isEmpty()) return
        for (entry in bytes.decodeToString().split(",")) {
            val colonIndex = entry.lastIndexOf(':')
            if (colonIndex < 1) continue
            val value =
                when (entry.substring(colonIndex + 1)) {
                    "true" -> true
                    "false" -> false
                    else -> continue
                }
            cache[entry.substring(0, colonIndex)] = value
        }
    }
}
