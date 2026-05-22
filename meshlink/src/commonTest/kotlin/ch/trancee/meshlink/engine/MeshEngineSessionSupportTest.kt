package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.transport.TransportSendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class MeshEngineSessionSupportTest {
    @Test
    fun `concurrent session establishment reuses the pending initiator handshake`() = runBlocking {
        // Arrange
        val localIdentity = LocalIdentity.fromAppId("session-support-test")
        val sessionRegistry = MeshEngineSessionRegistry()
        val peerId = PeerId("peer-b")
        val sendStarted = CompletableDeferred<Unit>()
        val allowSendToFinish = CompletableDeferred<Unit>()
        var sendCallCount = 0
        val support =
            MeshEngineSessionSupport(
                localIdentity = localIdentity,
                state = MeshEngineSessionState(sessionRegistry = sessionRegistry),
                handshakeTimeout = 1.seconds,
                callbacks =
                    MeshEngineSessionCallbacks(
                        hasTransport = { true },
                        sendDirectWireFrame = { _, _, _, _ ->
                            sendCallCount += 1
                            sendStarted.complete(Unit)
                            allowSendToFinish.await()
                            TransportSendResult.Dropped("test")
                        },
                        emitHopSessionFailed = { _, _, _, _ -> Unit },
                    ),
            )

        // Act
        val firstAttempt =
            async(start = CoroutineStart.UNDISPATCHED) { support.ensureHopSession(peerId) }
        sendStarted.await()
        val secondAttempt =
            async(start = CoroutineStart.UNDISPATCHED) { support.ensureHopSession(peerId) }
        allowSendToFinish.complete(Unit)
        val firstOutcome = withTimeout(1.seconds) { firstAttempt.await() }
        val secondOutcome = withTimeout(1.seconds) { secondAttempt.await() }

        // Assert
        assertEquals(1, sendCallCount)
        assertEquals(SessionEstablishmentOutcome.Unreachable, firstOutcome)
        assertEquals(SessionEstablishmentOutcome.Unreachable, secondOutcome)
        assertNull(sessionRegistry.initiatorHandshakeReservation(peerId))
    }
}
