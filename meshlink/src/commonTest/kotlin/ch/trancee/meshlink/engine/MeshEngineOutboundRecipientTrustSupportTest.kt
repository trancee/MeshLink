package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.engine.handshake.EndToEndSession
import ch.trancee.meshlink.engine.handshake.EndToEndSessionEstablishmentOutcome
import ch.trancee.meshlink.engine.transfer.MeshEngineOutboundRecipientTrustSupport
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.test.InMemorySecureStorage
import ch.trancee.meshlink.trust.TofuTrustStore
import ch.trancee.meshlink.trust.TrustPublicKeys
import ch.trancee.meshlink.trust.TrustRecord
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MeshEngineOutboundRecipientTrustSupportTest {
    @Test
    fun `resolveRecipientTrust returns existing trust without attempting a handshake`() =
        runBlocking<Unit> {
            // Arrange
            val recipientIdentity = LocalIdentity.fromAppId("recipient-trust-recipient")
            val trustStore = TofuTrustStore(InMemorySecureStorage())
            val existingTrust = trustRecordFor(recipientIdentity)
            trustStore.write(existingTrust)
            val ensureEndToEndSessionCalls = mutableListOf<String>()
            val support =
                MeshEngineOutboundRecipientTrustSupport(
                    trustStore = trustStore,
                    ensureEndToEndSession = { peerId ->
                        ensureEndToEndSessionCalls += peerId.value
                        EndToEndSessionEstablishmentOutcome.Unreachable
                    },
                )

            // Act
            val resolvedTrust = support.resolveRecipientTrust(recipientIdentity.peerId)

            // Assert
            assertNotNull(resolvedTrust)
            assertContentEquals(
                existingTrust.identityFingerprintBytes,
                resolvedTrust.identityFingerprintBytes,
            )
            assertContentEquals(existingTrust.ed25519PublicKey, resolvedTrust.ed25519PublicKey)
            assertContentEquals(existingTrust.x25519PublicKey, resolvedTrust.x25519PublicKey)
            assertTrue(ensureEndToEndSessionCalls.isEmpty())
        }

    @Test
    fun `resolveRecipientTrust reads the freshly pinned trust after a successful handshake`() =
        runBlocking<Unit> {
            // Arrange
            val recipientIdentity = LocalIdentity.fromAppId("recipient-trust-handshake-recipient")
            val trustStore = TofuTrustStore(InMemorySecureStorage())
            val support =
                MeshEngineOutboundRecipientTrustSupport(
                    trustStore = trustStore,
                    ensureEndToEndSession = { _ ->
                        // Simulates the handshake support pinning trust as a side effect of a
                        // successfully completed and cryptographically authenticated handshake.
                        trustStore.write(trustRecordFor(recipientIdentity))
                        EndToEndSessionEstablishmentOutcome.Established(
                            EndToEndSession(ByteArray(32), ByteArray(32))
                        )
                    },
                )

            // Act
            val resolvedTrust = support.resolveRecipientTrust(recipientIdentity.peerId)

            // Assert
            assertNotNull(resolvedTrust)
            assertEquals(recipientIdentity.peerId.value, resolvedTrust.peerIdValue)
        }

    @Test
    fun `resolveRecipientTrust returns null when the handshake is unreachable`() =
        runBlocking<Unit> {
            // Arrange
            val trustStore = TofuTrustStore(InMemorySecureStorage())
            val support =
                MeshEngineOutboundRecipientTrustSupport(
                    trustStore = trustStore,
                    ensureEndToEndSession = { EndToEndSessionEstablishmentOutcome.Unreachable },
                )

            // Act
            val resolvedTrust = support.resolveRecipientTrust(PeerId("unknown-recipient"))

            // Assert
            assertNull(resolvedTrust)
        }

    @Test
    fun `resolveRecipientTrust returns null when the handshake fails trust verification`() =
        runBlocking<Unit> {
            // Arrange
            val trustStore = TofuTrustStore(InMemorySecureStorage())
            val support =
                MeshEngineOutboundRecipientTrustSupport(
                    trustStore = trustStore,
                    ensureEndToEndSession = { EndToEndSessionEstablishmentOutcome.TrustFailure },
                )

            // Act
            val resolvedTrust = support.resolveRecipientTrust(PeerId("conflicting-recipient"))

            // Assert
            assertNull(resolvedTrust)
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
