package ch.trancee.meshlink.trust

import ch.trancee.meshlink.identity.toHexString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TrustRecordTest {
    @Test
    fun `trust record derives fingerprint text from bytes and copies public keys`() {
        // Arrange
        val fingerprintBytes = byteArrayOf(0x01, 0x23, 0x45, 0x67)
        val ed25519 = byteArrayOf(1, 2, 3, 4)
        val x25519 = byteArrayOf(5, 6, 7, 8)
        val record =
            TrustRecord(
                peerIdValue = "peer-abcdef",
                identityFingerprintBytes = fingerprintBytes,
                firstSeenAtEpochMillis = 100L,
                lastVerifiedAtEpochMillis = 200L,
                publicKeys = TrustPublicKeys(ed25519PublicKey = ed25519, x25519PublicKey = x25519),
            )
        fingerprintBytes[0] = 99
        ed25519[0] = 98
        x25519[0] = 97

        // Act
        val fingerprint = record.identityFingerprint

        // Assert
        assertEquals(byteArrayOf(0x01, 0x23, 0x45, 0x67).toHexString(), fingerprint)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), record.ed25519PublicKey)
        assertContentEquals(byteArrayOf(5, 6, 7, 8), record.x25519PublicKey)
    }

    @Test
    fun `trust record decodes fingerprint bytes from a hex string`() {
        // Arrange
        val record =
            TrustRecord(
                peerIdValue = "peer-abcdef",
                identityFingerprint = "CAFE1000",
                firstSeenAtEpochMillis = 100L,
                lastVerifiedAtEpochMillis = 200L,
                publicKeys =
                    TrustPublicKeys(
                        ed25519PublicKey = byteArrayOf(1, 2, 3, 4),
                        x25519PublicKey = byteArrayOf(5, 6, 7, 8),
                    ),
            )

        // Act / Assert
        assertContentEquals(
            byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0x10, 0x00),
            record.identityFingerprintBytes,
        )
        assertEquals("CAFE1000", record.identityFingerprint)
    }

    @Test
    fun `withLastVerifiedAt returns a copied record with an updated timestamp`() {
        // Arrange
        val record =
            TrustRecord(
                peerIdValue = "peer-abcdef",
                identityFingerprintBytes = byteArrayOf(1, 2, 3, 4),
                firstSeenAtEpochMillis = 100L,
                lastVerifiedAtEpochMillis = 200L,
                publicKeys =
                    TrustPublicKeys(
                        ed25519PublicKey = byteArrayOf(1, 2, 3, 4),
                        x25519PublicKey = byteArrayOf(5, 6, 7, 8),
                    ),
            )

        // Act
        val updated = record.withLastVerifiedAt(300L)

        // Assert
        assertEquals(200L, record.lastVerifiedAtEpochMillis)
        assertEquals(300L, updated.lastVerifiedAtEpochMillis)
        assertEquals(record.firstSeenAtEpochMillis, updated.firstSeenAtEpochMillis)
        assertEquals(record.peerIdValue, updated.peerIdValue)
        assertContentEquals(record.identityFingerprintBytes, updated.identityFingerprintBytes)
        assertContentEquals(record.ed25519PublicKey, updated.ed25519PublicKey)
        assertContentEquals(record.x25519PublicKey, updated.x25519PublicKey)
    }

    @Test
    fun `trust record requires a fingerprint source`() {
        // Arrange / Act
        val failure =
            assertFailsWith<IllegalStateException> {
                TrustRecord(
                    peerIdValue = "peer-abcdef",
                    firstSeenAtEpochMillis = 100L,
                    lastVerifiedAtEpochMillis = 200L,
                    publicKeys =
                        TrustPublicKeys(
                            ed25519PublicKey = byteArrayOf(1, 2, 3, 4),
                            x25519PublicKey = byteArrayOf(5, 6, 7, 8),
                        ),
                )
            }

        // Assert
        assertEquals("identity fingerprint is required", failure.message)
    }
}
