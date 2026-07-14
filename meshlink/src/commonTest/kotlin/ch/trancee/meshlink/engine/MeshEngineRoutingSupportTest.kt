package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.engine.assembly.MeshEngineRuntimeSurface
import ch.trancee.meshlink.engine.routing.MeshEngineRoutingSupport
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.trust.TrustPublicKeys
import ch.trancee.meshlink.trust.TrustRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

class MeshEngineRoutingSupportTest {
    @Test
    fun `dispatchMutation emits a route available diagnostic when a direct peer appears`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("routing-local")
            val remoteIdentity = LocalIdentity.fromAppId("routing-remote")
            val fixture = routingSupportFixture(localPeerId = localIdentity.peerId)
            val mutation =
                fixture.routeCoordinator.onPeerConnected(
                    peerId = remoteIdentity.peerId,
                    trustRecord = trustRecordFor(remoteIdentity),
                )

            // Act
            fixture.support.dispatchMutation(
                mutation = mutation,
                stage = "routing.connect",
                metadata = mapOf("connectedPeerId" to remoteIdentity.peerId.value),
            )

            // Assert
            assertEquals(1, fixture.diagnostics.size)
            val diagnostic = fixture.diagnostics.single()
            assertEquals(DiagnosticCode.ROUTE_DISCOVERED, diagnostic.code)
            assertEquals("routing.connect.routeAvailable", diagnostic.stage)
            assertEquals(DiagnosticReason.ROUTE_CHANGE, diagnostic.reason)
            assertEquals(remoteIdentity.peerId.value, diagnostic.metadata["peerId"])
            assertEquals("available", diagnostic.metadata["routeChange"])
            assertEquals("true", diagnostic.metadata["routeAvailable"])
        }

    @Test
    fun `dispatchMutation emits diagnostics before routing advertisements for a direct peer update`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("routing-local-order")
            val remoteIdentity = LocalIdentity.fromAppId("routing-remote-order")
            val runtimeSurface = MeshEngineRuntimeSurface().also { it.beginHardRun() }
            val fixture =
                routingSupportFixture(
                    localPeerId = localIdentity.peerId,
                    runtimeSurface = runtimeSurface,
                )
            val mutation =
                fixture.routeCoordinator.onPeerConnected(
                    peerId = remoteIdentity.peerId,
                    trustRecord = trustRecordFor(remoteIdentity),
                )

            // Act
            fixture.support.dispatchMutation(
                mutation = mutation,
                stage = "routing.connect",
                metadata = mapOf("connectedPeerId" to remoteIdentity.peerId.value),
            )

            // Assert
            val diagnosticEventIndex =
                fixture.eventLog.indexOfFirst { it.startsWith("diagnostic:") }
            val advertisementEventIndex =
                fixture.eventLog.indexOfFirst { it.startsWith("advertisement:") }
            assertTrue(diagnosticEventIndex >= 0, "Expected route diagnostics to be recorded")
            assertTrue(
                advertisementEventIndex >= 0,
                "Expected routing advertisements to be recorded",
            )
            assertTrue(
                diagnosticEventIndex < advertisementEventIndex,
                "Expected diagnostics before advertisements, but saw ${fixture.eventLog}",
            )
        }

    @Test
    fun `dispatchMutation sends routing advertisements while the hard run is active`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("routing-local")
            val firstPeer = LocalIdentity.fromAppId("routing-first")
            val secondPeer = LocalIdentity.fromAppId("routing-second")
            val runtimeSurface = MeshEngineRuntimeSurface()
            runtimeSurface.beginHardRun()
            val fixture =
                routingSupportFixture(
                    localPeerId = localIdentity.peerId,
                    runtimeSurface = runtimeSurface,
                )
            fixture.routeCoordinator.onPeerConnected(
                peerId = firstPeer.peerId,
                trustRecord = trustRecordFor(firstPeer),
            )
            val mutation =
                fixture.routeCoordinator.onPeerConnected(
                    peerId = secondPeer.peerId,
                    trustRecord = trustRecordFor(secondPeer),
                )

            // Act
            fixture.support.dispatchMutation(mutation = mutation, stage = "routing.connect")

            // Assert
            assertTrue(
                fixture.sentAdvertisements.any { advertisement ->
                    advertisement.targetPeerIdValue == firstPeer.peerId.value &&
                        advertisement.action == "routing.advertise" &&
                        advertisement.frameType == "RouteUpdate"
                }
            )
            assertTrue(
                fixture.sentAdvertisements.any { advertisement ->
                    advertisement.targetPeerIdValue == secondPeer.peerId.value &&
                        advertisement.action == "routing.advertise" &&
                        advertisement.frameType == "RouteDigest"
                }
            )
        }

    @Test
    fun `dispatchMutation skips routing advertisements once the hard run has ended`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("routing-local")
            val firstPeer = LocalIdentity.fromAppId("routing-first")
            val secondPeer = LocalIdentity.fromAppId("routing-second")
            val fixture = routingSupportFixture(localPeerId = localIdentity.peerId)
            fixture.routeCoordinator.onPeerConnected(
                peerId = firstPeer.peerId,
                trustRecord = trustRecordFor(firstPeer),
            )
            val mutation =
                fixture.routeCoordinator.onPeerConnected(
                    peerId = secondPeer.peerId,
                    trustRecord = trustRecordFor(secondPeer),
                )

            // Act
            fixture.support.dispatchMutation(mutation = mutation, stage = "routing.connect")

            // Assert
            assertTrue(fixture.sentAdvertisements.isEmpty())
        }

    @Test
    fun `dispatchMutation emits route retracted diagnostics when a direct peer disappears`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("routing-local")
            val remoteIdentity = LocalIdentity.fromAppId("routing-remote")
            val observerIdentity = LocalIdentity.fromAppId("routing-observer")
            val runtimeSurface = MeshEngineRuntimeSurface().also { it.beginHardRun() }
            val fixture =
                routingSupportFixture(
                    localPeerId = localIdentity.peerId,
                    runtimeSurface = runtimeSurface,
                )
            fixture.routeCoordinator.onPeerConnected(
                peerId = remoteIdentity.peerId,
                trustRecord = trustRecordFor(remoteIdentity),
            )
            fixture.routeCoordinator.onPeerConnected(
                peerId = observerIdentity.peerId,
                trustRecord = trustRecordFor(observerIdentity),
            )
            val mutation = fixture.routeCoordinator.onPeerDisconnected(remoteIdentity.peerId)

            // Act
            fixture.support.dispatchMutation(
                mutation = mutation,
                stage = "routing.disconnect",
                removalCode = DiagnosticCode.ROUTE_RETRACTED,
                metadata = mapOf("removedByPeerId" to remoteIdentity.peerId.value),
            )

            // Assert
            assertEquals(1, fixture.diagnostics.size)
            val diagnostic = fixture.diagnostics.single()
            assertEquals(DiagnosticCode.ROUTE_RETRACTED, diagnostic.code)
            assertEquals("routing.disconnect.routeRetracted", diagnostic.stage)
            assertEquals(DiagnosticReason.ROUTE_CHANGE, diagnostic.reason)
            assertEquals("retracted", diagnostic.metadata["routeChange"])
            assertEquals(remoteIdentity.peerId.value, diagnostic.metadata["removedByPeerId"])
            val diagnosticEventIndex =
                fixture.eventLog.indexOfFirst { it.startsWith("diagnostic:") }
            val advertisementEventIndex =
                fixture.eventLog.indexOfFirst { it.startsWith("advertisement:") }
            assertTrue(diagnosticEventIndex >= 0, "Expected route diagnostics to be recorded")
            assertTrue(
                advertisementEventIndex >= 0,
                "Expected routing advertisements to be recorded",
            )
            assertTrue(
                diagnosticEventIndex < advertisementEventIndex,
                "Expected diagnostics before advertisements, but saw ${fixture.eventLog}",
            )
        }
}

private data class RoutingSupportFixture(
    val support: MeshEngineRoutingSupport,
    val routeCoordinator: RouteCoordinator,
    val diagnostics: MutableList<RecordedRoutingDiagnostic>,
    val sentAdvertisements: MutableList<RecordedRoutingAdvertisement>,
    val eventLog: MutableList<String>,
)

private fun routingSupportFixture(
    localPeerId: PeerId,
    runtimeSurface: MeshEngineRuntimeSurface = MeshEngineRuntimeSurface(),
): RoutingSupportFixture {
    val routeCoordinator = RouteCoordinator(localPeerId)
    val diagnostics = mutableListOf<RecordedRoutingDiagnostic>()
    val sentAdvertisements = mutableListOf<RecordedRoutingAdvertisement>()
    val eventLog = mutableListOf<String>()
    val support =
        MeshEngineRoutingSupport(
            routeCoordinator = routeCoordinator,
            runtimeGate = runtimeSurface.runtimeGate,
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
                eventLog += "diagnostic:${code.name}"
                diagnostics +=
                    RecordedRoutingDiagnostic(
                        code = code,
                        severity = severity,
                        stage = stage,
                        peerSuffix = peerSuffix,
                        reason = reason,
                        metadata = metadata,
                    )
            },
            sendEncryptedWireFrame = { peerId, frame, action, _ ->
                eventLog += "advertisement:$action"
                sentAdvertisements +=
                    RecordedRoutingAdvertisement(
                        targetPeerIdValue = peerId.value,
                        action = action,
                        frameType = frame::class.simpleName.orEmpty(),
                    )
                true
            },
        )
    return RoutingSupportFixture(
        support = support,
        routeCoordinator = routeCoordinator,
        diagnostics = diagnostics,
        sentAdvertisements = sentAdvertisements,
        eventLog = eventLog,
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

private data class RecordedRoutingAdvertisement(
    val targetPeerIdValue: String,
    val action: String,
    val frameType: String,
)

private data class RecordedRoutingDiagnostic(
    val code: DiagnosticCode,
    val severity: DiagnosticSeverity,
    val stage: String,
    val peerSuffix: String?,
    val reason: DiagnosticReason?,
    val metadata: Map<String, String>,
)
