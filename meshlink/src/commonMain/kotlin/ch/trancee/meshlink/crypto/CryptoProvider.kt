package ch.trancee.meshlink.crypto

internal enum class CryptoPurpose {
    HOP,
    END_TO_END,
}

internal interface CryptoProvider {
    fun randomBytes(size: Int): ByteArray

    fun hash(input: ByteArray): ByteArray

    fun seal(payload: ByteArray, purpose: CryptoPurpose): ByteArray

    fun open(payload: ByteArray, purpose: CryptoPurpose): ByteArray
}
