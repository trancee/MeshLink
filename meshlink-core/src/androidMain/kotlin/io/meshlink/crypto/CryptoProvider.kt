@file:JvmName("AndroidCryptoProvider")

package io.meshlink.crypto

actual fun createCryptoProvider(): CryptoProvider = PureKotlinCryptoProvider()
