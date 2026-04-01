package io.meshlink

import io.meshlink.config.meshLinkConfig
import io.meshlink.crypto.CryptoProvider
import io.meshlink.transport.VirtualMeshTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class CompressionIntegrationTest {

    private val peerIdAlice = ByteArray(8) { (0xA0 + it).toByte() }
    private val peerIdBob = ByteArray(8) { (0xB0 + it).toByte() }

    private fun compressionConfig(block: io.meshlink.config.MeshLinkConfigBuilder.() -> Unit = {}) = meshLinkConfig {
        gossipIntervalMillis = 0L
        keepaliveIntervalMillis = 0L
        deliveryTimeoutMillis = 0L
        compressionEnabled = true
        compressionMinBytes = 64
        block()
    }

    @Test
    fun compressedMessageRoundTrips() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val tBob = VirtualMeshTransport(peerIdBob)
        tAlice.linkTo(tBob)
        val config = compressionConfig()
        val crypto = CryptoProvider()

        val alice = MeshLink(tAlice, config, coroutineContext, clock = { testScheduler.currentTime }, crypto = crypto)
        val bob = MeshLink(tBob, config, coroutineContext, clock = { testScheduler.currentTime }, crypto = crypto)
        alice.start(); bob.start()
        advanceUntilIdle()

        tAlice.simulateDiscovery(peerIdBob)
        tBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        val payload = ByteArray(200) { (it % 10).toByte() }
        var received: ByteArray? = null
        val job = launch { received = bob.messages.first().payload }
        alice.send(peerIdBob, payload)
        advanceUntilIdle()

        assertContentEquals(payload, received)
        job.cancel()
        alice.stop(); bob.stop()
    }

    @Test
    fun smallPayloadSkipsCompression() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val tBob = VirtualMeshTransport(peerIdBob)
        tAlice.linkTo(tBob)
        val config = compressionConfig { compressionMinBytes = 500 }
        val crypto = CryptoProvider()

        val alice = MeshLink(tAlice, config, coroutineContext, clock = { testScheduler.currentTime }, crypto = crypto)
        val bob = MeshLink(tBob, config, coroutineContext, clock = { testScheduler.currentTime }, crypto = crypto)
        alice.start(); bob.start()
        advanceUntilIdle()

        tAlice.simulateDiscovery(peerIdBob)
        tBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        val payload = byteArrayOf(1, 2, 3, 4, 5)
        var received: ByteArray? = null
        val job = launch { received = bob.messages.first().payload }
        alice.send(peerIdBob, payload)
        advanceUntilIdle()

        assertContentEquals(payload, received)
        job.cancel()
        alice.stop(); bob.stop()
    }

    @Test
    fun compressionDisabledSendsRaw() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val tBob = VirtualMeshTransport(peerIdBob)
        tAlice.linkTo(tBob)
        val config = meshLinkConfig {
            gossipIntervalMillis = 0L
            keepaliveIntervalMillis = 0L
            deliveryTimeoutMillis = 0L
            compressionEnabled = false
        }
        val crypto = CryptoProvider()

        val alice = MeshLink(tAlice, config, coroutineContext, clock = { testScheduler.currentTime }, crypto = crypto)
        val bob = MeshLink(tBob, config, coroutineContext, clock = { testScheduler.currentTime }, crypto = crypto)
        alice.start(); bob.start()
        advanceUntilIdle()

        tAlice.simulateDiscovery(peerIdBob)
        tBob.simulateDiscovery(peerIdAlice)
        advanceUntilIdle()

        val payload = ByteArray(200) { (it % 10).toByte() }
        var received: ByteArray? = null
        val job = launch { received = bob.messages.first().payload }
        alice.send(peerIdBob, payload)
        advanceUntilIdle()

        assertContentEquals(payload, received)
        job.cancel()
        alice.stop(); bob.stop()
    }

    @Test
    fun envelopeWrapUnwrapWithCompression() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val config = compressionConfig()

        val meshLink = MeshLink(tAlice, config, coroutineContext, clock = { testScheduler.currentTime })

        // Large payload should be wrapped with envelope
        val payload = ByteArray(200) { (it % 10).toByte() }
        val wrapped = meshLink.wrapPayloadEnvelope(payload)
        assertTrue(wrapped.size > 1, "Wrapped payload should have envelope prefix")

        val unwrapped = meshLink.unwrapPayloadEnvelope(wrapped)
        assertContentEquals(payload, unwrapped)
    }

    @Test
    fun envelopeWrapUnwrapSmallPayload() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val config = compressionConfig { compressionMinBytes = 500 }

        val meshLink = MeshLink(tAlice, config, coroutineContext, clock = { testScheduler.currentTime })

        // Small payload: uncompressed envelope (0x00 prefix + raw data)
        val payload = byteArrayOf(1, 2, 3)
        val wrapped = meshLink.wrapPayloadEnvelope(payload)
        assertEquals(4, wrapped.size, "Should be 1 byte flag + 3 bytes payload")
        assertEquals(0x00.toByte(), wrapped[0], "First byte should be UNCOMPRESSED flag")

        val unwrapped = meshLink.unwrapPayloadEnvelope(wrapped)
        assertContentEquals(payload, unwrapped)
    }

    @Test
    fun envelopeSkippedWhenCompressionDisabled() = runTest {
        val tAlice = VirtualMeshTransport(peerIdAlice)
        val config = meshLinkConfig {
            gossipIntervalMillis = 0L
            keepaliveIntervalMillis = 0L
            deliveryTimeoutMillis = 0L
            compressionEnabled = false
        }

        val meshLink = MeshLink(tAlice, config, coroutineContext, clock = { testScheduler.currentTime })

        val payload = ByteArray(200) { (it % 10).toByte() }
        val wrapped = meshLink.wrapPayloadEnvelope(payload)
        assertContentEquals(payload, wrapped, "Should return raw payload when compression disabled")

        val unwrapped = meshLink.unwrapPayloadEnvelope(wrapped)
        assertContentEquals(payload, unwrapped, "Should return raw payload when compression disabled")
    }
}
