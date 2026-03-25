package io.meshlink.util

enum class DeliveryOutcome {
    CONFIRMED,
    FAILED_NO_ROUTE,
    FAILED_HOP_LIMIT,
    FAILED_ACK_TIMEOUT,
    FAILED_BUFFER_FULL,
    FAILED_PEER_OFFLINE,
    FAILED_DELIVERY_TIMEOUT,
}

class DeliveryTracker {

    private enum class State { PENDING, RESOLVED }

    private val states = mutableMapOf<String, State>()

    fun register(messageId: String) {
        states[messageId] = State.PENDING
    }

    /** Returns the outcome if accepted (first terminal signal), null if rejected (duplicate/unknown). */
    fun recordOutcome(messageId: String, outcome: DeliveryOutcome): DeliveryOutcome? {
        val state = states[messageId] ?: return null
        if (state != State.PENDING) return null // already resolved → tombstoned
        states[messageId] = State.RESOLVED
        return outcome
    }
}
