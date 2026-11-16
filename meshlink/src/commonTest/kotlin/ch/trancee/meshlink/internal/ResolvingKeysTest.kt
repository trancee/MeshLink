package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ResolvingKeysTest {
    @Test
    fun theOwnKeyLastsADayAndItsSuccessorIsAnnouncedALeadAndAnEpochBeforeUse() {
        // Arrange
        val keys = ResolvingKeys(SodiumCryptoProvider, START)
        val first = keys.own(START)

        // Act
        val due = keys.nextAnnouncementMillis()
        val next = keys.announce(due)

        // Assert
        assertEquals(START_SECONDS + LIFETIME, first.notAfter.toLong())
        assertEquals((first.notAfter.toLong() - LEAD - EPOCH) * MILLIS, due)
        assertEquals(first.notAfter.toLong() + LIFETIME, next.notAfter.toLong())
        assertSame(next, keys.next)
        assertSame(first, keys.own(due))
        assertEquals((next.notAfter.toLong() - LEAD - EPOCH) * MILLIS, keys.nextAnnouncementMillis())
    }

    @Test
    fun theAnnouncedKeyReplacesTheOwnKeyOneEpochBeforeItExpires() {
        // Arrange
        val keys = ResolvingKeys(SodiumCryptoProvider, START)
        val first = keys.own(START)
        val next = keys.announce(keys.nextAnnouncementMillis())
        val switch = (first.notAfter.toLong() - EPOCH) * MILLIS

        // Act
        val before = keys.own(switch - 1)
        val after = keys.own(switch)

        // Assert
        assertSame(first, before)
        assertSame(next, after)
        assertNull(keys.next)
    }

    @Test
    fun anOwnKeyNobodyAnnouncedIsReplacedByAFreshOne() {
        // Arrange
        val keys = ResolvingKeys(SodiumCryptoProvider, START)
        val first = keys.own(START)

        // Act
        val later = keys.own((first.notAfter.toLong() - EPOCH) * MILLIS)

        // Assert
        assertTrue(!later.key.contentEquals(first.key))
        assertEquals(first.notAfter.toLong() - EPOCH + LIFETIME, later.notAfter.toLong())
    }

    @Test
    fun aLearnedKeyResolvesTagsOfTheAdjacentEpochsOnly() {
        // Arrange
        val keys = ResolvingKeys(SodiumCryptoProvider, START)
        val key = key(1, START_SECONDS + LIFETIME)
        keys.learn(PEER, key, START)

        // Act
        val resolved = (-2..2).map { keys.resolve(tag(key, EPOCH_NOW.toLong() + it), EPOCH_NOW, START) }

        // Assert
        assertEquals(listOf(null, PEER, PEER, PEER, null), resolved)
    }

    @Test
    fun aKeyNoLongerResolvesAtItsNotAfter() {
        // Arrange
        val keys = ResolvingKeys(SodiumCryptoProvider, START)
        val key = key(1, START_SECONDS + EPOCH)
        keys.learn(PEER, key, START)
        val expiry = key.notAfter.toLong() * MILLIS

        // Act
        val before = keys.resolve(tag(key, EPOCH_NOW.toLong()), EPOCH_NOW, expiry - 1)
        val after = keys.resolve(tag(key, EPOCH_NOW.toLong()), EPOCH_NOW, expiry)

        // Assert
        assertEquals(PEER, before)
        assertNull(after)
    }

    @Test
    fun anExpiredKeyIsNotStored() {
        // Arrange
        val keys = ResolvingKeys(SodiumCryptoProvider, START)
        val key = key(1, START_SECONDS)

        // Act
        keys.learn(PEER, key, START)

        // Assert
        assertNull(keys.resolve(tag(key, EPOCH_NOW.toLong()), EPOCH_NOW, START))
    }

    @Test
    fun aPeerKeepsItsTwoLatestKeys() {
        // Arrange
        val keys = ResolvingKeys(SodiumCryptoProvider, START)
        val stored = (1..3).map { key(it, START_SECONDS + it * LIFETIME) }

        // Act
        listOf(stored[1], stored[0], stored[1], stored[2]).forEach { keys.learn(PEER, it, START) }

        // Assert
        val resolved = stored.map { keys.resolve(tag(it, EPOCH_NOW.toLong()), EPOCH_NOW, START) }
        assertEquals(listOf(null, PEER, PEER), resolved)
    }

    @Test
    fun theLeastRecentlyLearnedPeerIsForgottenOnceFull() {
        // Arrange
        val keys = ResolvingKeys(SodiumCryptoProvider, START)
        val peers = List(ResolvingKeys.PEERS + 1) { id(it) }
        val learned = peers.mapIndexed { n, _ -> key(n, START_SECONDS + LIFETIME) }
        keys.learn(peers[0], learned[0], START)
        keys.learn(peers[1], learned[1], START)

        // Act
        keys.learn(peers[0], key(-1, START_SECONDS + LIFETIME), START)
        (2..ResolvingKeys.PEERS).forEach { keys.learn(peers[it], learned[it], START) }

        // Assert
        assertEquals(peers[0], keys.resolve(tag(learned[0], EPOCH_NOW.toLong()), EPOCH_NOW, START))
        assertNull(keys.resolve(tag(learned[1], EPOCH_NOW.toLong()), EPOCH_NOW, START))
        assertNotNull(keys.resolve(tag(learned.last(), EPOCH_NOW.toLong()), EPOCH_NOW, START))
    }

    @Test
    fun anUnknownTagResolvesToNoPeer() {
        val keys = ResolvingKeys(SodiumCryptoProvider, START)

        assertNull(keys.resolve(ByteArray(TAG_LENGTH), EPOCH_NOW, START))
    }

    private fun key(
        n: Int,
        notAfter: Long,
    ): ResolvingKey {
        val bytes = ByteArray(ResolvingKey.KEY_LENGTH) { (n shr (it % Int.SIZE_BITS)).toByte() }
        return ResolvingKey(bytes, notAfter.toUInt())
    }

    private fun tag(
        key: ResolvingKey,
        epoch: Long,
    ): ByteArray = Derivations(SodiumCryptoProvider).resolvableTag(key.key, epoch.toUInt())

    private fun id(n: Int): NodeId = NodeId(ByteArray(NODE_ID_LENGTH) { (n shr (it % Int.SIZE_BITS)).toByte() })

    private companion object {
        const val MILLIS = 1_000L
        const val START_SECONDS = 1_700_000_100L
        const val START = START_SECONDS * MILLIS
        const val LIFETIME = 86_400L
        const val LEAD = 3_600L
        const val EPOCH = 300L
        const val NODE_ID_LENGTH = 32
        const val TAG_LENGTH = 8
        val EPOCH_NOW = (START_SECONDS / EPOCH).toUInt()
        val PEER = NodeId(ByteArray(NODE_ID_LENGTH) { 0x42 })
    }
}
