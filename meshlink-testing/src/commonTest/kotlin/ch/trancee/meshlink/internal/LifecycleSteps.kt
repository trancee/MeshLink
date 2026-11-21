package ch.trancee.meshlink.internal

import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshLinkException
import ch.trancee.meshlink.MeshState
import ch.trancee.meshlink.PermissionKind
import ch.trancee.meshlink.Requirement
import io.github.menjoo.cucumberkmp.core.runner.StepsBuilder
import io.github.menjoo.cucumberkmp.core.runner.given
import io.github.menjoo.cucumberkmp.core.runner.steps
import io.github.menjoo.cucumberkmp.core.runner.then
import io.github.menjoo.cucumberkmp.core.runner.whenever
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

internal val acceptanceSteps =
    steps {
        configurationSteps()
        directMessageSteps()
        identityAvailabilitySteps()
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
    var resetFailure: MeshLinkException.InvalidState? = null
    val nodes = mutableListOf<MeshLink>()
    val observedStates = mutableListOf<MeshState>()

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

    fun closedNodeSteps() {
        given("a closed node") {
            createNode().close()
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
    }

    fun permissionSteps() {
        given("a node blocked because Bluetooth scan permission is denied") {
            val node = createNode()
            val denied = Requirement.PermissionDenied(PermissionKind.BluetoothScan)
            platform.radio.report(setOf(denied))
            node.start()
            assertEquals(MeshState.Blocked(setOf(denied)), node.state.value)
        }

        whenever("Bluetooth scan permission is granted") {
            platform.radio.report(emptySet())
            scope.testScheduler.runCurrent()
        }
    }

    fun identitySteps() {
        whenever("the node tries to reset its identity") {
            try {
                nodes.single().resetIdentity()
            } catch (failure: MeshLinkException.InvalidState) {
                resetFailure = failure
            }
        }

        then("the reset is rejected because the node is running") {
            assertEquals(MeshState.Running, assertNotNull(resetFailure).state)
            assertEquals(MeshState.Running, nodes.single().state.value)
        }
    }

    closedNodeSteps()
    identitySteps()
    permissionSteps()

    given("a stopped node") {
        createNode()
    }

    given("a running node") {
        createNode().start()
    }

    given("the node's lifecycle is observed") {
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            nodes.single().state.toList(observedStates)
        }
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

    then("the node is running") {
        assertEquals(MeshState.Running, nodes.single().state.value)
    }

    then("the node remains running without a lifecycle transition") {
        assertEquals(listOf<MeshState>(MeshState.Running), observedStates)
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
