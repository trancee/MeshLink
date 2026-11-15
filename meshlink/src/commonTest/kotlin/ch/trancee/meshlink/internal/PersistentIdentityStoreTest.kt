package ch.trancee.meshlink.internal

import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersistentIdentityStoreTest {
    @Test
    fun firstLoadCreatesAKeyPairAndStoresItsSeed() {
        // Arrange
        val slot = FakeSeedSlot(SlotRead.Absent)

        // Act
        val keyPair = PersistentIdentityStore(slot).loadOrCreate(SodiumCryptoProvider)

        // Assert
        assertContentEquals(keyPair!!.seed, slot.written.single())
    }

    @Test
    fun storedSeedLoadsItsKeyPairAndTheReadBufferIsWiped() {
        // Arrange
        val stored = SodiumCryptoProvider.ed25519KeyPair()
        val read = stored.seed.copyOf()
        val slot = FakeSeedSlot(SlotRead.Present(read))

        // Act
        val keyPair = PersistentIdentityStore(slot).loadOrCreate(NoKeyPairs)

        // Assert
        assertContentEquals(stored.publicKey, keyPair!!.publicKey)
        assertContentEquals(ByteArray(SEED_SIZE), read, "S1: the plaintext seed buffer is wiped")
        assertTrue(slot.written.isEmpty())
    }

    @Test
    fun aTruncatedSeedIsUnavailableAndNeverReplaced() {
        // Arrange: T10 truncated item.
        val read = ByteArray(SEED_SIZE - 1) { 1 }
        val slot = FakeSeedSlot(SlotRead.Present(read))

        // Act
        val keyPair = PersistentIdentityStore(slot).loadOrCreate(NoKeyPairs)

        // Assert
        assertNull(keyPair)
        assertContentEquals(ByteArray(SEED_SIZE - 1), read, "S1: the plaintext seed buffer is wiped")
        assertTrue(slot.written.isEmpty())
    }

    @Test
    fun anUnreadableSlotIsUnavailableAndNeverReplaced() {
        // Arrange
        val slot = FakeSeedSlot(SlotRead.Unavailable)

        // Act
        val keyPair = PersistentIdentityStore(slot).loadOrCreate(NoKeyPairs)

        // Assert
        assertNull(keyPair)
        assertTrue(slot.written.isEmpty())
    }

    @Test
    fun aKeyPairThatCanNotBeStoredIsNotUsed() {
        // Arrange
        val slot = FakeSeedSlot(SlotRead.Absent, writes = false)

        // Act
        val keyPair = PersistentIdentityStore(slot).loadOrCreate(SodiumCryptoProvider)

        // Assert
        assertNull(keyPair)
        assertEquals(1, slot.written.size)
    }

    @Test
    fun deleteErasesTheSlot() {
        // Arrange
        val slot = FakeSeedSlot(SlotRead.Unavailable)

        // Act
        val deleted = PersistentIdentityStore(slot).delete()

        // Assert
        assertTrue(deleted)
        assertEquals(1, slot.erased)
    }

    @Test
    fun aSlotThatCanNotBeErasedReportsIt() {
        // Arrange
        val slot = FakeSeedSlot(SlotRead.Unavailable, erases = false)

        // Act
        val deleted = PersistentIdentityStore(slot).delete()

        // Assert
        assertFalse(deleted)
    }

    private companion object {
        const val SEED_SIZE = 32
    }
}

/** A [SeedSlot] that reads [read] and records every write and erase. */
internal class FakeSeedSlot(
    private val read: SlotRead,
    private val writes: Boolean = true,
    private val erases: Boolean = true,
) : SeedSlot {
    val written = mutableListOf<ByteArray>()
    var erased = 0

    override fun read(): SlotRead = read

    override fun write(seed: ByteArray): Boolean {
        written += seed.copyOf()
        return writes
    }

    override fun erase(): Boolean {
        erased++
        return erases
    }
}
