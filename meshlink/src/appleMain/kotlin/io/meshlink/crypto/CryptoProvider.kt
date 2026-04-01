package io.meshlink.crypto

actual fun CryptoProvider(): CryptoProvider = PureKotlinCryptoProvider()
