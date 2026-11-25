package ch.trancee.meshlink.internal

import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshState
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.Requirement
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
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
internal fun StepsBuilder.identityAvailabilitySteps() {
    lateinit var scope: TestScope
    var scopeInitialized = false
    lateinit var identity: AvailableIdentity
    lateinit var original: NodeId
    val nodes = mutableListOf<MeshLink>()

    after {
        nodes.forEach { it.close() }
        if (scopeInitialized) scope.backgroundScope.cancel()
    }

    given("Alice and Bob are linked and Alice's identity can become unavailable") {
        val dispatcher = currentCoroutineContext()[ContinuationInterceptor] as TestDispatcher
        scope = TestScope(dispatcher)
        scopeInitialized = true
        val air = scope.air()
        val alice = air.platform(StandardTestDispatcher(scope.testScheduler))
        val bob = air.platform(StandardTestDispatcher(scope.testScheduler))
        identity = AvailableIdentity(alice.identityStore)
        val alicePort =
            object : PlatformPort by alice {
                override val identityStore: IdentityStore = identity
            }
        nodes += node(alicePort)
        nodes += node(bob)
        nodes.forEach { it.start() }
        scope.testScheduler.advanceTimeBy(SETTLE_MILLIS)
        original =
            nodes[1]
                .peers.value
                .single()
                .nodeId
    }

    whenever("Alice restarts with her identity unavailable") {
        nodes[0].stop()
        identity.available = false
        nodes[0].start()
    }

    then("Alice is blocked on identity availability") {
        assertEquals(MeshState.Blocked(setOf(Requirement.IdentityUnavailable)), nodes[0].state.value)
    }

    whenever("Alice's identity becomes available and she retries starting") {
        identity.available = true
        nodes[0].start()
        scope.testScheduler.advanceTimeBy(60_000L)
    }

    then("Bob recognizes Alice's original identity") {
        assertEquals(MeshState.Running(emptySet()), nodes[0].state.value)
        assertEquals(
            original,
            nodes[1]
                .peers.value
                .single()
                .nodeId,
        )
    }
}

private class AvailableIdentity(
    private val delegate: IdentityStore,
) : IdentityStore {
    var available = true

    override fun loadOrCreate(crypto: CryptoProvider): Ed25519KeyPair? {
        if (!available) return null
        return delegate.loadOrCreate(crypto)
    }

    override fun delete(): Boolean = delegate.delete()
}
