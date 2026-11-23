package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.Reachability
import ch.trancee.meshlink.ReceivedMessage
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.SendResult
import ch.trancee.meshlink.meshLinkConfig
import io.github.menjoo.cucumberkmp.core.runner.StepsBuilder
import io.github.menjoo.cucumberkmp.core.runner.given
import io.github.menjoo.cucumberkmp.core.runner.then
import io.github.menjoo.cucumberkmp.core.runner.whenever
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Secured sends on seeded virtual air (spec "First messages"): Alice to Eve in a line, each node
 * recording the frames its links carried to it and the messages it received.
 */
internal fun StepsBuilder.securedMessagingSteps() {
    val line = SecuredLine()
    after { line.close() }
    securedGivens(line)
    securedWhens(line)
    securedThens(line)
}

private fun StepsBuilder.securedGivens(line: SecuredLine) {
    given("Alice to Eve are running in a {word} line with links only between neighbors") { name: String ->
        line.start(if (name == "post-quantum") SecurityProfile.PostQuantum else SecurityProfile.Classical)
        line.nodes.forEachIndexed { index, node ->
            assertEquals(listOf(index - 1, index + 1).count { it in 0 until LENGTH }, node.peers.value.size)
        }
    }

    given("Alice is in the medium density class") { line.medium() }

    given("Alice holds Eve's certificate, has no route to Eve, and does not list Eve as a peer") {
        line.lookUp(EVE)
        assertNotNull(line.certificate(EVE))
        assertNull(
            line.nodes[ALICE]
                .peers.value
                .firstOrNull { it.nodeId == line.id(EVE) },
        )
    }
}

private fun StepsBuilder.securedWhens(line: SecuredLine) {
    whenever("Alice sends {string} to Eve, holding no certificate of Eve") { text: String ->
        assertNull(line.certificate(EVE))
        line.send(line.id(EVE), text)
    }

    whenever("Alice sends {string} to a node of another mesh") { text: String -> line.send(line.outsider(), text) }

    whenever("Alice sends {string} to Eve") { text: String -> line.send(line.id(EVE), text) }

    whenever(
        "Alice sends {string} to a node of another mesh, and then {string} to Eve",
    ) { first: String, second: String ->
        line.send(line.outsider(), first)
        line.send(line.id(EVE), second)
    }
}

private fun StepsBuilder.securedThens(line: SecuredLine) {
    then("Alice's message stayed Queued until Alice held Eve's certificate, and was then Sent") {
        assertEquals(listOf(DeliveryStatus.Queued to false, DeliveryStatus.Sent to true), line.statuses)
    }

    then("Alice's message stays Queued until 30 seconds after its expiry, and is then Expired") {
        val lifetimeMillis = line.nodes[ALICE].config.messageLifetimeSeconds * MILLIS_PER_SECOND
        line.settle(lifetimeMillis + SKEW_MILLIS)
        // The expiry is in whole seconds, rounded down from the moment of the send.
        val expiry = line.sentAtMillis / MILLIS_PER_SECOND * MILLIS_PER_SECOND + lifetimeMillis
        val expected =
            listOf(DeliveryStatus.Queued to line.sentAtMillis, DeliveryStatus.Expired to expiry + SKEW_MILLIS)
        assertEquals(expected, line.changes)
    }

    then("Eve receives {string} from Alice once") { text: String ->
        val received = line.inboxes[EVE].map { it.sender to it.payload.decodeToString() }
        assertEquals(listOf(line.id(ALICE) to text), received)
    }

    then("Bob, Carol and Dave relayed the message only as {int}-byte frames without its text") { size: Int ->
        val text = line.sent.encodeToByteArray().toHexString()
        listOf(BOB, CAROL, DAVE).forEach { relay ->
            val messages = line.received[relay].frames().filter { it.isMessage() }
            assertTrue(messages.isNotEmpty(), "relay ${'A' + relay}")
            assertEquals(setOf(size), messages.map { it.size }.toSet(), "relay ${'A' + relay}")
            assertTrue(messages.none { text in it.toHexString() }, "relay ${'A' + relay} saw the text")
        }
    }

    then("Alice's message {string} is still Queued") { text: String ->
        assertEquals(DeliveryStatus.Queued, line.latest.getValue(text))
    }

    then("Alice lists Eve as a routed peer {int} hops away") { hops: Int ->
        val eve =
            line.nodes[ALICE]
                .peers.value
                .single { it.nodeId == line.id(EVE) }
        assertEquals(Reachability.Routed(hops), eve.reachability)
        assertTrue(eve.secured)
    }
}

/** A scenario's line of five secured nodes on one stretch of virtual air. */
@OptIn(ExperimentalCoroutinesApi::class, InternalMeshLinkApi::class)
private class SecuredLine {
    private lateinit var scope: TestScope
    private lateinit var air: VirtualAir
    private var scopeInitialized = false
    private val platforms = mutableListOf<VirtualPlatform>()
    private var profile: SecurityProfile = SecurityProfile.Classical
    val nodes = mutableListOf<MeshLink>()
    val received = mutableListOf<OpenedFrames>()
    val inboxes = mutableListOf<MutableList<ReceivedMessage>>()

    /** The status of the sent message at each change, and whether Alice then held Eve's certificate. */
    val statuses = mutableListOf<Pair<DeliveryStatus, Boolean>>()

    /** The status of the sent message at each change, and when on the wall clock it changed. */
    val changes = mutableListOf<Pair<DeliveryStatus, Long>>()
    var sent = ""

    /** The latest status of each sent message, by its text. */
    val latest = mutableMapOf<String, DeliveryStatus>()
    var sentAtMillis = 0L

    fun close() {
        nodes.forEach { it.close() }
        if (scopeInitialized) scope.backgroundScope.cancel()
    }

    suspend fun start(profile: SecurityProfile) {
        scope = TestScope(currentCoroutineContext()[ContinuationInterceptor] as TestDispatcher)
        scopeInitialized = true
        air = VirtualAir(scope.backgroundScope, TestClock(scope.testScheduler), SeededRandom(SEED))
        this.profile = profile
        repeat(LENGTH) { index ->
            val platform = air.platform(StandardTestDispatcher(scope.testScheduler))
            air.move(platform.radio, index * SPACING, 0.0)
            platforms += platform
            val crypto = OpenedFrames(SeededCrypto(SEED + index))
            val node = create(platform, crypto, MESH)
            nodes += node
            received += crypto
            inboxes +=
                mutableListOf<ReceivedMessage>().also { inbox ->
                    scope.backgroundScope.launch { node.messages.collect { inbox += it } }
                }
            node.start()
        }
        scope.testScheduler.advanceTimeBy(LINKED_MILLIS)
    }

    private fun create(
        platform: VirtualPlatform,
        crypto: CryptoProvider,
        meshId: String,
    ): MeshLink {
        val config = meshLinkConfig(Preset.ProximityDiscovery, profile) { this.meshId = meshId }.build()
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
                create(platform, SeededCrypto(SEED + LENGTH + index), ELSEWHERE).also { nodes += it }
            }
        pair.forEach { it.start() }
        scope.testScheduler.advanceTimeBy(LINKED_MILLIS)
        return pair[0]
            .peers.value
            .single()
            .nodeId
    }

    /** Thirteen advertisers beside Alice alone move her node up to the medium class. */
    fun medium() {
        val profile = WireProfile.CLASSICAL
        repeat(MEDIUM_UP_HEARD) {
            air.move(advertiseFake(air, capability = null, profile = profile), -FAKE_DISTANCE, 0.0)
        }
        scope.testScheduler.advanceTimeBy(SWITCH_MILLIS)
    }

    // As a linked neighbor knows it.
    fun id(index: Int): NodeId =
        nodes.firstNotNullOf { node ->
            node.peers.value
                .firstOrNull { it.nodeId.toByteArray().contentEquals(nodeId(platforms[index])) }
                ?.nodeId
        }

    private fun lookups() = checkNotNull(nodes[ALICE].certificateLookups)

    fun lookUp(target: Int) {
        lookups().lookUp(id(target))
        scope.testScheduler.advanceTimeBy(LOOKUP_MILLIS)
    }

    suspend fun certificate(target: Int) = lookups().certificateOf(id(target))

    fun send(
        to: NodeId,
        text: String,
    ) {
        sent = text
        sentAtMillis = TestClock(scope.testScheduler).wallClockMillis()
        val message = assertIs<SendResult.Accepted>(nodes[ALICE].send(to, text.encodeToByteArray())).message
        scope.backgroundScope.launch {
            message.status.collect { status ->
                val held = lookups().certificateOf(to) != null
                statuses += status to held
                latest[text] = status
                changes += status to TestClock(scope.testScheduler).wallClockMillis()
            }
        }
        scope.testScheduler.advanceTimeBy(DELIVERED_MILLIS)
    }

    fun settle(millis: Long) = scope.testScheduler.advanceTimeBy(millis)
}

private fun ByteArray.isMessage(): Boolean =
    (UnicastFrame.decode(this) as? Decoded.Valid)?.let { it.value.type == MessageFrame.TYPE } == true

private const val SEED = 45L
private const val LENGTH = 5
private const val SPACING = 25.0
private const val ALICE = 0
private const val BOB = 1
private const val CAROL = 2
private const val DAVE = 3
private const val EVE = 4
private const val LINKED_MILLIS = 60_000L
private const val LOOKUP_MILLIS = 1_500L
private const val DELIVERED_MILLIS = 5_000L
private const val SKEW_MILLIS = 30_000L
private const val MILLIS_PER_SECOND = 1_000L
private const val MEDIUM_UP_HEARD = 13
private const val FAKE_DISTANCE = 20.0
private const val SWITCH_MILLIS = 120_000L
private const val FAR = 10_000.0
private const val ELSEWHERE = "Elsewhere"
