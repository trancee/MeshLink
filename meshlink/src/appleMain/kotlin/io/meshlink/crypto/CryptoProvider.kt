package io.meshlink.crypto

// TODO: Add CryptoKit-backed provider via Kotlin/Native cinterop.
// Requires adding a .def file for CryptoKit and configuring cinterop
// in build.gradle.kts. CryptoKit provides hardware-accelerated
// Curve25519, ChaChaPoly, SHA256, and HKDF on all Apple platforms.
// Until then, PureKotlinCryptoProvider is used (functionally correct,
// but not hardware-accelerated).
actual fun CryptoProvider(): CryptoProvider = PureKotlinCryptoProvider()
