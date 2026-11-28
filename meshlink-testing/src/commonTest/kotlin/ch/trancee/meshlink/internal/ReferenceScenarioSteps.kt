@file:OptIn(ExperimentalCoroutinesApi::class, InternalMeshLinkApi::class)

package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.ProximityBucket
import ch.trancee.meshlink.ReceivedMessage
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.SendOptions
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The reference scenarios, one per preset (ADR 0017 decision 6); the Swift fake runs the same titles. */
internal fun StepsBuilder.referenceScenarioSteps() {
    val mesh = ReferenceMesh()
    after { mesh.close() }
    offlineChatSteps(mesh)
    emergencyBroadcastSteps(mesh)
    proximityDiscoverySteps(mesh)
    localMultiplayerSteps(mesh)
    fieldTeamsSteps(mesh)
}

private fun StepsBuilder.offlineChatSteps(mesh: ReferenceMesh) {
    given("Alice, a relay and Bob run OfflineChat in a classical mesh, with the relay between them") {
        mesh.start(Preset.OfflineChat, SecurityProfile.Classical, line(count = 3))
    }

    whenever("Alice sends {string} to Bob and Bob marks it read") { text: String ->
        mesh.send(from = 0, to = 2, text)
        mesh
            .inbox(2)
            .single()
            .first
            .markRead()
        mesh.settle(REFERENCE_SETTLE_MILLIS)
    }

    then("Bob, who is not Alice's neighbor, received {string} from Alice") { text: String ->
        val (message, _) = mesh.inbox(2).single()
        assertEquals(text, message.payload.decodeToString())
        assertEquals(mesh.id(0), message.sender)
        assertTrue(
            mesh.nodes[0]
                .peers.value
                .none { it.nodeId == mesh.id(2) },
        )
    }

    then("Alice's message {string} went from Delivered to Read") { text: String ->
        assertEquals(listOf(DeliveryStatus.Delivered, DeliveryStatus.Read), mesh.statuses.getValue(text).takeLast(2))
    }
}

private fun StepsBuilder.emergencyBroadcastSteps(mesh: ReferenceMesh) {
    given("five nodes run EmergencyBroadcast in a classical line") {
        mesh.start(Preset.EmergencyBroadcast, SecurityProfile.Classical, line(count = 5))
    }

    whenever("the first node broadcasts {string} with a hop limit of {int}") { text: String, hopLimit: Int ->
        assertIs<SendResult.Accepted>(mesh.nodes[0].broadcast(text.encodeToByteArray(), hopLimit))
        mesh.settle(REFERENCE_SETTLE_MILLIS)
    }

    then("the next three nodes received {string} from the first node, and the fifth received nothing") { text: String ->
        (1..3).forEach { index ->
            val (message, _) = mesh.inbox(index).single()
            assertEquals(text, message.payload.decodeToString())
            assertEquals(mesh.id(0), message.sender)
            assertTrue(message.broadcast)
        }
        assertEquals(emptyList(), mesh.inbox(4))
    }
}

private fun StepsBuilder.proximityDiscoverySteps(mesh: ReferenceMesh) {
    given("three nodes run ProximityDiscovery, at {int}, {int} and {int} dBm from where Alice will be") {
        close: Int,
        room: Int,
        distant: Int,
        ->
        mesh.start(Preset.ProximityDiscovery, SecurityProfile.Classical, List(4) { 0.0 to 0.0 }, started = 1..3)
        listOf(close, room, distant).forEachIndexed { index, dBm -> mesh.setRssi(0, index + 1, dBm) }
    }

    whenever("Alice starts ProximityDiscovery among them") {
        mesh.nodes[0].start()
        mesh.settle(REFERENCE_SETTLE_MILLIS)
    }

    then("Alice sees those neighbors {word}, {word} and {word}") { close: String, room: String, distant: String ->
        val expected = listOf(close, room, distant).map(ProximityBucket::valueOf)
        assertEquals(expected, (1..3).map { mesh.peer(of = 0, index = it).proximity?.bucket })
    }
}

private fun StepsBuilder.localMultiplayerSteps(mesh: ReferenceMesh) {
    given("four players run the custom preset {string}, based on LocalMultiplayer, all in range of each other") { name: String ->
        val arena = Preset.Custom(name, Preset.LocalMultiplayer) { messageLifetimeSeconds = ARENA_LIFETIME_SECONDS }
        mesh.start(arena, SecurityProfile.Classical, List(PLAYERS) { 0.0 to 0.0 })
    }

    whenever("every player sends a move directly to every other player") {
        mesh.sentAtMillis = mesh.nowMillis()
        mesh.nodes.indices.forEach { from ->
            mesh.nodes.indices.filter { it != from }.forEach { to ->
                val move = byteArrayOf(from.toByte(), to.toByte())
                assertIs<SendResult.Accepted>(mesh.nodes[from].send(mesh.id(to), move, SendOptions(directOnly = true)))
            }
        }
        mesh.settle(REFERENCE_SETTLE_MILLIS)
    }

    then("every player received a move from each of the {int} others within {int} second") { others: Int, seconds: Int ->
        mesh.nodes.indices.forEach { player ->
            val inbox = mesh.inbox(player)
            assertEquals((mesh.nodes.indices - player).map(mesh::id).toSet(), inbox.map { it.first.sender }.toSet())
            assertEquals(others, inbox.size)
            inbox.forEach { (_, atMillis) -> assertTrue(atMillis - mesh.sentAtMillis <= seconds * MILLIS_PER_SECOND) }
        }
    }
}

private fun StepsBuilder.fieldTeamsSteps(mesh: ReferenceMesh) {
    given(
        "the base and a scout run FieldTeams in a post-quantum mesh, and the base sent {string} to the scout " +
            "while the scout was away",
    ) { text: String ->
        mesh.start(Preset.FieldTeams, SecurityProfile.PostQuantum, line(count = 2))
        val scout = mesh.id(1)
        mesh.moveAway(1)
        mesh.settle(REFERENCE_SETTLE_MILLIS)
        mesh.send(from = 0, to = scout, text)
    }

    whenever("the scout returns") {
        mesh.moveBack(1, x = SPACING)
        mesh.settle(REFERENCE_SETTLE_MILLIS)
    }

    whenever("the hour-long lifetime of the message passes") {
        mesh.settle(FIELD_TEAMS_EXPIRED_MILLIS)
    }

    then("the scout received {string}, and the base's message was Held, then Sent and Delivered") { text: String ->
        assertEquals(listOf(text), mesh.inbox(1).map { it.first.payload.decodeToString() })
        val expected = listOf(DeliveryStatus.Held, DeliveryStatus.Sent, DeliveryStatus.Delivered)
        assertEquals(expected, mesh.statuses.getValue(text).takeLast(3))
    }

    then("the scout received nothing, and the base's message went from Held to Expired") {
        assertEquals(emptyList(), mesh.inbox(1))
        val history = mesh.statuses.values.single()
        assertEquals(listOf(DeliveryStatus.Held, DeliveryStatus.Expired), history.takeLast(2))
    }
}

private fun line(count: Int): List<Pair<Double, Double>> = List(count) { it * SPACING to 0.0 }

/** The nodes of one reference scenario on seeded air, what each received and when, and each sent message's statuses. */
private class ReferenceMesh {
    private lateinit var scope: TestScope
    private lateinit var air: VirtualAir
    private var scopeInitialized = false
    private val platforms = mutableListOf<VirtualPlatform>()
    private val inboxes = mutableListOf<MutableList<Pair<ReceivedMessage, Long>>>()
    val nodes = mutableListOf<MeshLink>()

    /** The statuses of each sent message, by its text, in the order they changed. */
    val statuses = mutableMapOf<String, MutableList<DeliveryStatus>>()
    var sentAtMillis = 0L

    /** Starts nodes of [preset] in [profile] at [positions]; only those in [started] start now. */
    suspend fun start(
        preset: Preset,
        profile: SecurityProfile,
        positions: List<Pair<Double, Double>>,
        started: IntRange = positions.indices,
    ) {
        scope = TestScope(currentCoroutineContext()[ContinuationInterceptor] as TestDispatcher)
        scopeInitialized = true
        air = VirtualAir(scope.backgroundScope, TestClock(scope.testScheduler), SeededRandom(SEED))
        val config = meshLinkConfig(preset, profile) { meshId = MESH }.build()
        positions.forEachIndexed { index, (x, y) ->
            val platform = air.platform(StandardTestDispatcher(scope.testScheduler))
            air.move(platform.radio, x, y)
            platforms += platform
            val node = MeshLink.create(config, MeshPlatform.of(platform, SeededCrypto(SEED + index)))
            nodes += node
            inboxes +=
                mutableListOf<Pair<ReceivedMessage, Long>>().also { inbox ->
                    scope.backgroundScope.launch { node.messages.collect { inbox += it to nowMillis() } }
                }
        }
        started.forEach { nodes[it].start() }
        settle(REFERENCE_SETTLE_MILLIS)
    }

    fun nowMillis(): Long = scope.testScheduler.currentTime

    fun settle(millis: Long) = scope.testScheduler.advanceTimeBy(millis)

    fun inbox(index: Int): List<Pair<ReceivedMessage, Long>> = inboxes[index]

    fun setRssi(
        index: Int,
        other: Int,
        dBm: Int,
    ) = air.setRssi(platforms[index].radio, platforms[other].radio, dBm)

    fun moveAway(index: Int) = air.move(platforms[index].radio, AWAY, 0.0)

    fun moveBack(
        index: Int,
        x: Double,
    ) = air.move(platforms[index].radio, x, 0.0)

    /** The NodeId of node [index], as the nodes that see it know it. */
    fun id(index: Int): NodeId =
        nodes.firstNotNullOf { node ->
            node.peers.value
                .firstOrNull { it.nodeId.toByteArray().contentEquals(nodeId(platforms[index])) }
                ?.nodeId
        }

    /** Node [index] as node [of] sees it. */
    fun peer(
        of: Int,
        index: Int,
    ) = nodes[of].peers.value.single { it.nodeId == id(index) }

    fun send(
        from: Int,
        to: Int,
        text: String,
    ) = send(from, id(to), text)

    /** Node [from] sends [text] to [to], recording each status; then the mesh settles. */
    fun send(
        from: Int,
        to: NodeId,
        text: String,
    ) {
        val message = assertIs<SendResult.Accepted>(nodes[from].send(to, text.encodeToByteArray())).message
        val history = mutableListOf<DeliveryStatus>().also { statuses[text] = it }
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            message.status.collect { history += it }
        }
        settle(REFERENCE_SETTLE_MILLIS)
    }

    fun close() {
        nodes.forEach { it.close() }
        if (scopeInitialized) scope.backgroundScope.cancel()
    }
}

private const val SEED = 55L
private const val SPACING = 25.0
private const val AWAY = 1_000.0
private const val REFERENCE_SETTLE_MILLIS = 60_000L
private const val PLAYERS = 4
private const val ARENA_LIFETIME_SECONDS = 120
private const val MILLIS_PER_SECOND = 1_000L

// FieldTeams' one-hour lifetime and the 30-second clock-skew allowance, with a second to spare.
private const val FIELD_TEAMS_EXPIRED_MILLIS = 60 * 60_000L + 31_000L
