package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.transport.TransportSendResult
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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

    @Test
    fun `transient connection setup drops retry handshake message one until a direct link is ready`() =
        runBlocking {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("session-support-retry-test")
            val sessionRegistry = MeshEngineSessionRegistry()
            val peerId = PeerId("peer-b")
            val establishedSession = HopSession(ByteArray(32) { 0x01 }, ByteArray(32) { 0x02 })
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
                                when (sendCallCount) {
                                    1 ->
                                        TransportSendResult.Dropped(
                                            "Android BLE L2CAP connection is not ready"
                                        )

                                    2 -> {
                                        val pendingReservation =
                                            assertIs<InitiatorHandshakeReservation.Pending>(
                                                sessionRegistry.initiatorHandshakeReservation(
                                                    peerId
                                                )
                                            )
                                        sessionRegistry.completeInitiatorHandshake(
                                            peerId = peerId,
                                            pendingHandshake = pendingReservation.pendingHandshake,
                                            session = establishedSession,
                                        )
                                        pendingReservation.pendingHandshake.sessionDeferred
                                            .complete(
                                                SessionEstablishmentOutcome.Established(
                                                    establishedSession
                                                )
                                            )
                                        TransportSendResult.Delivered
                                    }

                                    else -> error("Unexpected send attempt $sendCallCount")
                                }
                            },
                            emitHopSessionFailed = { _, _, _, _ -> Unit },
                        ),
                )

            // Act
            val outcome = support.ensureHopSession(peerId)

            // Assert
            assertEquals(2, sendCallCount)
            val establishedOutcome = assertIs<SessionEstablishmentOutcome.Established>(outcome)
            assertContentEquals(establishedSession.sendKey, establishedOutcome.session.sendKey)
            assertContentEquals(
                establishedSession.receiveKey,
                establishedOutcome.session.receiveKey,
            )
        }

    @Test
    fun `transient connection setup keeps retrying beyond one second when the timeout allows it`() =
        runBlocking {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("session-support-extended-retry-test")
            val sessionRegistry = MeshEngineSessionRegistry()
            val peerId = PeerId("peer-c")
            val establishedSession = HopSession(ByteArray(32) { 0x03 }, ByteArray(32) { 0x04 })
            var sendCallCount = 0
            val support =
                MeshEngineSessionSupport(
                    localIdentity = localIdentity,
                    state = MeshEngineSessionState(sessionRegistry = sessionRegistry),
                    handshakeTimeout = 2.seconds,
                    callbacks =
                        MeshEngineSessionCallbacks(
                            hasTransport = { true },
                            sendDirectWireFrame = { _, _, _, _ ->
                                sendCallCount += 1
                                when (sendCallCount) {
                                    1,
                                    2,
                                    3 -> {
                                        delay(350.milliseconds)
                                        TransportSendResult.Dropped(
                                            "Android BLE L2CAP connection is not ready"
                                        )
                                    }

                                    4 -> {
                                        val pendingReservation =
                                            assertIs<InitiatorHandshakeReservation.Pending>(
                                                sessionRegistry.initiatorHandshakeReservation(
                                                    peerId
                                                )
                                            )
                                        sessionRegistry.completeInitiatorHandshake(
                                            peerId = peerId,
                                            pendingHandshake = pendingReservation.pendingHandshake,
                                            session = establishedSession,
                                        )
                                        pendingReservation.pendingHandshake.sessionDeferred
                                            .complete(
                                                SessionEstablishmentOutcome.Established(
                                                    establishedSession
                                                )
                                            )
                                        TransportSendResult.Delivered
                                    }

                                    else -> error("Unexpected send attempt $sendCallCount")
                                }
                            },
                            emitHopSessionFailed = { _, _, _, _ -> Unit },
                        ),
                )

            // Act
            val outcome = support.ensureHopSession(peerId)

            // Assert
            assertEquals(4, sendCallCount)
            val establishedOutcome = assertIs<SessionEstablishmentOutcome.Established>(outcome)
            assertContentEquals(establishedSession.sendKey, establishedOutcome.session.sendKey)
            assertContentEquals(
                establishedSession.receiveKey,
                establishedOutcome.session.receiveKey,
            )
        }
}
