package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.engine.assembly.MeshEngineHardRunToken
import ch.trancee.meshlink.engine.assembly.MeshEngineRuntimeSurface
import ch.trancee.meshlink.engine.internal.SessionEstablishmentOutcome
import ch.trancee.meshlink.engine.lifecycle.MeshEnginePeerFlowCallbacks
import ch.trancee.meshlink.engine.lifecycle.MeshEnginePeerFlowConfig
import ch.trancee.meshlink.engine.lifecycle.MeshEnginePeerFlowContext
import ch.trancee.meshlink.engine.lifecycle.MeshEnginePeerFlowSupport
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.trust.TrustPublicKeys
import ch.trancee.meshlink.trust.TrustRecord
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

class MeshEnginePeerFlowSupportTest {
    @Test
    fun `prewarmHopSession ensures later peers during an active hard run`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("peer-b")
            val runtimeSurface = MeshEngineRuntimeSurface()
            val hardRunToken = runtimeSurface.beginHardRun()
            val ensuredPeers = mutableListOf<String>()
            val fixture =
                peerFlowFixture(
                    localIdentity = localIdentity,
                    runtimeSurface = runtimeSurface,
                    captureHardRunToken = { hardRunToken },
                    ensureHopSession = { peerId ->
                        ensuredPeers += peerId.value
                        SessionEstablishmentOutcome.Unreachable
                    },
                )

            // Act
            fixture.support.prewarmHopSession(PeerId("peer-c"))

            // Assert
            assertEquals(listOf("peer-c"), ensuredPeers)
        }

    @Test
    fun `prewarmHopSession skips peers at or below the local peer id`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("peer-b")
            val runtimeSurface = MeshEngineRuntimeSurface()
            val hardRunToken = runtimeSurface.beginHardRun()
            var ensureCalls = 0
            val fixture =
                peerFlowFixture(
                    localIdentity = localIdentity,
                    runtimeSurface = runtimeSurface,
                    captureHardRunToken = { hardRunToken },
                    ensureHopSession = {
                        ensureCalls += 1
                        SessionEstablishmentOutcome.Unreachable
                    },
                )

            // Act
            fixture.support.prewarmHopSession(PeerId("peer-a"))
            fixture.support.prewarmHopSession(PeerId("peer-b"))

            // Assert
            assertEquals(0, ensureCalls)
        }

    @Test
    fun `forwardMessageToNextHop emits queued and delivered diagnostics when relay forwarding succeeds`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("relay-local")
            val destinationIdentity = LocalIdentity.fromAppId("relay-destination")
            val runtimeSurface = MeshEngineRuntimeSurface()
            val hardRunToken = runtimeSurface.beginHardRun()
            val fixture =
                peerFlowFixture(
                    localIdentity = localIdentity,
                    runtimeSurface = runtimeSurface,
                    captureHardRunToken = { hardRunToken },
                    sendEncryptedWireFrame = { _, _, _, _ -> true },
                )
            fixture.routeCoordinator.onPeerConnected(
                peerId = destinationIdentity.peerId,
                trustRecord = trustRecordFor(destinationIdentity),
            )
            val frame =
                WireFrame.Message(
                    messageId = "message-1",
                    originPeerId = PeerId("origin-peer"),
                    destinationPeerId = destinationIdentity.peerId,
                    priority = DeliveryPriority.NORMAL,
                    ttlMillis = 1000,
                    encryptedPayload = "hello".encodeToByteArray(),
                )

            // Act
            fixture.support.forwardMessageToNextHop(frame = frame, hardRunToken = hardRunToken)

            // Assert
            assertEquals(
                listOf(destinationIdentity.peerId.value to "forward.message"),
                fixture.forwardedFrames.map { forwarded ->
                    forwarded.peerIdValue to forwarded.action
                },
            )
            assertEquals(
                listOf(
                    DiagnosticCode.DELIVERY_QUEUED to "forward.message.queued",
                    DiagnosticCode.DELIVERY_SUCCEEDED to "forward.message.delivered",
                ),
                fixture.diagnostics.map { diagnostic -> diagnostic.code to diagnostic.stage },
            )
        }

    @Test
    fun `forwardMessageToNextHop emits no route when the destination is unreachable`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("relay-local")
            val runtimeSurface = MeshEngineRuntimeSurface()
            val hardRunToken = runtimeSurface.beginHardRun()
            val fixture =
                peerFlowFixture(
                    localIdentity = localIdentity,
                    runtimeSurface = runtimeSurface,
                    captureHardRunToken = { hardRunToken },
                )
            val frame =
                WireFrame.Message(
                    messageId = "message-2",
                    originPeerId = PeerId("origin-peer"),
                    destinationPeerId = PeerId("missing-destination"),
                    priority = DeliveryPriority.NORMAL,
                    ttlMillis = 1000,
                    encryptedPayload = "hello".encodeToByteArray(),
                )

            // Act
            fixture.support.forwardMessageToNextHop(frame = frame, hardRunToken = hardRunToken)

            // Assert
            assertTrue(fixture.forwardedFrames.isEmpty())
            assertEquals(
                listOf(DiagnosticCode.DELIVERY_UNREACHABLE to "forward.message.noRoute"),
                fixture.diagnostics.map { diagnostic -> diagnostic.code to diagnostic.stage },
            )
        }

    @Test
    fun `shouldAttemptLargeInlineSend requires a direct peer with enough transport budget`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("relay-local")
            val destinationIdentity = LocalIdentity.fromAppId("inline-destination")
            val fixture =
                peerFlowFixture(
                    localIdentity = localIdentity,
                    maximumPayloadBytesPerDelivery = { peerId ->
                        if (peerId.value == destinationIdentity.peerId.value) 16 * 1024 else null
                    },
                )
            fixture.routeCoordinator.onPeerConnected(
                peerId = destinationIdentity.peerId,
                trustRecord = trustRecordFor(destinationIdentity),
            )

            // Act
            val shouldAttempt =
                fixture.support.shouldAttemptLargeInlineSend(destinationIdentity.peerId)
            val shouldSkip =
                fixture.support.shouldAttemptLargeInlineSend(PeerId("missing-destination"))

            // Assert
            assertTrue(shouldAttempt)
            assertFalse(shouldSkip)
        }
}

private data class PeerFlowFixture(
    val support: MeshEnginePeerFlowSupport,
    val routeCoordinator: RouteCoordinator,
    val diagnostics: MutableList<RecordedPeerFlowDiagnostic>,
    val forwardedFrames: MutableList<RecordedPeerFlowForward>,
)

private fun peerFlowFixture(
    localIdentity: LocalIdentity,
    runtimeSurface: MeshEngineRuntimeSurface = MeshEngineRuntimeSurface(),
    captureHardRunToken: () -> MeshEngineHardRunToken = { runtimeSurface.captureHardRunToken() },
    sendEncryptedWireFrame:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean =
        { _, _, _, _ ->
            false
        },
    ensureHopSession: suspend (PeerId) -> SessionEstablishmentOutcome = {
        SessionEstablishmentOutcome.Unreachable
    },
    maximumPayloadBytesPerDelivery: (PeerId) -> Int? = { null },
): PeerFlowFixture {
    val routeCoordinator = RouteCoordinator(localIdentity.peerId)
    val diagnostics = mutableListOf<RecordedPeerFlowDiagnostic>()
    val forwardedFrames = mutableListOf<RecordedPeerFlowForward>()
    val support =
        MeshEnginePeerFlowSupport(
            localIdentity = localIdentity,
            context =
                MeshEnginePeerFlowContext(
                    routeCoordinator = routeCoordinator,
                    coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                ),
            config = MeshEnginePeerFlowConfig(largeInlineTransportBudgetBytes = 16 * 1024),
            callbacks =
                MeshEnginePeerFlowCallbacks(
                    runtimeGate = runtimeSurface.runtimeGate,
                    captureHardRunToken = captureHardRunToken,
                    sendEncryptedWireFrame = { peerId, frame, action, hardRunToken ->
                        forwardedFrames +=
                            RecordedPeerFlowForward(
                                peerIdValue = peerId.value,
                                action = action,
                                hardRunEpoch = hardRunToken?.epoch,
                                messageId = (frame as? WireFrame.Message)?.messageId,
                            )
                        sendEncryptedWireFrame(peerId, frame, action, hardRunToken)
                    },
                    ensureHopSession = ensureHopSession,
                    maximumPayloadBytesPerDelivery = maximumPayloadBytesPerDelivery,
                    emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
                        diagnostics +=
                            RecordedPeerFlowDiagnostic(
                                code = code,
                                severity = severity,
                                stage = stage,
                                peerSuffix = peerSuffix,
                                reason = reason,
                                metadata = metadata,
                            )
                    },
                    peerRouteMetadata = { peerId, metadata ->
                        mapOf(
                            "peerId" to peerId.value,
                            "routeAvailable" to
                                (routeCoordinator.routeFor(peerId) != null).toString(),
                        ) + metadata
                    },
                ),
        )
    return PeerFlowFixture(
        support = support,
        routeCoordinator = routeCoordinator,
        diagnostics = diagnostics,
        forwardedFrames = forwardedFrames,
    )
}

private fun trustRecordFor(identity: LocalIdentity): TrustRecord {
    return TrustRecord(
        peerIdValue = identity.peerId.value,
        identityFingerprintBytes = identity.identityFingerprintBytes,
        firstSeenAtEpochMillis = 1L,
        lastVerifiedAtEpochMillis = 1L,
        publicKeys =
            TrustPublicKeys(
                ed25519PublicKey = identity.ed25519PublicKey,
                x25519PublicKey = identity.x25519PublicKey,
            ),
    )
}

private data class RecordedPeerFlowForward(
    val peerIdValue: String,
    val action: String,
    val hardRunEpoch: Long?,
    val messageId: String?,
)

private data class RecordedPeerFlowDiagnostic(
    val code: DiagnosticCode,
    val severity: DiagnosticSeverity,
    val stage: String,
    val peerSuffix: String?,
    val reason: DiagnosticReason?,
    val metadata: Map<String, String>,
)
