package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.config.meshLinkConfig
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.test.InMemorySecureStorage
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.trust.TofuTrustStore
import ch.trancee.meshlink.trust.TrustPublicKeys
import ch.trancee.meshlink.trust.TrustRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

class MeshEngineRuntimeFoundationAssemblyTest {
    @Test
    fun `foundation assembly routing support uses the registered late-binding sender`() =
        runBlocking<Unit> {
            // Arrange
            val harness = runtimeFoundationAssemblyHarness()
            harness.runtimeSurface.beginHardRun()
            val remoteIdentity = LocalIdentity.fromAppId("foundation-remote")
            harness.lateBindingContext.registerRoutingAdvertisementSender {
                peerId,
                frame,
                action,
                hardRunToken ->
                harness.sentAdvertisements +=
                    RecordedFoundationAdvertisement(
                        targetPeerIdValue = peerId.value,
                        action = action,
                        frameType = frame::class.simpleName.orEmpty(),
                        hardRunEpoch = hardRunToken?.epoch,
                    )
                true
            }
            val mutation =
                harness.foundation.sharedState.routeCoordinator.onPeerConnected(
                    remoteIdentity.peerId,
                    trustRecordFor(remoteIdentity),
                )

            // Act
            harness.foundation.routingAndTrust.routingSupport.dispatchMutation(
                mutation = mutation,
                stage = "routing.connect",
            )

            // Assert
            assertEquals(
                listOf(
                    RecordedFoundationAdvertisement(
                        targetPeerIdValue = remoteIdentity.peerId.value,
                        action = "routing.advertise",
                        frameType = "RouteDigest",
                        hardRunEpoch = null,
                    )
                ),
                harness.sentAdvertisements,
            )
            assertTrue(
                harness.diagnostics.any { diagnostic ->
                    diagnostic.stage == "routing.connect.routeAvailable" &&
                        diagnostic.metadata["peerId"] == remoteIdentity.peerId.value
                }
            )
        }

    @Test
    fun `foundation assembly retry diagnostic reflects the configured deadline`() =
        runBlocking<Unit> {
            // Arrange
            val harness = runtimeFoundationAssemblyHarness()
            val peerId = PeerId("peer-abcdef")

            // Act
            harness.foundation.routingAndTrust.scheduleRetryDiagnostic(
                peerId,
                DeliveryPriority.HIGH,
            )

            // Assert
            assertEquals(1, harness.diagnostics.size)
            val diagnostic = harness.diagnostics.single()
            assertEquals("delivery.noRoute", diagnostic.stage)
            assertEquals("HIGH", diagnostic.metadata["priority"])
            assertEquals("9000", diagnostic.metadata["retryDeadlineMs"])
            assertEquals("250", diagnostic.metadata["retryBackoffBaseMs"])
            assertEquals("false", diagnostic.metadata["routeAvailable"])
            assertEquals("0", diagnostic.metadata["topologyVersion"])
        }
}

private data class RuntimeFoundationAssemblyHarness(
    val foundation: MeshEngineRuntimeFoundationAssembly,
    val lateBindingContext: MeshEngineRuntimeLateBindingContext,
    val runtimeSurface: MeshEngineRuntimeSurface,
    val diagnostics: MutableList<RecordedFoundationDiagnostic>,
    val sentAdvertisements: MutableList<RecordedFoundationAdvertisement>,
)

private fun runtimeFoundationAssemblyHarness(): RuntimeFoundationAssemblyHarness {
    val runtimeSurface = MeshEngineRuntimeSurface()
    val diagnostics = mutableListOf<RecordedFoundationDiagnostic>()
    val sentAdvertisements = mutableListOf<RecordedFoundationAdvertisement>()
    val lateBindingContext = MeshEngineRuntimeLateBindingContext()
    val environment =
        MeshEngineRuntimeAssemblyEnvironment(
            config =
                meshLinkConfig {
                    appId = "runtime-foundation"
                    deliveryRetryDeadline = 9.seconds
                },
            localIdentity = LocalIdentity.fromAppId("runtime-foundation-local"),
            trustStore = TofuTrustStore(InMemorySecureStorage()),
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            platformBridge = MeshEnginePlatformBridge(NoOpFoundationAssemblyBleTransport()),
            publishedSurface = runtimeSurface,
            compatibilitySurface = runtimeSurface,
        )
    val support =
        MeshEngineRuntimeAssemblySupport(
            emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
                diagnostics +=
                    RecordedFoundationDiagnostic(
                        code = code,
                        stage = stage,
                        peerSuffix = peerSuffix,
                        reason = reason,
                        metadata = metadata,
                    )
                runtimeSurface.emitDiagnostic(code, severity, stage, peerSuffix, reason, metadata)
            },
            sendDirectWireFrame = { _, _, _, _ -> TransportSendResult.Delivered },
        )
    val foundation =
        buildMeshEngineRuntimeFoundationAssembly(
            environment = environment,
            support = support,
            lateBindingContext = lateBindingContext,
        )
    return RuntimeFoundationAssemblyHarness(
        foundation = foundation,
        lateBindingContext = lateBindingContext,
        runtimeSurface = runtimeSurface,
        diagnostics = diagnostics,
        sentAdvertisements = sentAdvertisements,
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

private data class RecordedFoundationAdvertisement(
    val targetPeerIdValue: String,
    val action: String,
    val frameType: String,
    val hardRunEpoch: Long?,
)

private data class RecordedFoundationDiagnostic(
    val code: ch.trancee.meshlink.diagnostics.DiagnosticCode,
    val stage: String,
    val peerSuffix: String?,
    val reason: ch.trancee.meshlink.diagnostics.DiagnosticReason?,
    val metadata: Map<String, String>,
)

private class NoOpFoundationAssemblyBleTransport : BleTransport {
    override val events: Flow<TransportEvent> = emptyFlow()

    override suspend fun start(): Unit = Unit

    override suspend fun pause(): Unit = Unit

    override suspend fun resume(): Unit = Unit

    override suspend fun stop(): Unit = Unit

    override suspend fun send(frame: OutboundFrame): TransportSendResult =
        TransportSendResult.Delivered
}
