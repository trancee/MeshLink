package ch.trancee.meshlink.integration

import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.test.MeshTestHarness
import ch.trancee.meshlink.test.NodeHandle
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class MemoryBudgetIntegrationTest {
    @Test
    fun `eight-peer steady state stays within the retained heap budget`() =
        runBlocking<Unit> {
            // Arrange
            requestHeapStabilization()
            val baselineBytes = usedHeapBytesOrNull()
            val harness = MeshTestHarness()
            val nodes = List(8) { index -> harness.createNode(peerIdValue = "peer-$index") }
            linkSteadyStateTopology(harness, nodes)
            val forwardPayload = ByteArray(256) { index -> ((index * 17) and 0xFF).toByte() }
            val reversePayload = ByteArray(192) { index -> ((index * 29) and 0xFF).toByte() }

            try {
                nodes.forEach { node -> node.meshLink.start() }
                delay(500)
                val forwardReceivedDeferred =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(5_000) { nodes.last().meshLink.messages.first() }
                    }
                val reverseReceivedDeferred =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(5_000) { nodes.first().meshLink.messages.first() }
                    }

                // Act
                val forwardResult = nodes.first().meshLink.send(nodes.last().peerId, forwardPayload)
                val forwardReceived = forwardReceivedDeferred.await()
                val reverseResult = nodes.last().meshLink.send(nodes.first().peerId, reversePayload)
                val reverseReceived = reverseReceivedDeferred.await()
                delay(250)
                requestHeapStabilization()
                val steadyStateBytes = baselineBytes?.let { baseline ->
                    val usedBytes = usedHeapBytesOrNull() ?: return@let null
                    val retainedBytes = (usedBytes - baseline).coerceAtLeast(0L)
                    println(
                        "MEMORY_BUDGET baselineBytes=$baseline usedBytes=$usedBytes steadyStateBytes=$retainedBytes"
                    )
                    retainedBytes
                }

                // Assert
                assertIs<SendResult.Sent>(forwardResult)
                assertContentEquals(forwardPayload, forwardReceived.payload)
                assertIs<SendResult.Sent>(reverseResult)
                assertContentEquals(reversePayload, reverseReceived.payload)
                if (steadyStateBytes != null) {
                    assertTrue(
                        actual = steadyStateBytes <= MAX_RETAINED_HEAP_BYTES,
                        message =
                            "The eight-peer steady-state mesh should retain at most $MAX_RETAINED_HEAP_BYTES bytes, but used $steadyStateBytes bytes",
                    )
                }
            } finally {
                nodes.asReversed().forEach { node -> runCatching { node.meshLink.stop() } }
            }
        }

    private fun linkSteadyStateTopology(harness: MeshTestHarness, nodes: List<NodeHandle>): Unit {
        nodes.zipWithNext().forEach { (left, right) -> harness.linkPeers(left, right) }
        harness.linkPeers(nodes.first(), nodes.last())
        harness.linkPeers(nodes[1], nodes[5])
        harness.linkPeers(nodes[2], nodes[6])
    }

    private companion object {
        private const val MAX_RETAINED_HEAP_BYTES: Long = 8L * 1024L * 1024L
    }
}

internal expect fun usedHeapBytesOrNull(): Long?

internal expect fun requestHeapStabilization(): Unit
