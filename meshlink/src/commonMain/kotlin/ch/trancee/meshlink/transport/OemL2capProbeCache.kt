package ch.trancee.meshlink.transport

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
 * Thread-safety: not synchronized — callers must confine access to a single coroutine dispatcher or
 * provide external synchronization where concurrent writes are possible.
 */
class OemL2capProbeCache {

    private val cache = HashMap<String, Boolean>()

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
}
