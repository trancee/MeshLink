package ch.trancee.meshlink.internal

import kotlin.random.Random

/**
 * The only source of non-cryptographic randomness in the protocol core: jitter, latency and peer
 * choice (ADR 0017 decision 1). Key material and nonces come from [CryptoProvider] instead.
 *
 * This is the one `commonMain` file that may import `kotlin.random` (`config/detekt/detekt.yml`).
 */
public interface RandomSource {
    /** A uniformly distributed integer in [from] until [until], exclusive; [from] < [until]. */
    public fun nextInt(
        from: Int,
        until: Int,
    ): Int
}

/** A [RandomSource] that yields the same sequence for the same [seed] and Kotlin version. */
public class SeededRandom(
    seed: Long,
) : RandomSource {
    private val random = Random(seed)

    override fun nextInt(
        from: Int,
        until: Int,
    ): Int = random.nextInt(from, until)
}
