package ch.trancee.meshlink.docs

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshLinkConfig
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.MeshState
import ch.trancee.meshlink.Peer
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.SendResult
import ch.trancee.meshlink.internal.SeededRandom
import ch.trancee.meshlink.internal.TestClock
import ch.trancee.meshlink.internal.VirtualAir
import ch.trancee.meshlink.meshLinkConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The Kotlin code of docs/tutorials/get-started.md, run on VirtualAir. Each `// region` is copied into
// the tutorial, and ./gradlew checkDocRegions fails when the two differ (ADR 0006 decision 5).
@OptIn(InternalMeshLinkApi::class)
class GetStartedTest {
    @Test
    fun aliceSendsBobAMessageOverASecuredLinkAndSeesItDelivered() =
        runTest {
            // Arrange
            val air = VirtualAir(backgroundScope, TestClock(testScheduler), SeededRandom(1))
            val config = configure()
            val alice = startNode(config, platform(air))
            val bob = startNode(config, platform(air))
            val inbox = async { receive(bob) }

            // Act
            val peer = firstPeer(alice)
            val status = send(alice, peer)
            val text = inbox.await()
            close(alice)
            bob.close()

            // Assert
            assertTrue(peer.secured)
            assertEquals(DeliveryStatus.Delivered, status)
            assertEquals("Hello from Alice", text)
            assertEquals(MeshState.Stopped, alice.state.value)
        }

    private fun TestScope.platform(air: VirtualAir): MeshPlatform {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return MeshPlatform.of(air.platform(dispatcher))
    }

    private fun configure(): MeshLinkConfig {
        // region configure
        val config =
            meshLinkConfig(Preset.OfflineChat, SecurityProfile.Classical) {
                meshId = "Get started"
            }.build()
        // endregion
        return config
    }

    private suspend fun startNode(
        config: MeshLinkConfig,
        platform: MeshPlatform,
    ): MeshLink {
        // region create
        val node = MeshLink.create(config, platform)
        // endregion
        // region start
        node.start()
        // endregion
        return node
    }

    private suspend fun firstPeer(node: MeshLink): Peer {
        // region peers
        val peer = node.peers.first { it.isNotEmpty() }.first()
        // endregion
        return peer
    }

    private suspend fun send(
        node: MeshLink,
        peer: Peer,
    ): DeliveryStatus {
        // region send
        val result = node.send(peer.nodeId, "Hello from Alice".encodeToByteArray())
        val status =
            when (result) {
                is SendResult.Accepted -> result.message.status.first { it == DeliveryStatus.Delivered }
                is SendResult.Rejected -> error("Not sent: ${result.reason}")
            }
        // endregion
        return status
    }

    private suspend fun receive(node: MeshLink): String {
        // region receive
        val message = node.messages.first()
        val text = message.payload.decodeToString()
        // endregion
        return text
    }

    private fun close(node: MeshLink) {
        // region close
        node.close()
        // endregion
    }
}
