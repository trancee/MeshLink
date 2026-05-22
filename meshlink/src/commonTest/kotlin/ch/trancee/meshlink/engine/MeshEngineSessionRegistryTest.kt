package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.NoiseXXHandshakeManager
import ch.trancee.meshlink.identity.LocalIdentity
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MeshEngineSessionRegistryTest {
    @Test
    fun `rebind pending responder handshake moves it to the canonical peer id`() = runBlocking {
        // Arrange
        val localIdentity = LocalIdentity.fromAppId("session-registry-rebind-test")
        val sessionRegistry = MeshEngineSessionRegistry()
        val temporaryPeerId = PeerId("bt-aabbccddeeff")
        val canonicalPeerId = PeerId("00112233445566778899aabb")
        val pendingHandshake =
            PendingResponderHandshake(NoiseXXHandshakeManager(localIdentity.cryptoProvider))
        sessionRegistry.storePendingResponderHandshake(temporaryPeerId, pendingHandshake)

        // Act
        val rebound =
            sessionRegistry.rebindPendingResponderHandshake(
                fromPeerId = temporaryPeerId,
                toPeerId = canonicalPeerId,
                pendingHandshake = pendingHandshake,
            )

        // Assert
        assertTrue(rebound)
        assertNull(sessionRegistry.pendingResponderHandshake(temporaryPeerId))
        assertSame(pendingHandshake, sessionRegistry.pendingResponderHandshake(canonicalPeerId))
    }
}
