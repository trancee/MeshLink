package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.test.InMemorySecureStorage
import ch.trancee.meshlink.trust.TofuTrustStore
import ch.trancee.meshlink.trust.TrustPublicKeys
import ch.trancee.meshlink.trust.TrustRecord
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MeshEngineOutboundRecipientTrustSupportTest {
    @Test
    fun `resolveRecipientTrust returns existing trust when already pinned`() = runBlocking {
        // Arrange
        val localIdentity = LocalIdentity.fromAppId("recipient-trust-local")
        val recipientIdentity = LocalIdentity.fromAppId("recipient-trust-recipient")
        val trustStore = TofuTrustStore(InMemorySecureStorage())
        val existingTrust = trustRecordFor(recipientIdentity)
        trustStore.write(existingTrust)
        val fixture = outboundRecipientTrustFixture(localIdentity, trustStore)

        // Act
        val resolvedTrust = fixture.support.resolveRecipientTrust(recipientIdentity.peerId)

        // Assert
        assertNotNull(resolvedTrust)
        assertContentEquals(
            existingTrust.identityFingerprintBytes,
            resolvedTrust.identityFingerprintBytes,
        )
        assertContentEquals(existingTrust.ed25519PublicKey, resolvedTrust.ed25519PublicKey)
        assertContentEquals(existingTrust.x25519PublicKey, resolvedTrust.x25519PublicKey)
        assertTrue(fixture.diagnostics.isEmpty())
    }

    @Test
    fun `resolveRecipientTrust learns trust from a routed destination when no trust exists`() =
        runBlocking {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("recipient-trust-route-local")
            val relayIdentity = LocalIdentity.fromAppId("recipient-trust-relay")
            val recipientIdentity = LocalIdentity.fromAppId("recipient-trust-routed-recipient")
            val trustStore = TofuTrustStore(InMemorySecureStorage())
            val fixture = outboundRecipientTrustFixture(localIdentity, trustStore)
            fixture.routeCoordinator.onPeerConnected(
                peerId = relayIdentity.peerId,
                trustRecord = trustRecordFor(relayIdentity),
            )
            fixture.routeCoordinator.onRouteUpdate(
                fromPeerId = relayIdentity.peerId,
                update = routeUpdateForIdentity(recipientIdentity, relayIdentity.peerId, seqNo = 1L),
            )

            // Act
            val resolvedTrust = fixture.support.resolveRecipientTrust(recipientIdentity.peerId)
            val persistedTrust = trustStore.read(recipientIdentity.peerId.value)

            // Assert
            assertNotNull(resolvedTrust)
            assertNotNull(persistedTrust)
            assertContentEquals(
                recipientIdentity.identityFingerprintBytes,
                resolvedTrust.identityFingerprintBytes,
            )
            assertContentEquals(recipientIdentity.ed25519PublicKey, resolvedTrust.ed25519PublicKey)
            assertContentEquals(recipientIdentity.x25519PublicKey, resolvedTrust.x25519PublicKey)
            assertContentEquals(
                resolvedTrust.identityFingerprintBytes,
                persistedTrust.identityFingerprintBytes,
            )
            assertEquals(
                listOf(DiagnosticCode.TRUST_ESTABLISHED to "trust.routeUpdate"),
                fixture.diagnostics.map { it.code to it.stage },
            )
        }

    @Test
    fun `resolveRecipientTrust returns null when no trust or route exists`() = runBlocking {
        // Arrange
        val localIdentity = LocalIdentity.fromAppId("recipient-trust-missing-local")
        val trustStore = TofuTrustStore(InMemorySecureStorage())
        val fixture = outboundRecipientTrustFixture(localIdentity, trustStore)

        // Act
        val resolvedTrust = fixture.support.resolveRecipientTrust(PeerId("unknown-recipient"))

        // Assert
        assertNull(resolvedTrust)
        assertTrue(fixture.diagnostics.isEmpty())
    }
}

private data class OutboundRecipientTrustFixture(
    val support: MeshEngineOutboundRecipientTrustSupport,
    val routeCoordinator: RouteCoordinator,
    val diagnostics: MutableList<RecordedOutboundRecipientTrustDiagnostic>,
)

private fun outboundRecipientTrustFixture(
    localIdentity: LocalIdentity,
    trustStore: TofuTrustStore,
): OutboundRecipientTrustFixture {
    val diagnostics = mutableListOf<RecordedOutboundRecipientTrustDiagnostic>()
    val routeCoordinator = RouteCoordinator(localIdentity.peerId)
    return OutboundRecipientTrustFixture(
        support =
            MeshEngineOutboundRecipientTrustSupport(
                localIdentity = localIdentity,
                trustStore = trustStore,
                routeCoordinator = routeCoordinator,
                emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
                    diagnostics +=
                        RecordedOutboundRecipientTrustDiagnostic(
                            code = code,
                            severity = severity,
                            stage = stage,
                            peerSuffix = peerSuffix,
                            reason = reason,
                            metadata = metadata,
                        )
                },
            ),
        routeCoordinator = routeCoordinator,
        diagnostics = diagnostics,
    )
}

private fun routeUpdateForIdentity(
    destinationIdentity: LocalIdentity,
    relayPeerId: PeerId,
    seqNo: Long,
): WireFrame.RouteUpdate {
    return WireFrame.RouteUpdate(
        destinationPeerId = destinationIdentity.peerId,
        nextHopPeerId = relayPeerId,
        metrics = WireFrame.RouteUpdateMetrics(metric = 1, seqNo = seqNo, feasibilityMetric = 1),
        publicKeys =
            WireFrame.RouteUpdatePublicKeys(
                destinationEd25519PublicKey = destinationIdentity.ed25519PublicKey,
                destinationX25519PublicKey = destinationIdentity.x25519PublicKey,
            ),
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

private data class RecordedOutboundRecipientTrustDiagnostic(
    val code: DiagnosticCode,
    val severity: DiagnosticSeverity,
    val stage: String,
    val peerSuffix: String?,
    val reason: DiagnosticReason?,
    val metadata: Map<String, String>,
)
