package ch.trancee.meshlink.messaging

/**
 * Composite key for the 2D relay rate limiter: (neighbor peer, originating sender).
 *
 * Data class is safe here because [MessageIdKey] already has correct structural [equals]/[hashCode]
 * semantics, so the data-class-generated implementations are correct.
 */
internal data class PeerPair(val neighbor: MessageIdKey, val sender: MessageIdKey)
