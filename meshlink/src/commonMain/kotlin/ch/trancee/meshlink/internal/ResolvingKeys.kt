package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId

/**
 * A gated-mesh node's resolving keys for one run (spec "Resolvable tag", "Resolving key update"):
 * its own, each lasting `resolving-key-lifetime`, and those its linked peers sent it. Not
 * thread-safe; the node calls it on its serial dispatcher. Every time is wall-clock milliseconds.
 *
 * The node announces its [next] key `resolving-key-lead` and one epoch before it expires its own,
 * and switches one epoch early, so a payload advertised with the old key stops showing before peers
 * delete that key. It keeps at most two keys, the latest-expiring, for each of [PEERS] peers.
 */
internal class ResolvingKeys(
    private val crypto: CryptoProvider,
    nowMillis: Long,
) {
    private val derivations = Derivations(crypto)
    private var current = fresh(nowMillis)

    /** The key this node announced but doesn't use yet, or `null`. */
    var next: ResolvingKey? = null
        private set

    // Least recently learned first.
    private val peers = LinkedHashMap<NodeId, List<ResolvingKey>>()
    private var tagsEpoch: UInt? = null
    private var tags: Map<Long, NodeId>? = null

    /** The key this node advertises with at [nowMillis]. */
    fun own(nowMillis: Long): ResolvingKey {
        if (nowMillis / MILLIS + EPOCH_SECONDS >= current.notAfter.toLong()) {
            current = next ?: fresh(nowMillis)
            next = null
        }
        return current
    }

    /**
     * The key [own] will return at [atMillis], without switching to it: [next] once it is due, if
     * this node announced one, else the current key.
     */
    fun at(atMillis: Long): ResolvingKey {
        val due = atMillis / MILLIS + EPOCH_SECONDS >= current.notAfter.toLong()
        return next?.takeIf { due } ?: current
    }

    /** When this node must next [announce] a key. */
    fun nextAnnouncementMillis(): Long = ((next ?: current).notAfter.toLong() - LEAD_SECONDS - EPOCH_SECONDS) * MILLIS

    /** Draws the key this node uses after its current one, at [nowMillis], and returns it as [next]. */
    fun announce(nowMillis: Long): ResolvingKey {
        val following = ResolvingKey(crypto.randomBytes(ResolvingKey.KEY_LENGTH), own(nowMillis).notAfter + LIFETIME)
        next = following
        return following
    }

    /** Stores [key] for [peer] unless it has expired at [nowMillis]. */
    fun learn(
        peer: NodeId,
        key: ResolvingKey,
        nowMillis: Long,
    ) {
        if (!expired(key, nowMillis)) {
            val held = peers.remove(peer).orEmpty().filterNot { it.key.contentEquals(key.key) }
            peers[peer] = (held + key).sortedByDescending { it.notAfter }.take(KEYS_PER_PEER)
            if (peers.size > PEERS) peers.remove(peers.keys.first())
            tags = null
        }
    }

    /** The peer whose live key at [nowMillis] makes [tag] for epoch e − 1, e or e + 1, where e is [epoch]. */
    fun resolve(
        tag: ByteArray,
        epoch: UInt,
        nowMillis: Long,
    ): NodeId? {
        val before = peers.values.sumOf { it.size }
        peers.entries.forEach { entry -> entry.setValue(entry.value.filterNot { expired(it, nowMillis) }) }
        peers.values.removeAll { it.isEmpty() }
        if (epoch != tagsEpoch || before != peers.values.sumOf { it.size }) tags = null
        val table = tags ?: table(epoch)
        return table[tag.u64()]
    }

    private fun table(epoch: UInt): Map<Long, NodeId> {
        val epochs = listOf(epoch - 1u, epoch, epoch + 1u)
        val table =
            peers.entries
                .flatMap { (peer, keys) ->
                    keys.flatMap { key -> epochs.map { derivations.resolvableTag(key.key, it).u64() to peer } }
                }.toMap()
        tags = table
        tagsEpoch = epoch
        return table
    }

    private fun fresh(nowMillis: Long): ResolvingKey =
        ResolvingKey(crypto.randomBytes(ResolvingKey.KEY_LENGTH), (nowMillis / MILLIS + LIFETIME.toLong()).toUInt())

    private fun expired(
        key: ResolvingKey,
        nowMillis: Long,
    ): Boolean = key.notAfter.toLong() * MILLIS <= nowMillis

    private fun ByteArray.u64(): Long =
        fold(0L) { value, byte ->
            (value shl Byte.SIZE_BITS) or (byte.toLong() and BYTE)
        }

    companion object {
        /** Spec `resolving-key-peers`: the peers whose keys a node holds. */
        const val PEERS: Int = 1_024

        private const val KEYS_PER_PEER = 2
        private const val MILLIS = 1_000L
        private const val EPOCH_SECONDS = Derivations.EPOCH_SECONDS.toLong()

        /** Spec `resolving-key-lifetime`. */
        private const val LIFETIME = 86_400u

        /** Spec `resolving-key-lead`. */
        private const val LEAD_SECONDS = 3_600L
        private const val BYTE = 0xFFL
    }
}
