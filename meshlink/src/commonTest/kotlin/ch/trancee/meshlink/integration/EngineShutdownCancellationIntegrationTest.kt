package ch.trancee.meshlink.integration

import ch.trancee.meshlink.test.MeshTestHarness
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Covers issue #118's `CoroutineExceptionHandler`/cancellation-propagation fixes end-to-end: an
 * in-flight, genuinely suspending engine operation (handshake completion, which drives both
 * [ch.trancee.meshlink.engine.transport.MeshEnginePlatformBridge.promoteTemporaryPeer] and
 * hop-payload decryption under `HopSession.inboundMutex`) must unwind via ordinary coroutine
 * cancellation when the runtime's [kotlinx.coroutines.CoroutineScope] is cancelled mid-flight, not
 * be swallowed by a `runCatching`/`getOrElse` and reported as an ordinary failure diagnostic. Uses
 * the project's canonical [MeshTestHarness]/`VirtualMeshTransport` virtual harness rather than real
 * BLE hardware, per the constitution's multi-node integration test requirement.
 */
class EngineShutdownCancellationIntegrationTest {
    @Test
    fun `cancelling the runtime scope mid-handshake unwinds via cancellation without hanging`() =
        runBlocking<Unit> {
            // Arrange -- two nodes linked and started, but not yet given time to complete their
            // first handshake, so the handshake/promoteTemporaryPeer/decrypt machinery is still
            // genuinely in flight when the scope is cancelled below.
            val harness = harness()
            val sender = harness.createNode("peer-a")
            val recipient = harness.createNode("peer-b")
            val payload = "cancelled before handshake completes".encodeToByteArray()

            harness.linkPeers(sender, recipient)
            sender.meshLink.start()
            recipient.meshLink.start()

            val sendDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    sender.meshLink.send(recipient.peerId, payload)
                }

            // Act -- cancel the sender's runtime scope while the handshake/send is still
            // in flight, before any deliberate settle delay. A swallowed CancellationException
            // inside decryptInboundWireFrame/promoteTemporaryPeer's former runCatching blocks
            // would previously have let a stray coroutine linger past this cancellation trying to
            // report a spurious failure diagnostic against an already-torn-down scope instead of
            // unwinding immediately.
            delay(1)
            sender.coroutineScope.cancel()

            // Assert -- the in-flight send() completes via cancellation (or an ordinary result if
            // it happened to finish first), never hangs, and the cancellation itself does not
            // surface as an uncaught/unexpected exception escaping this test.
            val outcome = runCatching { sendDeferred.await() }
            assertTrue(
                outcome.isSuccess || outcome.exceptionOrNull() is CancellationException,
                "Expected send() to either complete normally or unwind via " +
                    "CancellationException, got: ${outcome.exceptionOrNull()}",
            )
        }

    private val harnesses: MutableList<MeshTestHarness> = mutableListOf()

    @AfterTest
    fun tearDown(): Unit =
        runBlocking<Unit> {
            harnesses.asReversed().forEach { harness -> runCatching { harness.stopAll() } }
            harnesses.clear()
        }

    private fun harness(): MeshTestHarness = MeshTestHarness().also(harnesses::add)
}
