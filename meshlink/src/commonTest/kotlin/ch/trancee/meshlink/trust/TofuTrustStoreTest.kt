package ch.trancee.meshlink.trust

import ch.trancee.meshlink.test.InMemorySecureStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class TofuTrustStoreTest {
    @Test
    fun `write and read preserve trust timestamps`() = runBlocking {
        // Arrange
        val storage = InMemorySecureStorage()
        val store = TofuTrustStore(storage)
        val record =
            TrustRecord(
                peerIdValue = "peer-001",
                identityFingerprint = "fingerprint-001",
                firstSeenAtEpochMillis = 1_700_000_000_000L,
                lastVerifiedAtEpochMillis = 1_700_000_123_456L,
                ed25519PublicKey = byteArrayOf(0x01, 0x02, 0x03),
                x25519PublicKey = byteArrayOf(0x04, 0x05, 0x06),
            )

        // Act
        store.write(record)
        val restored = store.read("peer-001")

        // Assert
        assertNotNull(restored)
        assertEquals(record.peerIdValue, restored.peerIdValue)
        assertEquals(record.identityFingerprint, restored.identityFingerprint)
        assertEquals(record.firstSeenAtEpochMillis, restored.firstSeenAtEpochMillis)
        assertEquals(record.lastVerifiedAtEpochMillis, restored.lastVerifiedAtEpochMillis)
        val snapshot = storage.snapshot()
        assertEquals(1, snapshot.size)
        assertTrue(snapshot.keys.single().startsWith("trust:"))
        assertTrue(snapshot.keys.single().contains(record.peerIdValue).not())
    }

    @Test
    fun `delete removes persisted trust record`() = runBlocking {
        // Arrange
        val storage = InMemorySecureStorage()
        val store = TofuTrustStore(storage)
        val record =
            TrustRecord(
                peerIdValue = "peer-002",
                identityFingerprint = "fingerprint-002",
                firstSeenAtEpochMillis = 10L,
                lastVerifiedAtEpochMillis = 20L,
                ed25519PublicKey = byteArrayOf(0x11, 0x12),
                x25519PublicKey = byteArrayOf(0x21, 0x22),
            )
        store.write(record)

        // Act
        store.delete("peer-002")
        val restored = store.read("peer-002")

        // Assert
        assertNull(restored)
        assertTrue(storage.snapshot().isEmpty())
    }
}
