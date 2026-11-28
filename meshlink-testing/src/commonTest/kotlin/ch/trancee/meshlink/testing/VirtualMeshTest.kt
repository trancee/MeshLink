package ch.trancee.meshlink.testing

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshLinkConfig
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.ReceivedMessage
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.SendResult
import ch.trancee.meshlink.meshLinkConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class VirtualMeshTest {
    @Test
    fun connectedNodesFindEachOtherAndDeliverAMessage() =
        runTest {
            // Arrange
            val mesh = VirtualMesh(this)
            val alice = mesh.node(chat())
            val bob = mesh.node(chat())
            val inbox = mutableListOf<ReceivedMessage>()
            backgroundScope.launch { bob.messages.collect { inbox += it } }
            mesh.connect(alice, bob)
            alice.start()
            bob.start()
            mesh.advance(SETTLE)

            // Act
            val result = alice.send(alice.onlyPeer(), "Hello".encodeToByteArray())
            mesh.advance(SETTLE)

            // Assert
            val accepted = result as SendResult.Accepted
            assertEquals(DeliveryStatus.Delivered, accepted.message.status.value)
            assertEquals(listOf("Hello"), inbox.map { it.payload.decodeToString() })
            alice.close()
            bob.close()
        }

    @Test
    fun nodesNeverConnectedStayStrangers() =
        runTest {
            // Arrange
            val mesh = VirtualMesh(this)
            val alice = mesh.node(chat())
            val bob = mesh.node(chat())
            alice.start()
            bob.start()

            // Act
            mesh.advance(SETTLE)

            // Assert
            assertEquals(emptyList(), alice.peers.value)
            assertEquals(emptyList(), bob.peers.value)
            alice.close()
            bob.close()
        }

    @Test
    fun disconnectedNodesHoldTheMessage() =
        runTest {
            // Arrange
            val mesh = VirtualMesh(this)
            val alice = mesh.node(chat())
            val bob = mesh.node(chat())
            val inbox = mutableListOf<ReceivedMessage>()
            backgroundScope.launch { bob.messages.collect { inbox += it } }
            mesh.connect(alice, bob)
            alice.start()
            bob.start()
            mesh.advance(SETTLE)
            val bobId = alice.onlyPeer()

            // Act
            mesh.disconnect(alice, bob)
            mesh.advance(SETTLE)
            val result = alice.send(bobId, "Hello".encodeToByteArray())
            mesh.advance(SETTLE)

            // Assert
            val accepted = result as SendResult.Accepted
            assertEquals(DeliveryStatus.Held, accepted.message.status.value)
            assertEquals(emptyList(), inbox)
            alice.close()
            bob.close()
        }

    @Test
    fun aSignalBelowTheDialFloorLinksNothing() =
        runTest {
            // Arrange
            val mesh = VirtualMesh(this)
            val alice = mesh.node(chat())
            val bob = mesh.node(chat())
            mesh.connect(alice, bob, rssi = -90)
            alice.start()
            bob.start()

            // Act
            mesh.advance(SETTLE)

            // Assert
            assertEquals(emptyList(), alice.peers.value)
            alice.close()
            bob.close()
        }

    @Test
    fun advanceRunsTheWorkDueAtItsEnd() =
        runTest {
            // Arrange
            val mesh = VirtualMesh(this)
            var ran = false
            launch {
                delay(DUE)
                ran = true
            }

            // Act
            mesh.advance(DUE)

            // Assert
            assertTrue(ran)
        }

    @Test
    fun connectRefusesANodeOfAnotherMeshOrTheSameNode() =
        runTest {
            // Arrange
            val mesh = VirtualMesh(this)
            val alice = mesh.node(chat())
            val stranger = VirtualMesh(this).node(chat())

            // Act
            val other = assertFailsWith<IllegalArgumentException> { mesh.connect(alice, stranger) }
            val same = assertFailsWith<IllegalArgumentException> { mesh.disconnect(alice, alice) }

            // Assert
            assertEquals("Not a node of this VirtualMesh", other.message)
            assertEquals("A node can't connect to itself", same.message)
            alice.close()
            stranger.close()
        }

    private fun chat(): MeshLinkConfig {
        val builder = meshLinkConfig(Preset.OfflineChat, SecurityProfile.Unsecured) { meshId = MESH_ID }
        return builder.build()
    }

    private fun MeshLink.onlyPeer() = peers.value.single().nodeId

    private companion object {
        const val MESH_ID = "Virtual mesh"
        val SETTLE = 30.seconds
        val DUE = 500.milliseconds
    }
}
