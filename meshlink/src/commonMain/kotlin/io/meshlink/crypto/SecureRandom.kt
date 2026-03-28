package io.meshlink.crypto

/**
 * Platform-specific cryptographically secure random byte generation.
 *
 * **Why not `kotlin.random.Random`?**
 * As of Kotlin 1.9+, `Random.Default` happens to delegate to platform-secure
 * sources (SecureRandom on JVM, arc4random on Native), but the Kotlin docs
 * do not guarantee cryptographic security — it is an implementation detail.
 * This expect/actual uses the canonical CSPRNG API on each platform, making
 * the security contract explicit and immune to future Kotlin stdlib changes.
 *
 * Actuals:
 * - JVM/Android: `java.security.SecureRandom`
 * - Apple (iOS/macOS): `Security.SecRandomCopyBytes`
 * - Linux: `/dev/urandom`
 */
expect fun secureRandomBytes(size: Int): ByteArray
