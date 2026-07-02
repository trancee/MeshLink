package ch.trancee.meshlink.trust

import ch.trancee.meshlink.test.InMemorySecureStorage
import ch.trancee.meshlink.wire.WriteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class TofuTrustStoreTest {
    @Test
    fun `write and read preserve trust timestamps`() =
        runBlocking<Unit> {
            // Arrange
            val storage = InMemorySecureStorage()
            val store = TofuTrustStore(storage)
            val record =
                TrustRecord(
                    peerIdValue = "peer-001",
                    identityFingerprintBytes = byteArrayOf(0x10, 0x20, 0x30, 0x40),
                    firstSeenAtEpochMillis = 1_700_000_000_000L,
                    lastVerifiedAtEpochMillis = 1_700_000_123_456L,
                    publicKeys =
                        TrustPublicKeys(
                            ed25519PublicKey = byteArrayOf(0x01, 0x02, 0x03),
                            x25519PublicKey = byteArrayOf(0x04, 0x05, 0x06),
                        ),
                )

            // Act
            store.write(record)
            val restored = store.read("peer-001")

            // Assert
            assertNotNull(restored)
            assertEquals(record.peerIdValue, restored.peerIdValue)
            assertEquals(record.identityFingerprint, restored.identityFingerprint)
            assertContentEquals(record.identityFingerprintBytes, restored.identityFingerprintBytes)
            assertEquals(record.firstSeenAtEpochMillis, restored.firstSeenAtEpochMillis)
            assertEquals(record.lastVerifiedAtEpochMillis, restored.lastVerifiedAtEpochMillis)
            val snapshot = storage.snapshot()
            assertEquals(1, snapshot.size)
            assertTrue(snapshot.keys.single().startsWith("trust:"))
            assertTrue(snapshot.keys.single().contains(record.peerIdValue).not())
        }

    @Test
    fun `delete removes persisted trust record`() =
        runBlocking<Unit> {
            // Arrange
            val storage = InMemorySecureStorage()
            val store = TofuTrustStore(storage)
            val record =
                TrustRecord(
                    peerIdValue = "peer-002",
                    identityFingerprintBytes = byteArrayOf(0x11, 0x22, 0x33, 0x44),
                    firstSeenAtEpochMillis = 10L,
                    lastVerifiedAtEpochMillis = 20L,
                    publicKeys =
                        TrustPublicKeys(
                            ed25519PublicKey = byteArrayOf(0x11, 0x12),
                            x25519PublicKey = byteArrayOf(0x21, 0x22),
                        ),
                )
            store.write(record)

            // Act
            store.delete("peer-002")
            val restored = store.read("peer-002")

            // Assert
            assertNull(restored)
            assertTrue(storage.snapshot().isEmpty())
        }

    @Test
    fun `delete allows the same peer id to be persisted as fresh trust state`() =
        runBlocking<Unit> {
            // Arrange
            val storage = InMemorySecureStorage()
            val store = TofuTrustStore(storage)
            val initialRecord =
                TrustRecord(
                    peerIdValue = "peer-003",
                    identityFingerprintBytes = byteArrayOf(0x01, 0x23, 0x45, 0x67),
                    firstSeenAtEpochMillis = 100L,
                    lastVerifiedAtEpochMillis = 200L,
                    publicKeys =
                        TrustPublicKeys(
                            ed25519PublicKey = byteArrayOf(0x31, 0x32),
                            x25519PublicKey = byteArrayOf(0x41, 0x42),
                        ),
                )
            val replacementRecord =
                TrustRecord(
                    peerIdValue = "peer-003",
                    identityFingerprintBytes =
                        byteArrayOf(0x89.toByte(), 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte()),
                    firstSeenAtEpochMillis = 300L,
                    lastVerifiedAtEpochMillis = 400L,
                    publicKeys =
                        TrustPublicKeys(
                            ed25519PublicKey = byteArrayOf(0x51, 0x52),
                            x25519PublicKey = byteArrayOf(0x61, 0x62),
                        ),
                )
            store.write(initialRecord)
            store.delete(initialRecord.peerIdValue)

            // Act
            store.write(replacementRecord)
            val restored = store.read(replacementRecord.peerIdValue)

            // Assert
            assertNotNull(restored)
            assertEquals(replacementRecord.identityFingerprint, restored.identityFingerprint)
            assertContentEquals(
                replacementRecord.identityFingerprintBytes,
                restored.identityFingerprintBytes,
            )
            assertEquals(replacementRecord.firstSeenAtEpochMillis, restored.firstSeenAtEpochMillis)
            assertEquals(
                replacementRecord.lastVerifiedAtEpochMillis,
                restored.lastVerifiedAtEpochMillis,
            )
            assertEquals(1, storage.snapshot().size)
        }

    @Test
    fun `read rejects a stored record whose embedded peer id does not match the lookup key`() =
        runBlocking<Unit> {
            // Arrange
            val storage = InMemorySecureStorage()
            val store = TofuTrustStore(storage)
            val storedPeerIdBytes = "peer-stored".encodeToByteArray()
            val buffer = WriteBuffer()
            buffer.writeIntLittleEndian(2)
            buffer.writeBytes(byteArrayOf(0x0a, 0x0b))
            buffer.writeIntLittleEndian(1)
            buffer.writeBytes(byteArrayOf(0x0c))
            buffer.writeIntLittleEndian(1)
            buffer.writeBytes(byteArrayOf(0x0d))
            buffer.writeLongLittleEndian(1L)
            buffer.writeLongLittleEndian(2L)
            buffer.writeIntLittleEndian(storedPeerIdBytes.size)
            buffer.writeBytes(storedPeerIdBytes)
            storage.write(store.keyForTest("peer-requested"), buffer.toByteArray())

            // Act
            val restored = store.read("peer-requested")

            // Assert
            assertNull(restored)
        }

    @Test
    fun `read trusts legacy records written before peer id verification existed`() =
        runBlocking<Unit> {
            // Arrange
            val storage = InMemorySecureStorage()
            val store = TofuTrustStore(storage)
            val fingerprintBytes = byteArrayOf(0x0a, 0x0b)
            val buffer = WriteBuffer()
            buffer.writeIntLittleEndian(fingerprintBytes.size)
            buffer.writeBytes(fingerprintBytes)
            buffer.writeIntLittleEndian(1)
            buffer.writeBytes(byteArrayOf(0x0c))
            buffer.writeIntLittleEndian(1)
            buffer.writeBytes(byteArrayOf(0x0d))
            buffer.writeLongLittleEndian(30L)
            buffer.writeLongLittleEndian(40L)
            storage.write(store.keyForTest("peer-legacy"), buffer.toByteArray())

            // Act
            val restored = store.read("peer-legacy")

            // Assert
            assertNotNull(restored)
            assertEquals("peer-legacy", restored.peerIdValue)
            assertContentEquals(fingerprintBytes, restored.identityFingerprintBytes)
        }
}
