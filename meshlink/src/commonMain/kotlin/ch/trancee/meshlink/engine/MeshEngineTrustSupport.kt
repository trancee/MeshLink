package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.platform.currentEpochMillis
import ch.trancee.meshlink.trust.TofuTrustStore
import ch.trancee.meshlink.trust.TrustPublicKeys
import ch.trancee.meshlink.trust.TrustRecord

internal class MeshEngineTrustSupport(
    private val localIdentity: LocalIdentity,
    private val trustStore: TofuTrustStore,
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
    suspend fun verifyAndPersistTrust(
        peerId: PeerId,
        remoteEd25519PublicKey: ByteArray,
        remoteX25519PublicKey: ByteArray,
        expectedFingerprintBytes: ByteArray? = null,
    ): TrustRecord? {
        val remoteIdentityHash =
            localIdentity.cryptoProvider.sha256(remoteEd25519PublicKey + remoteX25519PublicKey)
        val fingerprintMatches =
            fingerprintMatchesExpected(
                peerId = peerId,
                expectedFingerprintBytes = expectedFingerprintBytes,
                remoteIdentityHash = remoteIdentityHash,
            )
        val existingTrust = if (fingerprintMatches) trustStore.read(peerId.value) else null

        return when {
            !fingerprintMatches -> null
            existingTrust == null ->
                pinVerifiedTrust(
                    peerId = peerId,
                    remoteIdentityHash = remoteIdentityHash,
                    remoteEd25519PublicKey = remoteEd25519PublicKey,
                    remoteX25519PublicKey = remoteX25519PublicKey,
                )
            !trustMatchesRemoteIdentity(
                existingTrust = existingTrust,
                remoteIdentityHash = remoteIdentityHash,
                remoteEd25519PublicKey = remoteEd25519PublicKey,
                remoteX25519PublicKey = remoteX25519PublicKey,
            ) -> {
                emitDiagnostic(
                    DiagnosticCode.TRUST_FAILURE,
                    DiagnosticSeverity.ERROR,
                    "trust.verify",
                    peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
                    DiagnosticReason.TRUST_FAILURE,
                    emptyMap(),
                )
                null
            }
            else -> refreshVerifiedTrust(existingTrust)
        }
    }

    /**
     * Verifies self-asserted key material (e.g. from an inner message envelope) against a trust
     * record that was already pinned through a cryptographically authenticated channel (an
     * end-to-end or hop-level Noise handshake). Unlike [verifyAndPersistTrust], this never pins
     * trust on first contact: if [peerId] has no existing trust record, the claim is rejected
     * rather than trusted, since self-asserted data alone proves nothing about who actually sent
     * it.
     */
    suspend fun verifyEstablishedTrust(
        peerId: PeerId,
        remoteEd25519PublicKey: ByteArray,
        remoteX25519PublicKey: ByteArray,
        expectedFingerprintBytes: ByteArray? = null,
    ): TrustRecord? {
        val existingTrust = trustStore.read(peerId.value)
        if (existingTrust == null) {
            emitDiagnostic(
                DiagnosticCode.TRUST_FAILURE,
                DiagnosticSeverity.WARN,
                "trust.verify.untrusted",
                peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
                DiagnosticReason.TRUST_FAILURE,
                emptyMap(),
            )
            return null
        }
        val remoteIdentityHash =
            localIdentity.cryptoProvider.sha256(remoteEd25519PublicKey + remoteX25519PublicKey)
        val fingerprintMatches =
            fingerprintMatchesExpected(
                peerId = peerId,
                expectedFingerprintBytes = expectedFingerprintBytes,
                remoteIdentityHash = remoteIdentityHash,
            )
        if (
            !fingerprintMatches ||
                !trustMatchesRemoteIdentity(
                    existingTrust = existingTrust,
                    remoteIdentityHash = remoteIdentityHash,
                    remoteEd25519PublicKey = remoteEd25519PublicKey,
                    remoteX25519PublicKey = remoteX25519PublicKey,
                )
        ) {
            emitDiagnostic(
                DiagnosticCode.TRUST_FAILURE,
                DiagnosticSeverity.ERROR,
                "trust.verify",
                peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
                DiagnosticReason.TRUST_FAILURE,
                emptyMap(),
            )
            return null
        }
        return refreshVerifiedTrust(existingTrust)
    }

    private fun fingerprintMatchesExpected(
        peerId: PeerId,
        expectedFingerprintBytes: ByteArray?,
        remoteIdentityHash: ByteArray,
    ): Boolean {
        val fingerprintMatches =
            expectedFingerprintBytes == null ||
                expectedFingerprintBytes.contentEquals(remoteIdentityHash)
        if (!fingerprintMatches) {
            emitDiagnostic(
                DiagnosticCode.TRUST_FAILURE,
                DiagnosticSeverity.ERROR,
                "trust.verify.fingerprint",
                peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
                DiagnosticReason.TRUST_FAILURE,
                emptyMap(),
            )
        }
        return fingerprintMatches
    }

    private suspend fun pinVerifiedTrust(
        peerId: PeerId,
        remoteIdentityHash: ByteArray,
        remoteEd25519PublicKey: ByteArray,
        remoteX25519PublicKey: ByteArray,
    ): TrustRecord {
        val verifiedAtEpochMillis = currentEpochMillis()
        val trustRecord =
            TrustRecord(
                peerIdValue = peerId.value,
                identityFingerprintBytes = remoteIdentityHash,
                firstSeenAtEpochMillis = verifiedAtEpochMillis,
                lastVerifiedAtEpochMillis = verifiedAtEpochMillis,
                publicKeys =
                    TrustPublicKeys(
                        ed25519PublicKey = remoteEd25519PublicKey,
                        x25519PublicKey = remoteX25519PublicKey,
                    ),
            )
        trustStore.write(trustRecord)
        emitDiagnostic(
            DiagnosticCode.TRUST_ESTABLISHED,
            DiagnosticSeverity.INFO,
            "trust.pin",
            peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
            DiagnosticReason.STATE_CHANGE,
            emptyMap(),
        )
        return trustRecord
    }

    private fun trustMatchesRemoteIdentity(
        existingTrust: TrustRecord,
        remoteIdentityHash: ByteArray,
        remoteEd25519PublicKey: ByteArray,
        remoteX25519PublicKey: ByteArray,
    ): Boolean {
        return existingTrust.identityFingerprintBytes.contentEquals(remoteIdentityHash) &&
            existingTrust.ed25519PublicKey.contentEquals(remoteEd25519PublicKey) &&
            existingTrust.x25519PublicKey.contentEquals(remoteX25519PublicKey)
    }

    private suspend fun refreshVerifiedTrust(existingTrust: TrustRecord): TrustRecord {
        val refreshedTrust = existingTrust.withLastVerifiedAt(currentEpochMillis())
        if (refreshedTrust.lastVerifiedAtEpochMillis != existingTrust.lastVerifiedAtEpochMillis) {
            trustStore.write(refreshedTrust)
        }
        return refreshedTrust
    }
}
