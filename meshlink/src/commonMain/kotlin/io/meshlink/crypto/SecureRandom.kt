package io.meshlink.crypto

/** Platform-specific cryptographically secure random byte generation. */
expect fun secureRandomBytes(size: Int): ByteArray
