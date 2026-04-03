package io.meshlink.crypto

// Linux has no built-in crypto library accessible from Kotlin/Native
// without external dependencies. PureKotlinCryptoProvider is used.
// Post-v1: consider OpenSSL interop via cinterop for hardware acceleration.
actual fun CryptoProvider(): CryptoProvider = PureKotlinCryptoProvider()
