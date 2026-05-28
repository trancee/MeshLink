package ch.trancee.meshlink.piolium

import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.engine.MeshEngineOutboundRecipientTrustSupport
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.identity.toHexString
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.test.InMemorySecureStorage
import ch.trancee.meshlink.trust.TofuTrustStore
import ch.trancee.meshlink.trust.TrustPublicKeys
import ch.trancee.meshlink.trust.TrustRecord
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Confirm H1: route updates seed durable recipient trust before authenticated first contact. */
class H1RouteSeededTofuTrustConfirm441bfd37Test {
    @Test
    fun test_confirm_route_seeded_tofu_trust_441bfd37() = runBlocking {
        withTimeout(60_000) {
            // Arrange
            val victimIdentity = LocalIdentity.fromAppId("piolium.h1.victim")
            val attackerRelayIdentity = LocalIdentity.fromAppId("piolium.h1.attacker-relay")
            val claimedRecipientIdentity = LocalIdentity.fromAppId("piolium.h1.claimed-recipient")
            val attackerChosenIdentity = LocalIdentity.fromAppId("piolium.h1.attacker-chosen-keys")
            val trustStore = TofuTrustStore(InMemorySecureStorage())
            val routeCoordinator = RouteCoordinator(victimIdentity.peerId)
            val diagnostics = mutableListOf<Pair<DiagnosticCode, String>>()
            val support =
                MeshEngineOutboundRecipientTrustSupport(
                    localIdentity = victimIdentity,
                    trustStore = trustStore,
                    routeCoordinator = routeCoordinator,
                    emitDiagnostic = { code, _, stage, _, _, _ -> diagnostics += code to stage },
                )
            routeCoordinator.onPeerConnected(
                peerId = attackerRelayIdentity.peerId,
                trustRecord = trustRecordFor(attackerRelayIdentity),
            )
            routeCoordinator.onRouteUpdate(
                fromPeerId = attackerRelayIdentity.peerId,
                update =
                    WireFrame.RouteUpdate(
                        destinationPeerId = claimedRecipientIdentity.peerId,
                        nextHopPeerId = attackerRelayIdentity.peerId,
                        metrics =
                            WireFrame.RouteUpdateMetrics(
                                metric = 1,
                                seqNo = 1L,
                                feasibilityMetric = 1,
                            ),
                        publicKeys =
                            WireFrame.RouteUpdatePublicKeys(
                                destinationEd25519PublicKey =
                                    attackerChosenIdentity.ed25519PublicKey,
                                destinationX25519PublicKey = attackerChosenIdentity.x25519PublicKey,
                            ),
                    ),
            )

            // Act
            val resolvedTrust = support.resolveRecipientTrust(claimedRecipientIdentity.peerId)
            val persistedTrust = trustStore.read(claimedRecipientIdentity.peerId.value)

            // Assert
            assertNotNull(
                resolvedTrust,
                "The routed first contact should learn trust from attacker-controlled route metadata",
            )
            assertNotNull(persistedTrust, "The learned trust should be persisted for future sends")
            assertEquals(claimedRecipientIdentity.peerId.value, resolvedTrust.peerIdValue)
            assertEquals(claimedRecipientIdentity.peerId.value, persistedTrust.peerIdValue)
            assertContentEquals(
                attackerChosenIdentity.identityFingerprintBytes,
                resolvedTrust.identityFingerprintBytes,
            )
            assertContentEquals(
                attackerChosenIdentity.ed25519PublicKey,
                resolvedTrust.ed25519PublicKey,
            )
            assertContentEquals(
                attackerChosenIdentity.x25519PublicKey,
                resolvedTrust.x25519PublicKey,
            )
            assertFalse(
                resolvedTrust.ed25519PublicKey.contentEquals(
                    claimedRecipientIdentity.ed25519PublicKey
                ),
                "The claimed destination should not have been replaced by attacker keys",
            )
            assertFalse(
                resolvedTrust.x25519PublicKey.contentEquals(
                    claimedRecipientIdentity.x25519PublicKey
                ),
                "The claimed destination should not have been replaced by attacker keys",
            )
            assertEquals(
                listOf(DiagnosticCode.TRUST_ESTABLISHED to "trust.routeUpdate"),
                diagnostics,
            )
            println(
                "CONFIRM H1 claimedPeer=${claimedRecipientIdentity.peerId.value} persistedPeer=${persistedTrust.peerIdValue} attackerFingerprint=${attackerChosenIdentity.identityFingerprintBytes.toHexString()} persistedFingerprint=${persistedTrust.identityFingerprintBytes.toHexString()} matchesClaimed=${persistedTrust.identityFingerprintBytes.contentEquals(claimedRecipientIdentity.identityFingerprintBytes)}"
            )
        }
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
}
