package ch.trancee.meshlink.internal

import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshLinkException
import ch.trancee.meshlink.MeshState
import ch.trancee.meshlink.Requirement
import io.github.menjoo.cucumberkmp.core.runner.StepsBuilder
import io.github.menjoo.cucumberkmp.core.runner.given
import io.github.menjoo.cucumberkmp.core.runner.steps
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
import kotlin.test.assertNotNull

internal val acceptanceSteps =
    steps {
        directMessageSteps()
        lifecycleSteps()
        discoverySteps()
        linkSteps()
    }

@OptIn(ExperimentalCoroutinesApi::class)
internal fun StepsBuilder.lifecycleSteps() {
    lateinit var scope: TestScope
    lateinit var platform: VirtualPlatform
    var scopeInitialized = false
    var startFailure: MeshLinkException.Closed? = null
    val nodes = mutableListOf<MeshLink>()

    after {
        nodes.forEach { it.close() }
        if (scopeInitialized) scope.backgroundScope.cancel()
    }

    suspend fun createNode(): MeshLink {
        val dispatcher = currentCoroutineContext()[ContinuationInterceptor] as TestDispatcher
        scope = TestScope(dispatcher)
        scopeInitialized = true
        platform = scope.air().platform(StandardTestDispatcher(scope.testScheduler))
        return node(platform).also { nodes += it }
    }

    given("a stopped node") {
        createNode()
    }

    given("a closed node") {
        createNode().close()
    }

    given("a running node") {
        createNode().start()
    }

    given("the node has stopped") {
        nodes.single().stop()
        assertEquals(MeshState.Stopped, nodes.single().state.value)
    }

    given("a node blocked because Bluetooth is off") {
        val node = createNode()
        platform.radio.report(setOf(Requirement.BluetoothOff))
        node.start()
        assertEquals(MeshState.Blocked(setOf(Requirement.BluetoothOff)), node.state.value)
    }

    whenever("the node starts") {
        nodes.single().start()
    }

    whenever("the node tries to start") {
        try {
            nodes.single().start()
        } catch (failure: MeshLinkException.Closed) {
            startFailure = failure
        }
    }

    then("starting is rejected because the node is closed") {
        assertNotNull(startFailure)
    }

    then("the node is running") {
        assertEquals(MeshState.Running, nodes.single().state.value)
    }

    whenever("the node stops") {
        nodes.single().stop()
    }

    whenever("Bluetooth becomes available") {
        platform.radio.report(emptySet())
        scope.testScheduler.runCurrent()
    }

    then("the node is stopped") {
        assertEquals(MeshState.Stopped, nodes.single().state.value)
    }
}
