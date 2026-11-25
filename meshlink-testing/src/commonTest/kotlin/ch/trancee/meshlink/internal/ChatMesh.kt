package ch.trancee.meshlink.internal

import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.OutgoingMessage
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.ReceivedMessage
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.SendResult
import ch.trancee.meshlink.meshLinkConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.test.assertIs

/**
 * [count] chat nodes in [profile] on seeded air, first all in range of each other until they know each other,
 * then node `i` at `i` × [SPACING] on a line.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalMeshLinkApi::class)
internal class ChatMesh(
    private val scope: TestScope,
    private val profile: SecurityProfile,
    count: Int,
    seed: Long = SEED,
) {
    val air = VirtualAir(scope.backgroundScope, TestClock(scope.testScheduler), SeededRandom(seed))
    val platforms = List(count) { air.platform(StandardTestDispatcher(scope.testScheduler)) }
    private val crypto = List(count) { OpenedFrames(SeededCrypto(seed + it)) }
    private val config = meshLinkConfig(Preset.OfflineChat, profile) { meshId = MESH }.build()
    val nodes = platforms.indices.map { MeshLink.create(config, MeshPlatform.of(platforms[it], crypto[it])) }
    private val inboxes = nodes.map { mutableListOf<ReceivedMessage>() }

    suspend fun start(): ChatMesh {
        nodes.forEachIndexed { index, node ->
            node.start()
            scope.backgroundScope.launch { node.messages.collect { inboxes[index] += it } }
        }
        scope.testScheduler.advanceTimeBy(SETTLE_MILLIS)
        platforms.indices.forEach { place(it, it * SPACING) }
        scope.testScheduler.advanceTimeBy(SETTLE_MILLIS)
        return this
    }

    fun id(index: Int): NodeId =
        nodes.firstNotNullOf { node ->
            node.peers.value
                .firstOrNull { it.nodeId.toByteArray().contentEquals(nodeId(platforms[index])) }
                ?.nodeId
        }

    fun place(
        index: Int,
        x: Double,
    ) = air.move(platforms[index].radio, x, 0.0)

    /** Moves node [index] out of everyone's range. */
    fun leave(index: Int) = air.move(platforms[index].radio, AWAY + index * AWAY, 0.0)

    /** Node [from] sends [text] to [to]; then [settleMillis] pass. */
    fun send(
        from: Int,
        to: NodeId,
        text: String,
        settleMillis: Long = SETTLE_MILLIS,
    ): OutgoingMessage =
        assertIs<SendResult.Accepted>(nodes[from].send(to, text.encodeToByteArray())).message.also {
            scope.testScheduler.advanceTimeBy(settleMillis)
        }

    /** Nodes [index] and [other] meet far from everyone else, until linked. */
    fun meetFar(
        index: Int,
        other: Int,
    ) {
        place(index, FAR)
        place(other, FAR + SPACING)
        scope.testScheduler.advanceTimeBy(SETTLE_MILLIS)
    }

    /** The `CATCH_UP`s node [index] received. */
    fun catchUps(index: Int): List<LinkFrame.CatchUp> {
        val received =
            if (profile == SecurityProfile.Unsecured) {
                val ends = platforms.flatMap { it.radio.ends }.filter { it.remote == platforms[index].radio }
                ends.flatMap(::clearFrames)
            } else {
                crypto[index].frames()
            }
        return received
            .filter { it[0] == CATCH_UP }
            .map { assertIs<LinkFrame.CatchUp>(assertIs<Decoded.Valid<LinkFrame>>(LinkFrame.decode(it)).value) }
    }

    fun texts(index: Int): List<String> = inboxes[index].map { it.payload.decodeToString() }

    /** The `GRAFT`s node [index] sent. */
    fun grafts(index: Int): Int =
        if (profile == SecurityProfile.Unsecured) {
            platforms[index].radio.ends.sumOf { end -> clearFrames(end).count { it[0] == GRAFT } }
        } else {
            crypto.filterIndexed { other, _ -> other != index }.sumOf { opened ->
                opened.frames().count { it[0] == GRAFT }
            }
        }

    companion object {
        /** The seed of the air; node `i` uses `seed + i`. */
        const val SEED = 49L
        const val FAR = -5_000.0
        const val SPACING = 25.0
        const val AWAY = 1_000.0
        const val SETTLE_MILLIS = 60_000L
        private const val GRAFT: Byte = 0x0C
        private const val CATCH_UP: Byte = 0x0A
    }
}
