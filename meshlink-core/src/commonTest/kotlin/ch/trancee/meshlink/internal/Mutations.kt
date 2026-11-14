package ch.trancee.meshlink.internal

/**
 * Deterministic decoder inputs derived from [valid] (ADR 0017 decision 9): every prefix, [OVERLONG_EXTRA]
 * over-long inputs and [MUTATIONS] copies with up to [MAX_FLIPS] bytes overwritten, from a fixed seed.
 */
internal fun mutationInputs(valid: ByteArray): List<ByteArray> {
    val random = SeededRandom(MUTATION_SEED)
    return (0..valid.size).map { valid.copyOf(it) } +
        (1..OVERLONG_EXTRA).map { valid + ByteArray(it) } +
        (1..MUTATIONS).map {
            valid.copyOf().also { bytes ->
                repeat(random.nextInt(1, MAX_FLIPS + 1)) {
                    bytes[random.nextInt(0, bytes.size)] = random.nextInt(0, BYTE_VALUES).toByte()
                }
            }
        }
}

private const val MUTATION_SEED = 0x4D455348L
private const val OVERLONG_EXTRA = 64
private const val MUTATIONS = 10_000
private const val MAX_FLIPS = 4
private const val BYTE_VALUES = 256
