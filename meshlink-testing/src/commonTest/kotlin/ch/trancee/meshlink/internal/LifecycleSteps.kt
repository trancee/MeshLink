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
import kotlinx.coroutines.coroutineScope
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
        topologySteps()
        configurationSteps()
        directMessageSteps()
        identityAvailabilitySteps()
        lifecycleSteps()
        discoverySteps()
        linkSteps()
        meshDeliverySteps()
        certificateLookupSteps()
        securedMessagingSteps()
        securedLinkSteps()
    }

/** The nodes of one lifecycle scenario, on one scheduler; each [create]d node gets its own radio. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class LifecycleNodes {
    lateinit var scope: TestScope
    lateinit var platform: VirtualPlatform
    private var scopeInitialized = false
    val nodes = mutableListOf<MeshLink>()

    /** A stopped node on a radio of its own. */
    suspend fun create(): MeshLink {
        if (!scopeInitialized) {
            val dispatcher = currentCoroutineContext()[ContinuationInterceptor] as TestDispatcher
            scope = TestScope(dispatcher)
            scopeInitialized = true
        }
        platform = scope.air().platform(StandardTestDispatcher(scope.testScheduler))
        return onSameRadio()
    }

    /** The scenario's only node. */
    val node: MeshLink get() = nodes.single()

    /** A stopped node on the radio of the node [create]d last. */
    fun onSameRadio(): MeshLink = node(platform).also { nodes += it }

    /** Records every state of [node] into [states]. */
    fun observe(
        node: MeshLink,
        states: MutableList<MeshState>,
    ) {
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { node.state.toList(states) }
    }

    /** Runs every task due now. */
    fun settle() = scope.testScheduler.runCurrent()

    fun close() {
        nodes.forEach { it.close() }
        if (scopeInitialized) scope.backgroundScope.cancel()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun StepsBuilder.lifecycleSteps() {
    val world = LifecycleNodes()
    val observedStates = mutableListOf<MeshState>()

    after { world.close() }

    closedNodeSteps(world)
    separateRadioSteps(world)
    sharedRadioSteps(world)
    identitySteps(world)
    permissionSteps(world)

    given("a stopped node") {
        world.create()
    }

    given("a running node") {
        world.create().start()
    }

    given("the node's lifecycle is observed") {
        world.observe(world.node, observedStates)
    }

    given("the node has stopped") {
        world.node.stop()
        assertEquals(MeshState.Stopped, world.node.state.value)
    }

    given("a node blocked because Bluetooth is off") {
        val node = world.create()
        world.platform.radio.report(setOf(Requirement.BluetoothOff))
        node.start()
        assertEquals(MeshState.Blocked(setOf(Requirement.BluetoothOff)), node.state.value)
    }

    whenever("the node starts") {
        world.node.start()
    }

    then("the node is running") {
        assertEquals(MeshState.Running, world.node.state.value)
    }

    then("the node remains running without a lifecycle transition") {
        assertEquals(listOf<MeshState>(MeshState.Running), observedStates)
        assertEquals(MeshState.Running, world.node.state.value)
    }

    whenever("the node stops") {
        world.node.stop()
    }

    whenever("Bluetooth becomes available") {
        world.platform.radio.report(emptySet())
        world.settle()
    }

    then("the node is stopped") {
        assertEquals(MeshState.Stopped, world.node.state.value)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun StepsBuilder.separateRadioSteps(world: LifecycleNodes) {
    val observedByNode = mutableListOf<MutableList<MeshState>>()

    given("two stopped nodes on different radios") {
        repeat(2) { world.create() }
    }

    given("both nodes' lifecycles are observed") {
        world.nodes.forEach { node ->
            val states = mutableListOf<MeshState>().also { observedByNode += it }
            world.observe(node, states)
        }
    }

    whenever("both nodes start at the same time") {
        coroutineScope { world.nodes.forEach { launch { it.start() } } }
        world.settle()
    }

    then("both nodes are running and neither was blocked") {
        world.nodes.forEach { assertEquals(MeshState.Running, it.state.value) }
        observedByNode.forEach { states ->
            assertEquals(listOf(MeshState.Stopped, MeshState.Starting, MeshState.Running), states)
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun StepsBuilder.sharedRadioSteps(world: LifecycleNodes) {
    val radioInUse = MeshState.Blocked(setOf(Requirement.RadioInUse))

    suspend fun waitingNodes(count: Int) =
        repeat(count) {
            val node = world.onSameRadio()
            node.start()
            assertEquals(radioInUse, node.state.value)
        }

    given("a second node on the same radio is blocked because the radio is in use") { waitingNodes(1) }

    given("two more nodes on the same radio are blocked because the radio is in use") { waitingNodes(2) }

    whenever("the waiting node stops") {
        world.nodes.last().stop()
    }

    whenever("the running node stops") {
        world.nodes.first().stop()
        world.settle()
    }

    then("a third node started on that radio is blocked because the radio is in use") {
        val third = world.onSameRadio()
        third.start()
        assertEquals(
            MeshState.Running,
            world.nodes
                .first()
                .state.value,
        )
        assertEquals(radioInUse, third.state.value)
    }

    then("exactly one waiting node is running and the other is blocked because the radio is in use") {
        assertEquals(
            setOf(MeshState.Running, radioInUse),
            world.nodes
                .drop(1)
                .map { it.state.value }
                .toSet(),
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun StepsBuilder.closedNodeSteps(world: LifecycleNodes) {
    var startFailure: MeshLinkException.Closed? = null

    given("a closed node") {
        world.create().close()
    }

    whenever("the node tries to start") {
        try {
            world.node.start()
        } catch (failure: MeshLinkException.Closed) {
            startFailure = failure
        }
    }

    then("starting is rejected because the node is closed") {
        assertNotNull(startFailure)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun StepsBuilder.permissionSteps(world: LifecycleNodes) {
    given("a node blocked because Bluetooth scan permission is denied") {
        val node = world.create()
        val denied = Requirement.PermissionDenied(PermissionKind.BluetoothScan)
        world.platform.radio.report(setOf(denied))
        node.start()
        assertEquals(MeshState.Blocked(setOf(denied)), node.state.value)
    }

    whenever("Bluetooth scan permission is granted") {
        world.platform.radio.report(emptySet())
        world.settle()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun StepsBuilder.identitySteps(world: LifecycleNodes) {
    var resetFailure: MeshLinkException.InvalidState? = null

    whenever("the node tries to reset its identity") {
        try {
            world.node.resetIdentity()
        } catch (failure: MeshLinkException.InvalidState) {
            resetFailure = failure
        }
    }

    then("the reset is rejected because the node is running") {
        assertEquals(MeshState.Running, assertNotNull(resetFailure).state)
        assertEquals(MeshState.Running, world.node.state.value)
    }
}
