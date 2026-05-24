package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.platform.currentEpochMillis
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.routing.RouteEntry
import ch.trancee.meshlink.trust.TofuTrustStore
import ch.trancee.meshlink.trust.TrustPublicKeys
import ch.trancee.meshlink.trust.TrustRecord

internal class MeshEngineOutboundRecipientTrustSupport(
    private val localIdentity: LocalIdentity,
    private val trustStore: TofuTrustStore,
    private val routeCoordinator: RouteCoordinator,
    private val emitDiagnostic:
        (
            DiagnosticCode,
            DiagnosticSeverity,
            String,
            String?,
            DiagnosticReason?,
            Map<String, String>,
        ) -> Unit,
) {
    suspend fun resolveRecipientTrust(peerId: PeerId): TrustRecord? {
        val existingTrust = trustStore.read(peerId.value)
        val route = routeCoordinator.routeFor(peerId)

        return when {
            existingTrust != null -> existingTrust
            route == null -> null
            else -> learnRecipientTrustFromRoute(peerId = peerId, route = route)
        }
    }

    private suspend fun learnRecipientTrustFromRoute(
        peerId: PeerId,
        route: RouteEntry,
    ): TrustRecord {
        val learnedAtEpochMillis = currentEpochMillis()
        val learnedTrust =
            TrustRecord(
                peerIdValue = route.destinationPeerId.value,
                identityFingerprintBytes =
                    localIdentity.cryptoProvider.sha256(
                        route.ed25519PublicKey + route.x25519PublicKey
                    ),
                firstSeenAtEpochMillis = learnedAtEpochMillis,
                lastVerifiedAtEpochMillis = learnedAtEpochMillis,
                publicKeys =
                    TrustPublicKeys(
                        ed25519PublicKey = route.ed25519PublicKey,
                        x25519PublicKey = route.x25519PublicKey,
                    ),
            )
        trustStore.write(learnedTrust)
        emitDiagnostic(
            DiagnosticCode.TRUST_ESTABLISHED,
            DiagnosticSeverity.INFO,
            "trust.routeUpdate",
            peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
            DiagnosticReason.STATE_CHANGE,
            emptyMap(),
        )
        return learnedTrust
    }
}

internal fun buildMeshEngineRuntimeOutboundRecipientTrustSupport(
    localIdentity: LocalIdentity,
    trustStore: TofuTrustStore,
    routeCoordinator: RouteCoordinator,
    emitDiagnostic:
        (
            DiagnosticCode,
            DiagnosticSeverity,
            String,
            String?,
            DiagnosticReason?,
            Map<String, String>,
        ) -> Unit,
): MeshEngineOutboundRecipientTrustSupport {
    return MeshEngineOutboundRecipientTrustSupport(
        localIdentity = localIdentity,
        trustStore = trustStore,
        routeCoordinator = routeCoordinator,
        emitDiagnostic = emitDiagnostic,
    )
}
