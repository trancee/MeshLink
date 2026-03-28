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
