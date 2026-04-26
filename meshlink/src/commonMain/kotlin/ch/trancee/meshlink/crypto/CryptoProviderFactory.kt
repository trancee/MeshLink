package ch.trancee.meshlink.crypto

/** Returns the platform-specific [CryptoProvider] implementation. */
internal expect fun createCryptoProvider(): CryptoProvider
