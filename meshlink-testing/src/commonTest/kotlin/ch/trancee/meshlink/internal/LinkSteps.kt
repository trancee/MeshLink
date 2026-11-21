package ch.trancee.meshlink.internal

import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.Reachability
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

@OptIn(ExperimentalCoroutinesApi::class)
internal fun StepsBuilder.linkSteps() {
    lateinit var scope: TestScope
    lateinit var air: VirtualAir
    var scopeInitialized = false
    val platforms = mutableListOf<VirtualPlatform>()
    val nodes = mutableListOf<MeshLink>()
    var rememberedAlice: NodeId? = null

    after {
        nodes.forEach { it.close() }
        if (scopeInitialized) scope.backgroundScope.cancel()
    }

    fun assertDirectPeers() {
        nodes.forEachIndexed { index, node ->
            assertEquals(1, node.peers.value.size)
            val peer = node.peers.value.single()
            assertContentEquals(nodeId(platforms[1 - index]), peer.nodeId.toByteArray())
            assertEquals(Reachability.Direct, peer.reachability)
        }
    }

    fun moveBobOutOfRange() {
        air.move(platforms[1].radio, VirtualAir.DEFAULT_RANGE + 1, 0.0)
        scope.testScheduler.advanceTimeBy(SETTLE_MILLIS)
    }

    given("Alice and Bob have a direct link") {
        val dispatcher = currentCoroutineContext()[ContinuationInterceptor] as TestDispatcher
        scope = TestScope(dispatcher)
        scopeInitialized = true
        air = scope.air()
        repeat(2) {
            val platform = air.platform(StandardTestDispatcher(scope.testScheduler))
            platforms += platform
            nodes += node(platform)
        }
        nodes.forEach { it.start() }
        scope.testScheduler.advanceTimeBy(SETTLE_MILLIS)
        assertDirectPeers()
    }

    given("Bob is out of range") {
        moveBobOutOfRange()
        nodes.forEach { assertEquals(emptyList(), it.peers.value) }
    }

    fun identitySteps() {
        given("Bob remembers Alice's identity") {
            rememberedAlice =
                nodes[1]
                    .peers.value
                    .single()
                    .nodeId
        }

        whenever("Alice restarts") {
            nodes[0].stop()
            nodes[0].start()
            scope.testScheduler.advanceTimeBy(RECONNECT_WAIT_MILLIS)
        }

        then("Bob sees Alice with the same identity") {
            assertEquals(
                rememberedAlice,
                nodes[1]
                    .peers.value
                    .single()
                    .nodeId,
            )
        }
    }

    identitySteps()

    whenever("Bob moves out of range") {
        moveBobOutOfRange()
    }

    whenever("Bob returns within range") {
        air.move(platforms[1].radio, 0.0, 0.0)
        scope.testScheduler.advanceTimeBy(RECONNECT_WAIT_MILLIS)
    }

    then("neither node lists the other as a peer") {
        nodes.forEach { assertEquals(emptyList(), it.peers.value) }
    }

    then("Alice and Bob again list each other as direct peers") {
        assertDirectPeers()
    }
}

private const val RECONNECT_WAIT_MILLIS = 60_000L
