package io.meshlink.crypto

actual fun createCryptoProvider(): CryptoProvider =
    error("iOS crypto not yet implemented — use CryptoKit in a future phase")
