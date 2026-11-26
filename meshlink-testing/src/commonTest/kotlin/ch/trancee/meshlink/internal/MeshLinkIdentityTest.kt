package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DiagnosticEvent
import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshLinkException
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.MeshState
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.RejectionReason
import ch.trancee.meshlink.Requirement
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.SendResult
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import ch.trancee.meshlink.meshLinkConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MeshLinkIdentityTest {
    @Test
    fun startWithoutAnIdentityIsBlockedOnIdentityUnavailable() =
        runTest {
            // Arrange
            val (node, _) = node(FlakyIdentityStore(available = false))
            val states = recordStates(node)

            // Act
            node.start()

            // Assert
            assertEquals(listOf(MeshState.Stopped, MeshState.Starting, UNAVAILABLE), states)
        }

    @Test
    fun closeWhileTheIdentityLoadsLeavesTheNodeStopped() =
        runTest {
            // Arrange
            val store = FlakyIdentityStore(available = false)
            val (node, _) = node(store)
            store.onLoad = node::close

            // Act
            node.start()

            // Assert
            assertEquals(MeshState.Stopped, node.state.value)
        }

    @Test
    fun startAgainRetriesTheIdentity() =
        runTest {
            // Arrange
            val store = FlakyIdentityStore(available = false)
            val (node, _) = node(store)
            node.start()
            store.available = true

            // Act
            node.start()

            // Assert
            assertEquals(MeshState.Running(emptySet()), node.state.value)
        }

    @Test
    fun stopOfANodeBlockedOnItsIdentityStopsIt() =
        runTest {
            // Arrange
            val (node, _) = node(FlakyIdentityStore(available = false))
            node.start()
            val states = recordStates(node)

            // Act
            node.stop()

            // Assert
            assertEquals(listOf(UNAVAILABLE, MeshState.Stopping, MeshState.Stopped), states)
        }

    @Test
    fun sendWhileBlockedOnTheIdentityIsRejectedAsNotRunning() =
        runTest {
            // Arrange: Alice links to Bob, then restarts without her identity.
            val store = FlakyIdentityStore(available = true)
            val air = air()
            val (alice, _) = node(store, air)
            val bob = node(air.platform(StandardTestDispatcher(testScheduler)).meshPlatform)
            listOf(alice, bob).forEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)
            val bobId =
                alice.peers.value
                    .single()
                    .nodeId
            alice.stop()
            store.available = false
            alice.start()

            // Act
            val result = alice.send(bobId, ByteArray(1))

            // Assert
            assertEquals(UNAVAILABLE, alice.state.value)
            assertEquals(SendResult.Rejected(RejectionReason.NotRunning), result)
        }

    @Test
    fun resetIdentityOfAStoppedNodeGivesTheNextStartANewIdentity() =
        runTest {
            // Arrange
            val store = FlakyIdentityStore(available = true)
            val (node, _) = node(store)
            node.start()
            val before = store.memory.loadOrCreate(SodiumCryptoProvider)
            node.stop()

            // Act
            val reset = node.resetIdentity()
            node.start()

            // Assert
            assertTrue(reset)
            assertNotEquals(before, store.memory.loadOrCreate(SodiumCryptoProvider))
            assertEquals(MeshState.Running(emptySet()), node.state.value)
        }

    @Test
    fun resetIdentityOfARunningNodeThrowsInvalidStateAndKeepsTheIdentity() =
        runTest {
            // Arrange
            val store = FlakyIdentityStore(available = true)
            val (node, _) = node(store)
            node.start()

            // Act
            val thrown = assertFailsWith<MeshLinkException.InvalidState> { node.resetIdentity() }

            // Assert
            assertEquals(MeshState.Running(emptySet()), thrown.state)
            assertEquals(MeshState.Running(emptySet()), node.state.value)
            assertEquals(0, store.deletes)
        }

    @Test
    fun resetIdentityOfABlockedNodeStopsItAndDeletesTheIdentity() =
        runTest {
            // Arrange
            val store = FlakyIdentityStore(available = true)
            val (node, platform) = node(store)
            platform.radio.report(setOf(Requirement.BluetoothOff))
            node.start()
            val stops = mutableListOf<DiagnosticEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { node.diagnostics.events.toList(stops) }

            // Act
            val reset = node.resetIdentity()

            // Assert
            assertTrue(reset)
            assertEquals(MeshState.Stopped, node.state.value)
            assertEquals(1, store.deletes)
            val reasons = stops.map { (it as DiagnosticEvent.Stopped).reason }
            assertEquals(listOf(DiagnosticEvent.Stopped.Reason.IdentityReset), reasons)
        }

    @Test
    fun resetIdentityOfANodeBlockedOnItsIdentityStopsItAndDeletesTheIdentity() =
        runTest {
            // Arrange
            val store = FlakyIdentityStore(available = false)
            val (node, _) = node(store)
            node.start()

            // Act
            val reset = node.resetIdentity()

            // Assert
            assertTrue(reset)
            assertEquals(MeshState.Stopped, node.state.value)
            assertEquals(1, store.deletes)
        }

    @Test
    fun resetIdentityReportsAStoreThatRefusesToDelete() =
        runTest {
            // Arrange
            val store = FlakyIdentityStore(available = true, deletable = false)
            val (node, _) = node(store)

            // Act
            val reset = node.resetIdentity()

            // Assert
            assertFalse(reset)
        }

    @Test
    fun resetIdentityAfterCloseThrowsClosed() =
        runTest {
            // Arrange
            val store = FlakyIdentityStore(available = true)
            val (node, _) = node(store)
            node.close()

            // Act
            assertFailsWith<MeshLinkException.Closed> { node.resetIdentity() }

            // Assert
            assertEquals(0, store.deletes)
        }

    /** An [IdentityStore] in memory that can be made unavailable, or refuse to delete. */
    private class FlakyIdentityStore(
        var available: Boolean,
        private val deletable: Boolean = true,
    ) : IdentityStore {
        val memory = InMemoryIdentityStore()
        var deletes = 0
        var onLoad: () -> Unit = {}

        override fun loadOrCreate(crypto: CryptoProvider): Ed25519KeyPair? {
            onLoad()
            return if (available) memory.loadOrCreate(crypto) else null
        }

        override fun delete(): Boolean {
            deletes++
            return deletable && memory.delete()
        }
    }

    private fun TestScope.air(): VirtualAir = VirtualAir(backgroundScope, TestClock(testScheduler), SeededRandom(1))

    @OptIn(InternalMeshLinkApi::class)
    private fun TestScope.node(
        store: IdentityStore,
        air: VirtualAir = air(),
    ): Pair<MeshLink, VirtualPlatform> {
        val virtual = air.platform(StandardTestDispatcher(testScheduler))
        val port =
            object : PlatformPort by virtual {
                override val identityStore: IdentityStore = store
            }
        return node(MeshPlatform.of(port)) to virtual
    }

    private fun node(platform: MeshPlatform): MeshLink {
        val config = meshLinkConfig(Preset.OfflineChat, SecurityProfile.Unsecured) { meshId = "Identity" }.build()
        return MeshLink.create(config, platform)
    }

    /** Every state [node] passes through from now on, including short-lived ones. */
    private fun TestScope.recordStates(node: MeshLink): List<MeshState> {
        val states = mutableListOf<MeshState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { node.state.toList(states) }
        runCurrent()
        return states
    }

    private companion object {
        val UNAVAILABLE = MeshState.Blocked(setOf(Requirement.IdentityUnavailable))
    }
}
