package ch.trancee.meshlink.engine

import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.engine.trust.MeshEngineTrustSupport
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

class MeshEngineTrustSupportTest {
    @Test
    fun `verifyAndPersistTrust pins new trust when the remote fingerprint matches`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("trust-local")
            val remoteIdentity = LocalIdentity.fromAppId("trust-remote")
            val storage = InMemorySecureStorage()
            val trustStore = TofuTrustStore(storage)
            val diagnostics = mutableListOf<RecordedTrustDiagnostic>()
            val support =
                trustSupport(
                    localIdentity = localIdentity,
                    trustStore = trustStore,
                    diagnostics = diagnostics,
                )

            // Act
            val verified =
                support.verifyAndPersistTrust(
                    peerId = remoteIdentity.peerId,
                    remoteEd25519PublicKey = remoteIdentity.ed25519PublicKey,
                    remoteX25519PublicKey = remoteIdentity.x25519PublicKey,
                    expectedFingerprintBytes = remoteIdentity.identityFingerprintBytes,
                )
            val persisted = trustStore.read(remoteIdentity.peerId.value)

            // Assert
            assertNotNull(verified)
            assertNotNull(persisted)
            assertEquals(remoteIdentity.peerId.value, verified.peerIdValue)
            assertContentEquals(
                remoteIdentity.identityFingerprintBytes,
                verified.identityFingerprintBytes,
            )
            assertContentEquals(
                remoteIdentity.identityFingerprintBytes,
                persisted.identityFingerprintBytes,
            )
            assertEquals(verified.firstSeenAtEpochMillis, verified.lastVerifiedAtEpochMillis)
            assertTrue(
                diagnostics.any { diagnostic ->
                    diagnostic.code == DiagnosticCode.TRUST_ESTABLISHED &&
                        diagnostic.stage == "trust.pin" &&
                        diagnostic.reason == DiagnosticReason.STATE_CHANGE
                }
            )
        }

    @Test
    fun `verifyAndPersistTrust rejects mismatched expected fingerprints`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("trust-local")
            val remoteIdentity = LocalIdentity.fromAppId("trust-remote")
            val trustStore = TofuTrustStore(InMemorySecureStorage())
            val diagnostics = mutableListOf<RecordedTrustDiagnostic>()
            val support =
                trustSupport(
                    localIdentity = localIdentity,
                    trustStore = trustStore,
                    diagnostics = diagnostics,
                )

            // Act
            val verified =
                support.verifyAndPersistTrust(
                    peerId = remoteIdentity.peerId,
                    remoteEd25519PublicKey = remoteIdentity.ed25519PublicKey,
                    remoteX25519PublicKey = remoteIdentity.x25519PublicKey,
                    expectedFingerprintBytes = byteArrayOf(0x00),
                )
            val persisted = trustStore.read(remoteIdentity.peerId.value)

            // Assert
            assertNull(verified)
            assertNull(persisted)
            assertTrue(
                diagnostics.any { diagnostic ->
                    diagnostic.code == DiagnosticCode.TRUST_FAILURE &&
                        diagnostic.stage == "trust.verify.fingerprint" &&
                        diagnostic.reason == DiagnosticReason.TRUST_FAILURE
                }
            )
        }

    @Test
    fun `verifyAndPersistTrust rejects existing trust that no longer matches the remote identity`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("trust-local")
            val remoteIdentity = LocalIdentity.fromAppId("trust-remote")
            val staleIdentity = LocalIdentity.fromAppId("trust-stale")
            val trustStore = TofuTrustStore(InMemorySecureStorage())
            trustStore.write(
                TrustRecord(
                    peerIdValue = remoteIdentity.peerId.value,
                    identityFingerprintBytes = staleIdentity.identityFingerprintBytes,
                    firstSeenAtEpochMillis = 10L,
                    lastVerifiedAtEpochMillis = 20L,
                    publicKeys =
                        TrustPublicKeys(
                            ed25519PublicKey = staleIdentity.ed25519PublicKey,
                            x25519PublicKey = staleIdentity.x25519PublicKey,
                        ),
                )
            )
            val diagnostics = mutableListOf<RecordedTrustDiagnostic>()
            val support =
                trustSupport(
                    localIdentity = localIdentity,
                    trustStore = trustStore,
                    diagnostics = diagnostics,
                )

            // Act
            val verified =
                support.verifyAndPersistTrust(
                    peerId = remoteIdentity.peerId,
                    remoteEd25519PublicKey = remoteIdentity.ed25519PublicKey,
                    remoteX25519PublicKey = remoteIdentity.x25519PublicKey,
                )
            val persisted = trustStore.read(remoteIdentity.peerId.value)

            // Assert
            assertNull(verified)
            assertNotNull(persisted)
            assertContentEquals(
                staleIdentity.identityFingerprintBytes,
                persisted.identityFingerprintBytes,
            )
            assertEquals(20L, persisted.lastVerifiedAtEpochMillis)
            assertTrue(
                diagnostics.any { diagnostic ->
                    diagnostic.code == DiagnosticCode.TRUST_FAILURE &&
                        diagnostic.stage == "trust.verify" &&
                        diagnostic.reason == DiagnosticReason.TRUST_FAILURE
                }
            )
        }

    @Test
    fun `verifyAndPersistTrust refreshes matching trust without changing first seen`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("trust-local")
            val remoteIdentity = LocalIdentity.fromAppId("trust-remote")
            val trustStore = TofuTrustStore(InMemorySecureStorage())
            trustStore.write(
                TrustRecord(
                    peerIdValue = remoteIdentity.peerId.value,
                    identityFingerprintBytes = remoteIdentity.identityFingerprintBytes,
                    firstSeenAtEpochMillis = 10L,
                    lastVerifiedAtEpochMillis = 20L,
                    publicKeys =
                        TrustPublicKeys(
                            ed25519PublicKey = remoteIdentity.ed25519PublicKey,
                            x25519PublicKey = remoteIdentity.x25519PublicKey,
                        ),
                )
            )
            val diagnostics = mutableListOf<RecordedTrustDiagnostic>()
            val support =
                trustSupport(
                    localIdentity = localIdentity,
                    trustStore = trustStore,
                    diagnostics = diagnostics,
                )

            // Act
            val verified =
                support.verifyAndPersistTrust(
                    peerId = remoteIdentity.peerId,
                    remoteEd25519PublicKey = remoteIdentity.ed25519PublicKey,
                    remoteX25519PublicKey = remoteIdentity.x25519PublicKey,
                )
            val persisted = trustStore.read(remoteIdentity.peerId.value)

            // Assert
            assertNotNull(verified)
            assertNotNull(persisted)
            assertEquals(10L, verified.firstSeenAtEpochMillis)
            assertEquals(10L, persisted.firstSeenAtEpochMillis)
            assertTrue(verified.lastVerifiedAtEpochMillis >= 20L)
            assertTrue(persisted.lastVerifiedAtEpochMillis >= 20L)
            assertTrue(diagnostics.isEmpty())
        }

    @Test
    fun `verifyEstablishedTrust rejects a peer with no existing trust record`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("trust-local")
            val remoteIdentity = LocalIdentity.fromAppId("trust-remote-untrusted")
            val trustStore = TofuTrustStore(InMemorySecureStorage())
            val diagnostics = mutableListOf<RecordedTrustDiagnostic>()
            val support =
                trustSupport(
                    localIdentity = localIdentity,
                    trustStore = trustStore,
                    diagnostics = diagnostics,
                )

            // Act
            val verified = support.verifyEstablishedTrust(remoteIdentity.peerId)
            val persisted = trustStore.read(remoteIdentity.peerId.value)

            // Assert
            assertNull(verified)
            assertNull(persisted, "First contact must never pin trust via this method")
            assertTrue(
                diagnostics.any { diagnostic ->
                    diagnostic.code == DiagnosticCode.TRUST_FAILURE &&
                        diagnostic.stage == "trust.verify.untrusted"
                }
            )
        }

    @Test
    fun `verifyEstablishedTrust refreshes trust that matches an existing record`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("trust-local")
            val remoteIdentity = LocalIdentity.fromAppId("trust-remote-established")
            val trustStore = TofuTrustStore(InMemorySecureStorage())
            trustStore.write(
                TrustRecord(
                    peerIdValue = remoteIdentity.peerId.value,
                    identityFingerprintBytes = remoteIdentity.identityFingerprintBytes,
                    firstSeenAtEpochMillis = 10L,
                    lastVerifiedAtEpochMillis = 20L,
                    publicKeys =
                        TrustPublicKeys(
                            ed25519PublicKey = remoteIdentity.ed25519PublicKey,
                            x25519PublicKey = remoteIdentity.x25519PublicKey,
                        ),
                )
            )
            val diagnostics = mutableListOf<RecordedTrustDiagnostic>()
            val support =
                trustSupport(
                    localIdentity = localIdentity,
                    trustStore = trustStore,
                    diagnostics = diagnostics,
                )

            // Act
            val verified = support.verifyEstablishedTrust(remoteIdentity.peerId)
            val persisted = trustStore.read(remoteIdentity.peerId.value)

            // Assert
            assertNotNull(verified)
            assertNotNull(persisted)
            assertEquals(10L, verified.firstSeenAtEpochMillis)
            assertTrue(verified.lastVerifiedAtEpochMillis >= 20L)
            assertTrue(diagnostics.isEmpty())
        }
}

private fun trustSupport(
    localIdentity: LocalIdentity,
    trustStore: TofuTrustStore,
    diagnostics: MutableList<RecordedTrustDiagnostic>,
): MeshEngineTrustSupport {
    return MeshEngineTrustSupport(
        localIdentity = localIdentity,
        trustStore = trustStore,
        emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
            diagnostics +=
                RecordedTrustDiagnostic(
                    code = code,
                    severity = severity,
                    stage = stage,
                    peerSuffix = peerSuffix,
                    reason = reason,
                    metadata = metadata,
                )
        },
    )
}

private data class RecordedTrustDiagnostic(
    val code: DiagnosticCode,
    val severity: DiagnosticSeverity,
    val stage: String,
    val peerSuffix: String?,
    val reason: DiagnosticReason?,
    val metadata: Map<String, String>,
)
