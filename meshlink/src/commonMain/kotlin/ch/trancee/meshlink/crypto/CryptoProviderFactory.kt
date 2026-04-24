package ch.trancee.meshlink.crypto

/** Returns the platform-specific [CryptoProvider] implementation. */
expect fun createCryptoProvider(): CryptoProvider
