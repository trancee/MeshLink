package ch.trancee.meshlink.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KeychainSlotTest {
    @Test
    fun aMissingItemIsAbsent() {
        // Arrange
        val keychain = FakeKeychain(KeychainCopy(ERR_SEC_ITEM_NOT_FOUND, null))

        // Act
        val read = KeychainSlot(keychain).read()

        // Assert
        assertEquals(SlotRead.Absent, read)
    }

    @Test
    fun aFoundItemIsPresentWithItsData() {
        // Arrange
        val data = ByteArray(32) { it.toByte() }
        val keychain = FakeKeychain(KeychainCopy(ERR_SEC_SUCCESS, data.copyOf()))

        // Act
        val read = KeychainSlot(keychain).read()

        // Assert
        assertContentEquals(data, assertIs<SlotRead.Present>(read).seed)
    }

    @Test
    fun aFoundItemWithoutDataIsUnavailable() {
        // Arrange: T10 unparsable Keychain item.
        val keychain = FakeKeychain(KeychainCopy(ERR_SEC_SUCCESS, null))

        // Act
        val read = KeychainSlot(keychain).read()

        // Assert
        assertEquals(SlotRead.Unavailable, read)
    }

    @Test
    fun aKeychainThatFailsIsUnavailable() {
        // Arrange: errSecInteractionNotAllowed, as before the first unlock after a restart.
        val keychain = FakeKeychain(KeychainCopy(ERR_SEC_INTERACTION_NOT_ALLOWED, null))

        // Act
        val read = KeychainSlot(keychain).read()

        // Assert
        assertEquals(SlotRead.Unavailable, read)
    }

    @Test
    fun writeAddsTheItemAndReportsSuccess() {
        // Arrange
        val keychain = FakeKeychain(KeychainCopy(ERR_SEC_ITEM_NOT_FOUND, null))
        val seed = ByteArray(32) { 7 }

        // Act
        val written = KeychainSlot(keychain).write(seed)

        // Assert
        assertTrue(written)
        assertContentEquals(seed, keychain.added.single())
    }

    @Test
    fun writeReportsAFailedAdd() {
        // Arrange: errSecDuplicateItem; the slot never overwrites an item.
        val keychain = FakeKeychain(KeychainCopy(ERR_SEC_ITEM_NOT_FOUND, null), addStatus = ERR_SEC_DUPLICATE_ITEM)

        // Act
        val written = KeychainSlot(keychain).write(ByteArray(32))

        // Assert
        assertFalse(written)
    }

    @Test
    fun eraseSucceedsWhenTheItemIsDeletedOrMissing() {
        // Arrange
        val deleted = FakeKeychain(KeychainCopy(ERR_SEC_ITEM_NOT_FOUND, null), deleteStatus = ERR_SEC_SUCCESS)
        val missing = FakeKeychain(KeychainCopy(ERR_SEC_ITEM_NOT_FOUND, null), deleteStatus = ERR_SEC_ITEM_NOT_FOUND)

        // Act
        val erased = listOf(KeychainSlot(deleted).erase(), KeychainSlot(missing).erase())

        // Assert
        assertEquals(listOf(true, true), erased)
    }

    @Test
    fun eraseReportsAFailedDelete() {
        // Arrange
        val keychain =
            FakeKeychain(KeychainCopy(ERR_SEC_ITEM_NOT_FOUND, null), deleteStatus = ERR_SEC_INTERACTION_NOT_ALLOWED)

        // Act
        val erased = KeychainSlot(keychain).erase()

        // Assert
        assertFalse(erased)
    }

    private companion object {
        const val ERR_SEC_DUPLICATE_ITEM = -25299
        const val ERR_SEC_INTERACTION_NOT_ALLOWED = -25308
    }
}

/** A Keychain whose calls return fixed statuses; it records each added seed. */
internal class FakeKeychain(
    private val copy: KeychainCopy = KeychainCopy(ERR_SEC_ITEM_NOT_FOUND, null),
    private val addStatus: Int = ERR_SEC_SUCCESS,
    private val deleteStatus: Int = ERR_SEC_SUCCESS,
) : Keychain {
    val added = mutableListOf<ByteArray>()

    override fun copy(): KeychainCopy = copy

    override fun add(seed: ByteArray): Int {
        added += seed.copyOf()
        return addStatus
    }

    override fun delete(): Int = deleteStatus
}

private const val ERR_SEC_SUCCESS = 0
private const val ERR_SEC_ITEM_NOT_FOUND = -25300
