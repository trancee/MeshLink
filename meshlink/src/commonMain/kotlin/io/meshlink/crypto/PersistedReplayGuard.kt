package io.meshlink.crypto

import io.meshlink.storage.SecureStorage

/**
 * Wrapper around [ReplayGuard] that persists outbound and inbound counters
 * to [SecureStorage] so they survive app restarts.
 *
 * - Outbound counter is persisted on every [advance] call.
 * - Inbound highest-seen counter is persisted immediately whenever the
 *   high-water mark advances. This eliminates the vulnerability window
 *   that existed with the previous periodic-persist strategy.
 * - On creation, persisted counters are loaded from storage. The sliding-window bitmask is
 *   set to all-ones after restore so that only counters above the persisted highest-seen
 *   value are accepted (conservative replay protection after restart).
 */
class PersistedReplayGuard(
    private val peerIdHex: String,
    private val storage: SecureStorage,
) {
    private val guard: ReplayGuard

    init {
        val outbound = loadCounter(outboundKey())
        val inbound = loadCounter(inboundKey())
        // After restart the fine-grained window state is lost. Setting the bitmask to
        // all-ones marks every position as "already seen", so only counters strictly
        // above highestSeen will be accepted. This is the safe default.
        guard = ReplayGuard.restore(
            ReplayGuard.Snapshot(
                outboundCounter = outbound,
                highestSeen = inbound,
                windowBitmask = if (inbound > 0uL) ULong.MAX_VALUE else 0uL,
            )
        )
    }

    /** Returns the next outbound counter and persists it. */
    fun advance(): ULong {
        val counter = guard.advance()
        storage.put(outboundKey(), counter.toBytesBigEndian())
        return counter
    }

    /** Checks whether [counter] is fresh. Persists immediately on high-water-mark advance. */
    fun check(counter: ULong): Boolean {
        val prevHighest = guard.snapshot().highestSeen
        val result = guard.check(counter)
        if (result) {
            val newHighest = guard.snapshot().highestSeen
            if (newHighest > prevHighest) {
                persistInbound()
            }
        }
        return result
    }

    /** Force-persists both counters (call on shutdown / session end). */
    fun flush() {
        val snap = guard.snapshot()
        storage.put(outboundKey(), snap.outboundCounter.toBytesBigEndian())
        storage.put(inboundKey(), snap.highestSeen.toBytesBigEndian())
    }

    fun snapshot(): ReplayGuard.Snapshot = guard.snapshot()

    // ── private helpers ─────────────────────────────────────────────────

    private fun outboundKey() = "replay_out_$peerIdHex"
    private fun inboundKey() = "replay_in_$peerIdHex"

    private fun persistInbound() {
        storage.put(inboundKey(), guard.snapshot().highestSeen.toBytesBigEndian())
    }

    private fun loadCounter(key: String): ULong {
        val bytes = storage.get(key) ?: return 0uL
        if (bytes.size != 8) return 0uL
        return bytes.fromBytesBigEndian()
    }

    companion object {
        internal fun ULong.toBytesBigEndian(): ByteArray {
            val b = ByteArray(8)
            var v = this
            for (i in 7 downTo 0) {
                b[i] = (v and 0xFFuL).toByte()
                v = v shr 8
            }
            return b
        }

        internal fun ByteArray.fromBytesBigEndian(): ULong {
            var v = 0uL
            for (i in 0 until 8) {
                v = (v shl 8) or this[i].toUByte().toULong()
            }
            return v
        }
    }
}
