package io.meshlink.crypto

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TrustStoreTest {

    @Test
    fun firstVerifyReturnsFirstSeenAndPinsKey() {
        val store = TrustStore()
        val key = byteArrayOf(1, 2, 3, 4)

        val result = store.verify("peer-a", key)

        assertIs<VerifyResult.FirstSeen>(result)
    }

    @Test
    fun secondVerifySameKeyReturnsTrusted() {
        val store = TrustStore()
        val key = byteArrayOf(1, 2, 3, 4)

        store.verify("peer-a", key) // first time → pins
        val result = store.verify("peer-a", key) // same key → trusted

        assertIs<VerifyResult.Trusted>(result)
    }

    @Test
    fun differentKeyInStrictModeReturnsKeyChanged() {
        val store = TrustStore(TrustMode.STRICT)
        val originalKey = byteArrayOf(1, 2, 3, 4)
        val newKey = byteArrayOf(5, 6, 7, 8)

        store.verify("peer-a", originalKey) // pin original
        val result = store.verify("peer-a", newKey) // different key

        assertIs<VerifyResult.KeyChanged>(result)
        assertTrue(result.previousKey.contentEquals(originalKey),
            "KeyChanged should contain the previously pinned key")
        // Key should NOT be updated — still pinned to original
        assertIs<VerifyResult.Trusted>(store.verify("peer-a", originalKey))
    }

    @Test
    fun differentKeyInSoftRepinModeAutoRepins() {
        val store = TrustStore(TrustMode.SOFT_REPIN)
        val originalKey = byteArrayOf(1, 2, 3, 4)
        val newKey = byteArrayOf(5, 6, 7, 8)

        store.verify("peer-a", originalKey) // pin original
        val result = store.verify("peer-a", newKey) // different key → auto-repin

        assertIs<VerifyResult.FirstSeen>(result, "SOFT_REPIN treats new key as FirstSeen")
        // New key should now be pinned
        assertIs<VerifyResult.Trusted>(store.verify("peer-a", newKey))
    }

    @Test
    fun repinInStrictModeUpdatesPin() {
        val store = TrustStore(TrustMode.STRICT)
        val originalKey = byteArrayOf(1, 2, 3, 4)
        val newKey = byteArrayOf(5, 6, 7, 8)

        store.verify("peer-a", originalKey) // pin original
        store.verify("peer-a", newKey) // KeyChanged, but pin unchanged

        // Explicitly repin to the new key
        store.repin("peer-a", newKey)

        assertIs<VerifyResult.Trusted>(store.verify("peer-a", newKey))
        assertIs<VerifyResult.KeyChanged>(store.verify("peer-a", originalKey))
    }

    @Test
    fun snapshotRestorePreservesState() {
        val original = TrustStore(TrustMode.STRICT)
        val keyA = byteArrayOf(1, 2, 3)
        val keyB = byteArrayOf(4, 5, 6)

        original.verify("peer-a", keyA)
        original.verify("peer-b", keyB)

        val snapshot = original.snapshot()
        val restored = TrustStore.restore(snapshot)

        // Restored store remembers pinned keys
        assertIs<VerifyResult.Trusted>(restored.verify("peer-a", keyA))
        assertIs<VerifyResult.Trusted>(restored.verify("peer-b", keyB))

        // Restored store preserves mode
        assertIs<VerifyResult.KeyChanged>(restored.verify("peer-a", byteArrayOf(9, 9, 9)))
    }
}
