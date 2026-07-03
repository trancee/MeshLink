package ch.trancee.meshlink.piolium

import ch.trancee.meshlink.engine.EndToEndSessionEstablishmentOutcome
import ch.trancee.meshlink.engine.MeshEngineOutboundRecipientTrustSupport
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.test.InMemorySecureStorage
import ch.trancee.meshlink.trust.TofuTrustStore
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Regression for H1: route-gossiped destination keys can no longer seed recipient trust. Trust is
 * only ever pinned as the outcome of a cryptographically authenticated end-to-end Noise XX
 * handshake ([EndToEndSessionEstablishmentOutcome.Established]); route metadata (which any relay on
 * the path can forge) is not consulted at all by [MeshEngineOutboundRecipientTrustSupport] anymore.
 */
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
            var ensureEndToEndSessionCalls = 0
            val support =
                MeshEngineOutboundRecipientTrustSupport(
                    trustStore = trustStore,
                    ensureEndToEndSession = {
                        // Simulates the attacker-controlled relay never actually completing a
                        // cryptographically authenticated handshake with the claimed recipient.
                        ensureEndToEndSessionCalls++
                        EndToEndSessionEstablishmentOutcome.Unreachable
                    },
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
            assertNull(
                resolvedTrust,
                "Route-gossiped attacker keys must never be resolved as recipient trust",
            )
            assertNull(persistedTrust, "No trust should be persisted from route metadata alone")
            println(
                "CONFIRM H1 fixed: claimedPeer=${claimedRecipientIdentity.peerId.value} " +
                    "ensureEndToEndSessionCalls=$ensureEndToEndSessionCalls " +
                    "resolvedTrust=$resolvedTrust persistedTrust=$persistedTrust"
            )
        }
    }
}
