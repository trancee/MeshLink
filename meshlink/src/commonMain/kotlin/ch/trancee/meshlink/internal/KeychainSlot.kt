package ch.trancee.meshlink.internal

/**
 * The installation identity's generic-password item in the iOS Keychain (ADR 0003): readable after
 * the first unlock, on this device only, never synchronized. Each call returns the `OSStatus` of
 * its `SecItem` call.
 */
internal interface Keychain {
    /** `SecItemCopyMatching` for the item's data. */
    fun copy(): KeychainCopy

    /** `SecItemAdd` of the item holding [seed]; the call wipes its own copy of [seed]. */
    fun add(seed: ByteArray): Int

    /** `SecItemDelete` of the item. */
    fun delete(): Int
}

/** The result of [Keychain.copy]: the `OSStatus`, and the item's data on success. */
internal class KeychainCopy(
    val status: Int,
    val data: ByteArray?,
)

/** A [SeedSlot] in the Keychain item of [keychain]. */
internal class KeychainSlot(
    private val keychain: Keychain,
) : SeedSlot {
    override fun read(): SlotRead {
        val copy = keychain.copy()
        return when (copy.status) {
            ERR_SEC_SUCCESS -> copy.data?.let(SlotRead::Present) ?: SlotRead.Unavailable
            ERR_SEC_ITEM_NOT_FOUND -> SlotRead.Absent
            else -> SlotRead.Unavailable
        }
    }

    override fun write(seed: ByteArray): Boolean = keychain.add(seed) == ERR_SEC_SUCCESS

    override fun erase(): Boolean = keychain.delete().let { it == ERR_SEC_SUCCESS || it == ERR_SEC_ITEM_NOT_FOUND }

    private companion object {
        const val ERR_SEC_SUCCESS = 0
        const val ERR_SEC_ITEM_NOT_FOUND = -25300
    }
}
