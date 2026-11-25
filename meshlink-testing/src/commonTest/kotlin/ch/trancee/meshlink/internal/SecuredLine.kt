package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshLinkConfigBuilder
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.ReceivedMessage
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.SendOptions
import ch.trancee.meshlink.SendResult
import ch.trancee.meshlink.meshLinkConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.assertIs

/**
 * A scenario's line of [length] nodes of identity seeds from [seed] on one stretch of seeded virtual air,
 * links only between neighbors: each node records the frames its links carried to it, the messages it
 * received and its link diagnostics; each sent message, its statuses.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalMeshLinkApi::class)
internal class SecuredLine(
    private val seed: Long,
    val length: Int = LENGTH,
) {
    /** Sets up the config of node `index`; set before [start]. */
    var configure: MeshLinkConfigBuilder.(index: Int) -> Unit = {}

    /** The crypto node `index` uses, made of its own; set before [start]. */
    var crypto: (index: Int, CryptoProvider) -> CryptoProvider = { _, crypto -> crypto }

    private lateinit var scope: TestScope
    private lateinit var air: VirtualAir
    private var scopeInitialized = false
    private val platforms = mutableListOf<VirtualPlatform>()
    private var profile: SecurityProfile = SecurityProfile.Classical
    val nodes = mutableListOf<MeshLink>()
    val received = mutableListOf<OpenedFrames>()
    val inboxes = mutableListOf<MutableList<ReceivedMessage>>()
    val diagnostics = mutableListOf<MutableList<LinkDiagnostic>>()

    /**
     * The status of the last sent message at each change, and whether Alice then held its destination's
     * certificate.
     */
    val statuses = mutableListOf<Pair<DeliveryStatus, Boolean>>()

    /** The status of the last sent message at each change, and when on the wall clock it changed. */
    val changes = mutableListOf<Pair<DeliveryStatus, Long>>()
    var sent = ""

    /** The statuses of each sent message, by its text. */
    val history = mutableMapOf<String, MutableList<DeliveryStatus>>()

    /** The latest status of each sent message, by its text. */
    val latest: Map<String, DeliveryStatus> get() = history.mapValues { it.value.last() }
    var sentAtMillis = 0L

    fun close() {
        nodes.forEach { it.close() }
        if (scopeInitialized) scope.backgroundScope.cancel()
    }

    /** Starts the line in [profile]; the wall clock of node `i` runs [offsetMillis]`[i]` ahead. */
    suspend fun start(
        profile: SecurityProfile,
        offsetMillis: Map<Int, Long> = emptyMap(),
    ) {
        scope = TestScope(currentCoroutineContext()[ContinuationInterceptor] as TestDispatcher)
        scopeInitialized = true
        air = VirtualAir(scope.backgroundScope, TestClock(scope.testScheduler), SeededRandom(seed))
        this.profile = profile
        repeat(length) { index ->
            val dispatcher = StandardTestDispatcher(scope.testScheduler)
            val platform =
                offsetMillis[index]?.let { offset ->
                    val clock = TestClock(scope.testScheduler, TestClock.WALL_CLOCK_START_MILLIS + offset)
                    VirtualPlatform(air.radio(), clock, SeededRandom(seed + index), dispatcher)
                } ?: air.platform(dispatcher)
            air.move(platform.radio, index * SPACING, 0.0)
            platforms += platform
            val crypto = OpenedFrames(crypto(index, SeededCrypto(seed + index)))
            val node = create(platform, crypto, MESH, index)
            nodes += node
            received += crypto
            inboxes +=
                mutableListOf<ReceivedMessage>().also { inbox ->
                    scope.backgroundScope.launch { node.messages.collect { inbox += it } }
                }
            diagnostics +=
                mutableListOf<LinkDiagnostic>().also { events ->
                    scope.backgroundScope.launch { node.linkDiagnostics.collect { events += it } }
                }
            node.start()
        }
        scope.testScheduler.advanceTimeBy(LINKED_MILLIS)
    }

    private fun create(
        platform: VirtualPlatform,
        crypto: CryptoProvider,
        meshId: String,
        index: Int,
    ): MeshLink {
        val config =
            meshLinkConfig(Preset.ProximityDiscovery, profile) {
                this.meshId = meshId
                configure(index)
            }.build()
        return MeshLink.create(config, MeshPlatform.of(platform, crypto))
    }

    /**
     * The NodeId of a running node of another mesh far from the line, as its own neighbor there knows it: no
     * node of the line holds its certificate.
     */
    suspend fun outsider(): NodeId {
        val pair =
            List(2) { index ->
                val platform = air.platform(StandardTestDispatcher(scope.testScheduler))
                air.move(platform.radio, FAR + index * SPACING, 0.0)
                create(platform, SeededCrypto(seed + length + index), ELSEWHERE, length + index).also { nodes += it }
            }
        pair.forEach { it.start() }
        scope.testScheduler.advanceTimeBy(LINKED_MILLIS)
        return pair[0]
            .peers.value
            .single()
            .nodeId
    }

    /** Thirteen advertisers of the line's profile beside node [index] alone move it up to the medium class. */
    fun medium(index: Int = 0) {
        repeat(MEDIUM_UP_HEARD) {
            val fake = advertiseFake(air, capability = null, profile = wire(profile))
            air.move(fake, index * SPACING, -FAKE_DISTANCE)
        }
        scope.testScheduler.advanceTimeBy(SWITCH_MILLIS)
    }

    /** How many bytes node [from] wrote on its links to node [to] since the wall-clock [sinceMillis]. */
    fun written(
        from: Int,
        to: Int,
        sinceMillis: Long,
    ): Int =
        platforms[from]
            .radio.ends
            .filter { it.remote == platforms[to].radio }
            .flatMap { it.writes }
            .filter { (at, _) -> at >= sinceMillis }
            .sumOf { (_, bytes) -> bytes.size }

    /** The NodeId of node [index], as a linked neighbor knows it. */
    fun id(index: Int): NodeId =
        nodes.firstNotNullOf { node ->
            node.peers.value
                .firstOrNull { it.nodeId.toByteArray().contentEquals(nodeId(platforms[index])) }
                ?.nodeId
        }

    private fun lookups() = checkNotNull(nodes[0].certificateLookups)

    // Whether the first node holds [peer]'s certificate; an `Unsecured` node holds none.
    private suspend fun holds(peer: NodeId) = nodes[0].certificateLookups?.certificateOf(peer) != null

    /** The first node looks up the certificate of node [target]. */
    fun lookUp(target: Int) {
        lookups().lookUp(id(target))
        scope.testScheduler.advanceTimeBy(LOOKUP_MILLIS)
    }

    /** The certificate of node [target] the first node holds, if any. */
    suspend fun certificate(target: Int) = lookups().certificateOf(id(target))

    /**
     * Node [from] sends [text] to [to] with [options], recording its statuses under [text]; then
     * [settleMillis] pass.
     */
    fun send(
        to: NodeId,
        text: String,
        from: Int = 0,
        settleMillis: Long = DELIVERED_MILLIS,
        options: SendOptions = SendOptions(),
    ) {
        sent = text
        sentAtMillis = TestClock(scope.testScheduler).wallClockMillis()
        val message = assertIs<SendResult.Accepted>(nodes[from].send(to, text.encodeToByteArray(), options)).message
        val statuses = mutableListOf<DeliveryStatus>().also { history[text] = it }
        scope.backgroundScope.launch {
            message.status.collect { status ->
                val held = holds(to)
                this@SecuredLine.statuses += status to held
                statuses += status
                changes += status to TestClock(scope.testScheduler).wallClockMillis()
            }
        }
        scope.testScheduler.advanceTimeBy(settleMillis)
    }

    /** Stops node [index], which forgets its sessions, and starts it again; then its links open again. */
    suspend fun restart(index: Int) {
        nodes[index].stop()
        nodes[index].start()
        scope.testScheduler.advanceTimeBy(LINKED_MILLIS)
    }

    fun settle(millis: Long) = scope.testScheduler.advanceTimeBy(millis)

    /** The `MESSAGE` frames the links carried to node [index], in order. */
    fun messages(index: Int): List<MessageFrame> = decoded(index) { MessageFrame.decode(it) }

    /** The `READ_RECEIPT` frames the links carried to node [index], in order. */
    fun reads(index: Int): List<ReadReceipt> = decoded(index) { ReadReceipt.decode(it) }

    /** The `DELIVERY_RECEIPT` frames the links carried to node [index], in order, with their bytes. */
    fun receipts(index: Int): List<Pair<ByteArray, DeliveryReceipt>> =
        received[index].frames().filter { it.type == DeliveryReceipt.TYPE }.mapNotNull { bytes ->
            (DeliveryReceipt.decode(bytes) as? Decoded.Valid)?.let { bytes to it.value }
        }

    private fun <T> decoded(
        index: Int,
        decode: (ByteArray) -> Decoded<T>,
    ): List<T> = received[index].frames().mapNotNull { (decode(it) as? Decoded.Valid)?.value }

    /** A scripted `Classical` peer linked to node [index], which writes any frame to it. */
    suspend fun hostile(index: Int): Hostile {
        val fake = FakePeer(WireProfile.CLASSICAL, TestClock(scope.testScheduler))
        val radio = air.radio().also { air.move(it, index * SPACING, BESIDE) }
        val link = (radio.connect(platforms[index].radio.handle) as RadioResult.Ok).value
        scope.testScheduler.advanceTimeBy(1)
        val transport = scope.initiateGranting(fake, link, platforms[index].radio.ends.last())
        scope.testScheduler.advanceTimeBy(LINKED_MILLIS)
        return Hostile(link, transport)
    }

    /** The [link] of a scripted peer, sealed with [transport]. */
    inner class Hostile(
        private val link: RadioLink,
        private val transport: NoiseTransport,
    ) {
        /**
         * Writes [frame] to the node, then lets the reverse route the node learned to its source tag through this
         * peer expire (spec "Unicast forwarding"; a hostile neighbor can black-hole a route that briefly, threat A3).
         */
        suspend fun write(frame: ByteArray) {
            link.write(seal(transport, frame))
            scope.testScheduler.advanceTimeBy(REVERSE_ROUTE_EXPIRED_MILLIS)
        }
    }

    private companion object {
        const val LENGTH = 5
        const val SPACING = 25.0
        const val LINKED_MILLIS = 60_000L
        const val LOOKUP_MILLIS = 1_500L
        const val DELIVERED_MILLIS = 5_000L
        const val MEDIUM_UP_HEARD = 13
        const val FAKE_DISTANCE = 20.0
        const val SWITCH_MILLIS = 120_000L
        const val FAR = 10_000.0
        const val BESIDE = 10.0

        // reverse-route-timeout, 6 s, and some.
        const val REVERSE_ROUTE_EXPIRED_MILLIS = 10_000L
        const val ELSEWHERE = "Elsewhere"
    }
}

// The wire code of [profile], which the library keeps internal.
private fun wire(profile: SecurityProfile): WireProfile =
    when (profile) {
        SecurityProfile.Unsecured -> WireProfile.UNSECURED
        SecurityProfile.Classical -> WireProfile.CLASSICAL
        SecurityProfile.PostQuantum -> WireProfile.POST_QUANTUM
    }

private val ByteArray.type: Int get() = if (isEmpty()) -1 else this[0].toInt() and 0xFF
