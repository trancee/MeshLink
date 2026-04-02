package io.meshlink.wire

import io.meshlink.model.MessageId
import io.meshlink.util.PlatformLock
import io.meshlink.util.currentTimeMillis
import io.meshlink.util.withLock

/**
 * Generates deterministic 12-byte structured message IDs:
 * `[8-byte peer ID hash | 4-byte LE counter]`.
 *
 * The counter is initialised from [currentTimeMillis] so that IDs generated
 * after a restart never collide with IDs still in peers' dedup sets (5-min TTL).
 * Thread-safe via [PlatformLock].
 */
class MessageIdGenerator(localPeerId: ByteArray) {

    private val peerIdPrefix: ByteArray = localPeerId.copyOfRange(0, PEER_ID_PREFIX_SIZE)
    private val lock = PlatformLock()
    private var counter: Int = (currentTimeMillis() and COUNTER_MASK).toInt()

    fun generate(): MessageId {
        val c = lock.withLock {
            val current = counter
            counter++
            current
        }
        val id = ByteArray(MessageId.SIZE)
        peerIdPrefix.copyInto(id, 0)
        id[8] = (c and 0xFF).toByte()
        id[9] = ((c shr 8) and 0xFF).toByte()
        id[10] = ((c shr 16) and 0xFF).toByte()
        id[11] = ((c shr 24) and 0xFF).toByte()
        return MessageId.fromBytes(id)
    }

    companion object {
        private const val PEER_ID_PREFIX_SIZE = 8
        private const val COUNTER_MASK = 0xFFFFFFFFL
    }
}
