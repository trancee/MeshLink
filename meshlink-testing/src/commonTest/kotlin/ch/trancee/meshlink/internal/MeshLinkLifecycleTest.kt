package ch.trancee.meshlink.internal

import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshLinkException
import ch.trancee.meshlink.MeshState
import ch.trancee.meshlink.PermissionKind
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.Requirement
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.meshLinkConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class MeshLinkLifecycleTest {
    @Test
    fun stateMovesFromStoppedThroughRunningBackToStopped() =
        runTest {
            // Arrange
            val node = node(platform())
            val states = recordStates(node)

            // Act
            node.start()
            node.stop()
            runCurrent()

            // Assert
            assertEquals(
                listOf(MeshState.Stopped, MeshState.Starting, RUNNING, MeshState.Stopping, MeshState.Stopped),
                states,
            )
        }

    @Test
    fun startAndStopAreIdempotent() =
        runTest {
            // Arrange
            val node = node(platform())
            val states = recordStates(node)

            // Act
            node.stop()
            node.start()
            node.start()
            node.stop()
            node.stop()
            runCurrent()

            // Assert
            assertEquals(
                listOf(MeshState.Stopped, MeshState.Starting, RUNNING, MeshState.Stopping, MeshState.Stopped),
                states,
            )
        }

    @Test
    fun startAndStopAfterCloseThrowClosed() =
        runTest {
            // Arrange
            val node = node(platform())
            node.start()

            // Act
            node.close()
            node.close()

            // Assert
            assertEquals(MeshState.Stopped, node.state.value)
            assertFailsWith<MeshLinkException.Closed> { node.start() }
            assertFailsWith<MeshLinkException.Closed> { node.stop() }
        }

    @Test
    fun nodeBlockedOnStartRunsOnceTheRequirementClears() =
        runTest {
            val requirements =
                listOf(
                    Requirement.BluetoothOff,
                    Requirement.PermissionDenied(PermissionKind.BluetoothScan),
                    Requirement.Unsupported,
                    Requirement.RadioInUse,
                )
            requirements.forEach { requirement ->
                // Arrange
                val platform = platform()
                val node = node(platform)
                platform.radio.report(setOf(requirement))

                // Act
                node.start()
                val blocked = node.state.value
                platform.radio.report(emptySet())
                runCurrent()

                // Assert
                assertEquals(MeshState.Blocked(setOf(requirement)), blocked)
                assertEquals(RUNNING, node.state.value)
                node.close()
            }
        }

    @Test
    fun runningNodeIsBlockedWhileARequirementIsMissing() =
        runTest {
            // Arrange
            val platform = platform()
            val node = node(platform)
            node.start()

            // Act
            platform.radio.report(setOf(Requirement.BluetoothOff))
            runCurrent()
            val blocked = node.state.value
            platform.radio.report(emptySet())
            runCurrent()

            // Assert
            assertEquals(MeshState.Blocked(setOf(Requirement.BluetoothOff)), blocked)
            assertEquals(RUNNING, node.state.value)
        }

    @Test
    fun secondNodeOnTheSamePlatformRadioRunsOnceTheFirstStops() =
        runTest {
            // Arrange: ADR 0007 decision 1 as amended, one running node per platform radio.
            val platform = platform()
            val first = node(platform)
            val second = node(platform)
            first.start()

            // Act
            second.start()
            val blocked = second.state.value
            first.stop()
            runCurrent()

            // Assert
            assertEquals(MeshState.Blocked(setOf(Requirement.RadioInUse)), blocked)
            assertEquals(RUNNING, second.state.value)
        }

    @Test
    fun closingTheRunningNodeFreesTheRadio() =
        runTest {
            // Arrange
            val platform = platform()
            val first = node(platform)
            val second = node(platform)
            first.start()
            second.start()

            // Act
            first.close()
            runCurrent()

            // Assert
            assertEquals(RUNNING, second.state.value)
        }

    @Test
    fun startWaitsUntilTheRadioKnowsWhatItMisses() =
        runTest {
            // Arrange
            val platform = platform()
            val node = node(platform)
            platform.radio.reportUnknown()

            // Act
            val starting = launch { node.start() }
            runCurrent()
            val unknown = node.state.value
            platform.radio.report(emptySet())
            starting.join()

            // Assert
            assertEquals(MeshState.Starting, unknown)
            assertEquals(RUNNING, node.state.value)
            node.close()
        }

    @Test
    fun nodeWaitingForTheRadioStillReportsTheRadioRequirements() =
        runTest {
            // Arrange
            val platform = platform()
            val first = node(platform)
            val second = node(platform)
            platform.radio.report(setOf(Requirement.BluetoothOff))
            first.start()

            // Act
            second.start()

            // Assert
            assertEquals(MeshState.Blocked(setOf(Requirement.BluetoothOff)), first.state.value)
            assertEquals(
                MeshState.Blocked(setOf(Requirement.BluetoothOff, Requirement.RadioInUse)),
                second.state.value,
            )
        }

    @Test
    fun nodesOnSeparateVirtualRadiosAllRun() =
        runTest {
            // Arrange: any number of nodes may run on VirtualAir in one process.
            val air = air()
            val nodes = List(3) { node(air.platform(StandardTestDispatcher(testScheduler))) }

            // Act
            nodes.forEach { it.start() }

            // Assert
            nodes.forEach { assertEquals(RUNNING, it.state.value) }
        }

    @Test
    fun firstStartCreatesTheInstallationIdentityAndARestartKeepsIt() =
        runTest {
            // Arrange
            val platform = platform()
            val node = node(platform)
            val store = platform.identityStore
            assertFailsWith<IllegalStateException>("the store starts empty") { store.loadOrCreate(NoNewKeyPairs) }

            // Act
            node.start()
            val created = platform.identityStore.loadOrCreate(NoNewKeyPairs)
            node.stop()
            node.start()

            // Assert
            assertSame(created, platform.identityStore.loadOrCreate(NoNewKeyPairs))
        }

    private fun TestScope.air(): VirtualAir = VirtualAir(backgroundScope, TestClock(testScheduler), SeededRandom(1))

    private fun TestScope.platform(): VirtualPlatform = air().platform(StandardTestDispatcher(testScheduler))

    private fun node(platform: VirtualPlatform): MeshLink =
        MeshLink.create(
            meshLinkConfig(Preset.OfflineChat, SecurityProfile.Unsecured) { meshId = "Lifecycle" }.build(),
            platform.meshPlatform,
        )

    /** Every state [node] passes through, including short-lived ones. */
    private fun TestScope.recordStates(node: MeshLink): List<MeshState> {
        val states = mutableListOf<MeshState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { node.state.toList(states) }
        return states
    }

    private companion object {
        val RUNNING: MeshState = MeshState.Running(emptySet())
    }
}
