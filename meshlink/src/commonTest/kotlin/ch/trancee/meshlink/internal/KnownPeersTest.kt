package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KnownPeersTest {
    @Test
    fun forgetsTheLeastRecentlySeenNodeIdOnceFull() {
        // Arrange
        val known = KnownPeers()
        val ids = List(KnownPeers.CAPACITY + 1) { id(it) }

        // Act
        ids.forEach { known.touch(it) }

        // Assert
        assertFalse(ids.first() in known)
        assertTrue(ids.drop(1).all { it in known })
    }

    @Test
    fun touchingAKnownNodeIdMakesItTheMostRecentlySeen() {
        // Arrange
        val known = KnownPeers()
        val ids = List(KnownPeers.CAPACITY) { id(it) }
        ids.forEach { known.touch(it) }

        // Act
        known.touch(ids[0])
        known.touch(id(KnownPeers.CAPACITY))

        // Assert
        assertTrue(ids[0] in known)
        assertFalse(ids[1] in known)
    }

    private fun id(n: Int): NodeId = NodeId(ByteArray(NODE_ID_LENGTH) { (n shr (it % Int.SIZE_BITS)).toByte() })

    private companion object {
        const val NODE_ID_LENGTH = 32
    }
}
