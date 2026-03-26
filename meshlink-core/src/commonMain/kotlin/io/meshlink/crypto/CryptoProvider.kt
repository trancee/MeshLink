package io.meshlink.crypto

data class CryptoKeyPair(val publicKey: ByteArray, val privateKey: ByteArray)

interface CryptoProvider {
    fun generateEd25519KeyPair(): CryptoKeyPair
    fun sign(privateKey: ByteArray, data: ByteArray): ByteArray
    fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean
    fun generateX25519KeyPair(): CryptoKeyPair
    fun x25519SharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray
    fun aesgcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray = byteArrayOf()): ByteArray
    fun aesgcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray = byteArrayOf()): ByteArray
    fun sha256(data: ByteArray): ByteArray
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray
}

expect fun createCryptoProvider(): CryptoProvider
