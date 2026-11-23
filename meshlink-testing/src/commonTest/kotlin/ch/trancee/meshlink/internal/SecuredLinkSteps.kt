package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.Preset
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A secured link between two nodes on seeded virtual air across a bearer switch and hourly rekeys
 * (spec "Bearer switch", "Rekey"): Alice opens the one link, as Bob doesn't scan.
 */
internal fun StepsBuilder.securedLinkSteps() {
    val pair = SecuredPair()
    after { pair.close() }

    given(
        "Alice and Bob are linked in a {word} mesh, " +
            "Alice's L2CAP channel to Bob opening {int} seconds after the link",
    ) {
        name: String,
        seconds: Int,
        ->
        pair.start(name, channelDelayMillis = seconds * MILLIS_PER_SECOND)
    }

    given("Alice and Bob are linked in a {word} mesh") { name: String -> pair.start(name) }

    whenever(
        "Alice sends a message to Bob before the channel opens, " +
            "and {int} more once the link moved to it",
    ) { more: Int ->
        assertTrue(pair.aliceEnd().channels.isEmpty())
        pair.send(DELIVERED_MILLIS)
        while (pair.aliceChannel()?.written?.isNotEmpty() != true) pair.advance(1)
        pair.advance(DELIVERED_MILLIS)
        pair.gattMark = pair.aliceEnd().written.size
        pair.channelMark = pair.aliceChannel()!!.written.size
        repeat(more) { pair.send(DELIVERED_MILLIS) }
    }

    whenever(
        "Alice sends a message to Bob, " +
            "and one more a minute after each of the next {int} hourly rekeys",
    ) { hours: Int ->
        val linkedAt = pair.now()
        pair.send(DELIVERED_MILLIS)
        repeat(hours) { hour ->
            pair.advance(linkedAt + (hour + 1) * LinkCipher.REKEY_INTERVAL_MILLIS + MINUTE_MILLIS - pair.now())
            pair.send(DELIVERED_MILLIS)
        }
    }

    then("Bob received Alice's {int} messages once each, in order, and each was Delivered") { count: Int ->
        val texts = List(count) { "Message ${it + 1}" }
        assertEquals(texts, pair.received.map { it.first })
        assertEquals(texts.map { DeliveryStatus.Delivered }, texts.map { pair.statuses.getValue(it).last() })
    }

    then(
        "Bob received the first while the link was on GATT, " +
            "and Alice wrote the other {int} on the L2CAP channel",
    ) { others: Int ->
        assertEquals(listOf(true) + List(others) { false }, pair.received.map { it.second })
        assertEquals(pair.gattMark, pair.aliceEnd().written.size, "Alice's GATT bytes after the switch")
        assertTrue(pair.aliceChannel()!!.written.size > pair.channelMark)
    }

    then("Alice and Bob each rekeyed their direction of the link {int} times, and the link stayed open") { times: Int ->
        assertEquals(listOf(times, times), pair.crypto.map { it.rekeys() })
        assertEquals(listOf(1, 1), pair.nodes.map { it.peers.value.size })
        assertTrue(pair.aliceEnd().open)
    }
}

/** Alice and Bob on one stretch of seeded virtual air, with Bob's inbox and each sent message's statuses. */
@OptIn(ExperimentalCoroutinesApi::class, InternalMeshLinkApi::class)
private class SecuredPair {
    private lateinit var scope: TestScope
    private var scopeInitialized = false
    private val platforms = mutableListOf<VirtualPlatform>()
    val nodes = mutableListOf<MeshLink>()
    val crypto = mutableListOf<OpenedFrames>()

    /** Each message Bob received, and whether Alice's link then had no channel yet. */
    val received = mutableListOf<Pair<String, Boolean>>()

    /** The statuses of each sent message, by its text. */
    val statuses = mutableMapOf<String, MutableList<DeliveryStatus>>()

    /** How many bytes Alice had written on GATT and on her channel once the link moved to it. */
    var gattMark = 0
    var channelMark = 0

    fun close() {
        nodes.forEach { it.close() }
        if (scopeInitialized) scope.backgroundScope.cancel()
    }

    /** Starts Alice and Bob in the profile [name], Alice's channels opening [channelDelayMillis] late. */
    suspend fun start(
        name: String,
        channelDelayMillis: Long = 0,
    ) {
        scope = TestScope(currentCoroutineContext()[ContinuationInterceptor] as TestDispatcher)
        scopeInitialized = true
        val air = VirtualAir(scope.backgroundScope, TestClock(scope.testScheduler), SeededRandom(SEED))
        val profile = if (name == "post-quantum") SecurityProfile.PostQuantum else SecurityProfile.Classical
        repeat(2) { platforms += air.platform(StandardTestDispatcher(scope.testScheduler), coc = true) }
        val alice = platforms[ALICE].radio
        val bob = platforms[BOB].radio
        val ports =
            listOf(
                platformWith(platforms[ALICE], SlowChannels(alice, channelDelayMillis)),
                platformWith(
                    platforms[BOB],
                    object : RadioPort by bob {
                        override fun startScanning() = RadioResult.Ok(Unit)
                    },
                ),
            )
        ports.forEachIndexed { index, port ->
            val opened = OpenedFrames(SeededCrypto(SEED + index))
            val config = meshLinkConfig(Preset.ProximityDiscovery, profile) { meshId = SECURED_LINKS_MESH }.build()
            nodes += MeshLink.create(config, MeshPlatform.of(port, opened))
            crypto += opened
        }
        scope.backgroundScope.launch {
            nodes[BOB].messages.collect { received += it.payload.decodeToString() to aliceEnd().channels.isEmpty() }
        }
        nodes.forEach { it.start() }
        while (nodes.any { it.peers.value.isEmpty() }) advance(1)
    }

    fun now(): Long = scope.testScheduler.currentTime

    fun advance(millis: Long) = scope.testScheduler.advanceTimeBy(millis)

    /** Alice's end of the link. */
    fun aliceEnd(): VirtualLinkEnd = platforms[ALICE].radio.ends.single()

    /** Alice's end of the link's channel, once it opened. */
    fun aliceChannel(): VirtualChannelEnd? = aliceEnd().channels.singleOrNull()

    /** Alice sends Bob the next numbered message, recording its statuses; then [settleMillis] pass. */
    fun send(settleMillis: Long) {
        val text = "Message ${statuses.size + 1}"
        val bob =
            nodes[ALICE]
                .peers.value
                .single()
                .nodeId
        val message = assertIs<SendResult.Accepted>(nodes[ALICE].send(bob, text.encodeToByteArray())).message
        val history = mutableListOf<DeliveryStatus>().also { statuses[text] = it }
        scope.backgroundScope.launch { message.status.collect { history += it } }
        advance(settleMillis)
    }
}

/** [radio], whose links open each channel [delayMillis] after asked to. */
private class SlowChannels(
    private val radio: RadioPort,
    private val delayMillis: Long,
) : RadioPort by radio {
    override suspend fun connect(peer: PeerHandle): RadioResult<CentralLink> =
        when (val result = radio.connect(peer)) {
            is RadioResult.Ok -> {
                val link = result.value
                RadioResult.Ok(
                    object : CentralLink by link {
                        override suspend fun openChannel(psm: Int): RadioResult<RadioStream> {
                            delay(delayMillis)
                            return link.openChannel(psm)
                        }
                    },
                )
            }

            is RadioResult.Failed -> {
                result
            }
        }
}

/** How many `REKEY` frames this node opened: its peer's rekeys of the direction toward it. */
private fun OpenedFrames.rekeys(): Int = frames().count { it.contentEquals(LinkFrame.Rekey.encode()) }

private const val SEED = 46L
private const val ALICE = 0
private const val BOB = 1
private const val SECURED_LINKS_MESH = "secured-links"
private const val MILLIS_PER_SECOND = 1_000L
private const val MINUTE_MILLIS = 60_000L

// A first message and its answer, or a session frame and its receipt, on one hop.
private const val DELIVERED_MILLIS = 10_000L
