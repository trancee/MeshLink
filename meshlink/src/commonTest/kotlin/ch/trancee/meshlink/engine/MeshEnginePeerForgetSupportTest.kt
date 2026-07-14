package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.ForgetPeerResult
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.engine.internal.PendingInitiatorHandshake
import ch.trancee.meshlink.engine.internal.SessionEstablishmentOutcome
import ch.trancee.meshlink.engine.lifecycle.MeshEnginePeerForgetCallbacks
import ch.trancee.meshlink.engine.lifecycle.MeshEnginePeerForgetSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

class MeshEnginePeerForgetSupportTest {
    @Test
    fun `forgetPeer returns NotFound when no trust record exists`() =
        runBlocking<Unit> {
            // Arrange
            val callbacks = RecordingPeerForgetCallbacks(firstSeenAtEpochMillis = null)
            val support = MeshEnginePeerForgetSupport(callbacks.asCallbacks())
            val peerId = PeerId("peer-abcdef")

            // Act
            val result = support.forgetPeer(peerId)

            // Assert
            assertEquals(ForgetPeerResult.NotFound, result)
            assertFalse(callbacks.deleteTrustCalled)
            assertTrue(callbacks.disconnectedMetadata.isEmpty())
            assertTrue(callbacks.lostPeers.isEmpty())
        }

    @Test
    fun `forgetPeer clears trust disconnects the peer and emits peer lost when presence changes`() =
        runBlocking<Unit> {
            // Arrange
            val sessionDeferred = CompletableDeferred<SessionEstablishmentOutcome>()
            val pendingHandshake =
                PendingInitiatorHandshake(
                    manager =
                        ch.trancee.meshlink.crypto.NoiseXXHandshakeManager(
                            ch.trancee.meshlink.identity.LocalIdentity.fromAppId("forget-peer-test")
                                .cryptoProvider
                        ),
                    sessionDeferred = sessionDeferred,
                )
            val callbacks =
                RecordingPeerForgetCallbacks(
                    firstSeenAtEpochMillis = 123L,
                    pendingHandshake = pendingHandshake,
                    markPeerDisconnected = true,
                )
            val support = MeshEnginePeerForgetSupport(callbacks.asCallbacks())
            val peerId = PeerId("peer-abcdef")

            // Act
            val result = support.forgetPeer(peerId)

            // Assert
            assertEquals(ForgetPeerResult.Forgotten, result)
            assertTrue(callbacks.deleteTrustCalled)
            assertEquals(1, callbacks.clearPeerCalls)
            assertTrue(sessionDeferred.isCompleted)
            assertIs<SessionEstablishmentOutcome.Unreachable>(sessionDeferred.await())
            assertEquals(
                listOf(
                    peerId to
                        mapOf("forgottenPeerId" to peerId.value, "firstSeenAtEpochMillis" to "123")
                ),
                callbacks.disconnectedMetadata,
            )
            assertEquals(listOf(peerId), callbacks.lostPeers)
        }

    @Test
    fun `forgetPeer skips peer lost when presence was already disconnected`() =
        runBlocking<Unit> {
            // Arrange
            val callbacks =
                RecordingPeerForgetCallbacks(
                    firstSeenAtEpochMillis = 456L,
                    markPeerDisconnected = false,
                )
            val support = MeshEnginePeerForgetSupport(callbacks.asCallbacks())
            val peerId = PeerId("peer-abcdef")

            // Act
            val result = support.forgetPeer(peerId)

            // Assert
            assertEquals(ForgetPeerResult.Forgotten, result)
            assertTrue(callbacks.deleteTrustCalled)
            assertTrue(callbacks.lostPeers.isEmpty())
        }
}

private class RecordingPeerForgetCallbacks(
    private val firstSeenAtEpochMillis: Long?,
    private val pendingHandshake: PendingInitiatorHandshake? = null,
    private val markPeerDisconnected: Boolean = false,
) {
    var deleteTrustCalled: Boolean = false
    var clearPeerCalls: Int = 0
    val disconnectedMetadata: MutableList<Pair<PeerId, Map<String, String>>> = mutableListOf()
    val lostPeers: MutableList<PeerId> = mutableListOf()

    fun asCallbacks(): MeshEnginePeerForgetCallbacks {
        return MeshEnginePeerForgetCallbacks(
            readFirstSeenAtEpochMillis = { firstSeenAtEpochMillis },
            deleteTrust = { deleteTrustCalled = true },
            clearPeer = {
                clearPeerCalls += 1
                pendingHandshake
            },
            dispatchPeerDisconnected = { peerId, metadata ->
                disconnectedMetadata += peerId to metadata
            },
            markPeerDisconnected = { markPeerDisconnected },
            emitPeerLost = { peerId -> lostPeers += peerId },
        )
    }
}
