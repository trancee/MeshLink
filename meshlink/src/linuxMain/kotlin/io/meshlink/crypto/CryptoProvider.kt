package io.meshlink.crypto

actual fun createCryptoProvider(): CryptoProvider = PureKotlinCryptoProvider()
