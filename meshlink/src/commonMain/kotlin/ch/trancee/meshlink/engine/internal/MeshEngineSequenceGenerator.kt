package ch.trancee.meshlink.engine.internal

import ch.trancee.meshlink.identity.LocalIdentity
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement

@OptIn(ExperimentalAtomicApi::class)
internal class MeshEngineSequenceGenerator(
    private val localIdentity: LocalIdentity,
    initialSequenceNumber: Long = 1L,
) {
    // An AtomicLong increment has no suspension point, unlike the previous Mutex.withLock, so
    // concurrent outbound message/transfer/handshake-id creation no longer serializes on a
    // coroutine-level lock just to bump a counter -- fetchAndIncrement is still fully correct
    // under concurrent access from multiple coroutines (e.g. Dispatchers.Default), the same
    // concurrency guarantee the Mutex previously provided.
    private val nextSequenceNumber = AtomicLong(initialSequenceNumber)

    suspend fun createMessageId(): String {
        val current = nextSequence()
        return "${localIdentity.peerId.diagnosticSuffix()}-$current"
    }

    suspend fun createTransferId(): String {
        val current = nextSequence()
        return "transfer-${localIdentity.peerId.diagnosticSuffix()}-$current"
    }

    suspend fun createHandshakeId(): String {
        val current = nextSequence()
        return "e2e-handshake-${localIdentity.peerId.diagnosticSuffix()}-$current"
    }

    private fun nextSequence(): Long {
        return nextSequenceNumber.fetchAndIncrement()
    }
}
