package io.meshlink.util

import io.meshlink.crypto.CryptoProvider

class AppIdFilter(appId: String?) {

    private val expectedHash: ByteArray? = appId?.let { hash(it) }

    fun accepts(hash: ByteArray?): Boolean {
        if (expectedHash == null) return true // no filter → accept all
        if (hash == null) return false
        return expectedHash.contentEquals(hash)
    }

    companion object {
        /** Compute a 16-byte hash of the appId (truncated SHA-256). */
        fun hash(appId: String): ByteArray {
            val crypto = CryptoProvider()
            return crypto.sha256(appId.encodeToByteArray()).copyOf(16)
        }
    }
}
