package ch.trancee.meshlink.transport

import ch.trancee.meshlink.storage.SecureStorage

/**
 * Tracks per-OEM effective L2CAP slot counts, reducing slots on BLE failures and persisting state
 * across process restarts.
 *
 * Each OEM key maps to an `(effectiveSlots, lastFailureMillis)` pair serialized as a 12-byte
 * `ByteArray` (4 bytes Int LE + 8 bytes Long LE) under storage key `"oemSlots:{oemKey}"`.
 *
 * **30-day expiry:** if the gap between [clock]`()` and the stored `lastFailureMillis` is at least
 * 30 days, [recordFailure] resets the slot count back to [initialMaxSlots] before applying the new
 * failure reduction. This prevents devices that have been stable for a month from being permanently
 * penalised for old failures.
 *
 * **Key design:** this class is key-agnostic — callers supply the full composite OEM key (e.g.
 * `"Samsung|Galaxy S22|33"`). The composite key is assembled in `AndroidBleTransport` /
 * `IosBleTransport`.
 *
 * Thread-safety: not synchronized — callers must confine access to a single coroutine dispatcher.
 *
 * @param storage Persistent key-value store for slot state.
 * @param initialMaxSlots Maximum slot count when no failures have been recorded, or after expiry.
 * @param clock Time source returning epoch milliseconds; injected for testability.
 */
internal class OemSlotTracker(
    private val storage: SecureStorage,
    private val initialMaxSlots: Int,
    private val clock: () -> Long,
) {
    private val storageKeyPrefix = "oemSlots:"
    private val thirtyDaysMillis = 30L * 24L * 60L * 60L * 1000L

    /**
     * Returns the current effective slot count for [key].
     *
     * Returns [initialMaxSlots] if no failure has ever been recorded for [key].
     *
     * @param key OEM composite key (e.g. `"Samsung|Galaxy S22|33"`).
     */
    fun effectiveSlots(key: String): Int {
        val bytes = storage.get("$storageKeyPrefix$key") ?: return initialMaxSlots
        return readInt(bytes, 0)
    }

    /**
     * Records a BLE failure for [key], reducing [effectiveSlots] by 1 (floor at 1).
     *
     * If the stored failure timestamp is older than 30 days the entry is reset to [initialMaxSlots]
     * before the reduction is applied.
     *
     * @param key OEM composite key (e.g. `"Samsung|Galaxy S22|33"`).
     */
    fun recordFailure(key: String) {
        val storageKey = "$storageKeyPrefix$key"
        val now = clock()
        val existingBytes = storage.get(storageKey)
        val currentSlots =
            if (existingBytes == null) {
                initialMaxSlots
            } else {
                val lastFailureMillis = readLong(existingBytes, 4)
                if (now - lastFailureMillis >= thirtyDaysMillis) initialMaxSlots
                else readInt(existingBytes, 0)
            }
        val newSlots = if (currentSlots > 1) currentSlots - 1 else 1
        storage.put(storageKey, writeEntry(newSlots, now))
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readLong(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 4].toLong() and 0xFF) shl 32) or
            ((bytes[offset + 5].toLong() and 0xFF) shl 40) or
            ((bytes[offset + 6].toLong() and 0xFF) shl 48) or
            ((bytes[offset + 7].toLong() and 0xFF) shl 56)

    private fun writeEntry(effectiveSlots: Int, lastFailureMillis: Long): ByteArray {
        val bytes = ByteArray(12)
        bytes[0] = (effectiveSlots and 0xFF).toByte()
        bytes[1] = ((effectiveSlots shr 8) and 0xFF).toByte()
        bytes[2] = ((effectiveSlots shr 16) and 0xFF).toByte()
        bytes[3] = ((effectiveSlots shr 24) and 0xFF).toByte()
        bytes[4] = (lastFailureMillis and 0xFF).toByte()
        bytes[5] = ((lastFailureMillis shr 8) and 0xFF).toByte()
        bytes[6] = ((lastFailureMillis shr 16) and 0xFF).toByte()
        bytes[7] = ((lastFailureMillis shr 24) and 0xFF).toByte()
        bytes[8] = ((lastFailureMillis shr 32) and 0xFF).toByte()
        bytes[9] = ((lastFailureMillis shr 40) and 0xFF).toByte()
        bytes[10] = ((lastFailureMillis shr 48) and 0xFF).toByte()
        bytes[11] = ((lastFailureMillis shr 56) and 0xFF).toByte()
        return bytes
    }
}
