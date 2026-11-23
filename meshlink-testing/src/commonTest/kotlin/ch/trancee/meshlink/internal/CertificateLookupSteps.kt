package ch.trancee.meshlink.internal

import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.meshLinkConfig
import io.github.menjoo.cucumberkmp.core.runner.StepsBuilder
import io.github.menjoo.cucumberkmp.core.runner.given
import io.github.menjoo.cucumberkmp.core.runner.then
import io.github.menjoo.cucumberkmp.core.runner.whenever
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Certificate lookup on seeded virtual air (spec "Certificate lookup"); each node records the
 * frames its links carried to it.
 */
internal fun StepsBuilder.certificateLookupSteps() {
    val line = LookupLine()
    after { line.close() }
    lookupGivens(line)
    lookupThens(line)
}

private fun StepsBuilder.lookupGivens(line: LookupLine) {
    given("Alice to Frank are running in a {word} line with links only between neighbors") { name: String ->
        line.profile = if (name == "post-quantum") SecurityProfile.PostQuantum else SecurityProfile.Classical
        line.start(List(LINE) { it * SPACING to 0.0 }, List(LINE) { null })
        line.nodes.forEachIndexed { index, node ->
            assertEquals(listOf(index - 1, index + 1).count { it in 0 until LINE }, node.peers.value.size)
        }
    }

    given(
        "Alice, Bob and Carol are running in a line in a classical gated mesh, " +
            "and Mallory beside Bob holds another mesh key",
    ) {
        line.start(
            listOf(0.0 to 0.0, SPACING to 0.0, 2 * SPACING to 0.0, SPACING to SPACING),
            listOf(MESH_KEY, MESH_KEY, MESH_KEY, OTHER_KEY),
        )
        assertEquals(listOf(1, 2, 1, 0), line.nodes.map { it.peers.value.size })
    }

    given("Carol has looked up Frank's certificate") {
        line.lookUp(CAROL, FRANK)
        assertNotNull(line.certificate(CAROL, FRANK))
    }

    given("Alice has restarted holding no certificates") {
        line.restart(ALICE)
        assertEquals(null, line.certificate(ALICE, FRANK))
        line.daveRequests = line.received[DAVE].frames().count { it.type == LookupRequest.TYPE }
    }

    whenever("Alice looks up Frank's certificate") { line.lookUp(ALICE, FRANK) }

    whenever("Alice looks up Carol's certificate") { line.lookUp(ALICE, CAROL) }
}

private fun StepsBuilder.lookupThens(line: LookupLine) {
    then("Alice holds the certificate Frank proved to Eve on their link") {
        val proved = assertNotNull(line.certificate(EVE, FRANK))
        assertContentEquals(proved.encode(), line.certificate(ALICE, FRANK)?.encode())
    }

    then("Alice holds the certificate Carol proved to Bob on their link") {
        val proved = assertNotNull(line.certificate(BOB, CAROL))
        assertContentEquals(proved.encode(), line.certificate(ALICE, CAROL)?.encode())
    }

    then("every lookup reply Alice received was {int} bytes") { size: Int ->
        val replies = line.received[ALICE].frames().filter { it.type == LookupReply.TYPE }
        assertTrue(replies.isNotEmpty())
        assertEquals(setOf(size), replies.map { it.size }.toSet())
    }

    then("Frank received no lookup request") {
        assertEquals(emptyList(), line.received[FRANK].frames().filter { it.type == LookupRequest.TYPE })
    }

    then("Dave received no lookup request since Alice restarted") {
        assertEquals(line.daveRequests, line.received[DAVE].frames().count { it.type == LookupRequest.TYPE })
    }

    then("Mallory received no lookup frame") {
        assertEquals(emptyList(), line.received[MALLORY].frames().filter { it.type in LOOKUP_TYPES })
    }

    then("no lookup frame went out in the clear") {
        val lookups =
            line.received
                .flatMap { it.frames() }
                .filter { it.type in LOOKUP_TYPES }
                .map { it.toHexString() }
        val bodies =
            line.platforms
                .flatMap { it.radio.ends }
                .flatMap(::bodies)
                .map { it.toHexString() }
        assertTrue(lookups.isNotEmpty())
        assertTrue(lookups.none { frame -> bodies.any { frame in it } }, "a lookup frame went out in the clear")
    }
}

/** A scenario's nodes on one stretch of virtual air. */
@OptIn(ExperimentalCoroutinesApi::class, InternalMeshLinkApi::class)
private class LookupLine {
    private lateinit var scope: TestScope
    private var scopeInitialized = false
    val platforms = mutableListOf<VirtualPlatform>()
    val nodes = mutableListOf<MeshLink>()
    val received = mutableListOf<OpenedFrames>()
    var profile: SecurityProfile = SecurityProfile.Classical
    var daveRequests = 0
    private var meshKey: ByteArray? = null

    fun close() {
        nodes.forEach { it.close() }
        if (scopeInitialized) scope.backgroundScope.cancel()
    }

    private fun create(index: Int): MeshLink {
        val crypto = OpenedFrames(SeededCrypto(SEED + index))
        val node =
            MeshLink.create(
                meshLinkConfig(Preset.OfflineChat, profile) {
                    meshId = MESH
                    this.meshKey = this@LookupLine.meshKey
                }.build(),
                MeshPlatform.of(platforms[index], crypto),
            )
        if (index < nodes.size) {
            nodes[index] = node
            received[index] = crypto
        } else {
            nodes += node
            received += crypto
        }
        return node
    }

    suspend fun start(
        positions: List<Pair<Double, Double>>,
        keys: List<ByteArray?>,
    ) {
        scope = TestScope(currentCoroutineContext()[ContinuationInterceptor] as TestDispatcher)
        scopeInitialized = true
        val air = VirtualAir(scope.backgroundScope, TestClock(scope.testScheduler), SeededRandom(SEED))
        positions.forEachIndexed { index, (x, y) ->
            platforms += air.platform(StandardTestDispatcher(scope.testScheduler)).also { air.move(it.radio, x, y) }
            meshKey = keys[index]
            create(index).start()
        }
        scope.testScheduler.advanceTimeBy(LINKED_MILLIS)
    }

    suspend fun restart(index: Int) {
        nodes[index].close()
        create(index).start()
        scope.testScheduler.advanceTimeBy(LINKED_MILLIS)
    }

    // As a linked neighbor knows it.
    private fun id(index: Int): NodeId =
        nodes.firstNotNullOf { node ->
            node.peers.value
                .firstOrNull { it.nodeId.toByteArray().contentEquals(nodeId(platforms[index])) }
                ?.nodeId
        }

    private fun lookups(index: Int) = checkNotNull(nodes[index].certificateLookups)

    fun lookUp(
        requester: Int,
        target: Int,
    ) {
        lookups(requester).lookUp(id(target))
        scope.testScheduler.advanceTimeBy(LOOKUP_MILLIS)
    }

    suspend fun certificate(
        holder: Int,
        target: Int,
    ) = lookups(holder).certificateOf(id(target))
}

/**
 * A node's crypto that keeps every plaintext it opens, by key: on a link, each key carries one
 * direction of the link's records.
 */
private class OpenedFrames(
    private val crypto: CryptoProvider,
) : CryptoProvider by crypto {
    private val opened = mutableListOf<Pair<List<Byte>, ByteArray>>()

    override fun chaCha20Poly1305Open(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): CryptoResult<ByteArray> =
        crypto.chaCha20Poly1305Open(key, nonce, ciphertext, associatedData).also {
            if (it is CryptoResult.Ok) opened += key.toList() to it.value
        }

    /** Every frame opened so far, each reassembled from its `FRAGMENT`s on its key. */
    fun frames(): List<ByteArray> =
        opened.groupBy({ it.first }, { it.second }).values.flatMap { bodies ->
            val streams = mutableMapOf<Int, ByteArray>()
            bodies.mapNotNull { body ->
                val fragment = (LinkFrame.decode(body) as? Decoded.Valid)?.value as? LinkFrame.Fragment
                val data = fragment?.let { streams.getOrElse(it.streamId) { ByteArray(0) } + it.data }
                when {
                    fragment == null -> body
                    fragment.last -> data.also { streams -= fragment.streamId }
                    else -> null.also { streams[fragment.streamId] = data!! }
                }
            }
        }
}

private val ByteArray.type: Int get() = if (isEmpty()) -1 else this[0].toInt() and 0xFF

private val LOOKUP_TYPES = setOf(LookupRequest.TYPE, LookupReply.TYPE)
private const val SEED = 44L
private const val LINE = 6
private const val SPACING = 25.0
private const val ALICE = 0
private const val BOB = 1
private const val CAROL = 2
private const val DAVE = 3
private const val EVE = 4
private const val FRANK = 5
private const val MALLORY = 3
private const val LINKED_MILLIS = 60_000L

// Past the first request's answer on the line, well within the first retry.
private const val LOOKUP_MILLIS = 1_500L
private val MESH_KEY = ByteArray(32) { 1 }
private val OTHER_KEY = ByteArray(32) { 2 }
