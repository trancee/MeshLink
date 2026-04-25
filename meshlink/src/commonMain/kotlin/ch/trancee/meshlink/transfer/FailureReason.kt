package ch.trancee.meshlink.transfer

enum class FailureReason {
    INACTIVITY_TIMEOUT,
    DEGRADATION_PROBE_FAILED,
    BUFFER_FULL_RETRY_EXHAUSTED,
    MEMORY_PRESSURE,
    RESUME_FAILED,
}
