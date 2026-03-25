package io.meshlink.util

import java.security.MessageDigest

class AppIdFilter(appId: String?) {

    private val expectedHash: ByteArray? = appId?.let { hash(it) }

    fun accepts(hash: ByteArray?): Boolean {
        if (expectedHash == null) return true // no filter → accept all
        if (hash == null) return false
        return expectedHash.contentEquals(hash)
    }

    companion object {
        /** Compute a 16-byte hash of the appId (placeholder for BLAKE2b-128). */
        fun hash(appId: String): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(appId.encodeToByteArray()).copyOf(16)
        }
    }
}
