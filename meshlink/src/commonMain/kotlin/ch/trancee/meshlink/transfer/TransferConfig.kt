package ch.trancee.meshlink.transfer

internal data class TransferConfig(
    val chunkSize: Int = 244,
    val acksBeforeDouble: Int = 4,
    val acksBeforeQuad: Int = 8,
    val inactivityBaseTimeoutMillis: Long = 30_000L,
    val maxResumeAttempts: Int = 3,
    val maxNackRetries: Int = 5,
    val nackBaseBackoffMillis: Long = 500L,
    /** Maximum jitter added to NACK backoff delays (ms). Set >0 in production. */
    val backoffJitterMaxMillis: Long = 0L,
)
