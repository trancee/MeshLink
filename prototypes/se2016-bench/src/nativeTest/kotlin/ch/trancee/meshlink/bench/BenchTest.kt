package ch.trancee.meshlink.bench

import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.SendResult
import ch.trancee.meshlink.bench.crypto.CryptoBench
import ch.trancee.meshlink.bench.crypto.VectorCheck
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BenchTest {
    @Test
    fun vectorsMatch() {
        val result = VectorCheck.run()
        result.lines.forEach(::println)
        assertTrue(result.ok)
    }

    @Test
    fun handshakesComplete() {
        CryptoBench(warmup = 1, iterations = 3).run().forEach(::println)
    }

    @Test
    fun echoRoundTripAndCancellation() = runBlocking {
        val mesh = MeshLink()
        mesh.start()
        val payload = ByteArray(1024) { it.toByte() }
        assertTrue(mesh.send(mesh.echoPeer, payload) is SendResult.Accepted)
        assertTrue(mesh.messages.first().payload.contentEquals(payload))
        assertEquals(0, mesh.probes.activeCollectorCount)

        val waiter = launch { mesh.awaitForever() }
        while (mesh.probes.activeSuspendCallCount == 0) yield()
        waiter.cancel()
        withTimeout(1_000) { waiter.join() }
        assertEquals(1, mesh.probes.suspendCancellationCount)
        assertEquals(0, mesh.probes.activeSuspendCallCount)
        mesh.close()
    }
}
