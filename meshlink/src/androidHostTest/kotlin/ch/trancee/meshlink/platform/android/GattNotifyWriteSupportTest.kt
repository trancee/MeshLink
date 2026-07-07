package ch.trancee.meshlink.platform.android

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class GattNotifyWriteSupportTest {
    @Test
    fun writeViaAndroidGattNotifyReturnsFalseWhenTheClientIsNotReady(): Unit = runBlocking {
        // Arrange
        val fixture = GattNotifyWriteFixture(clientReady = false)

        // Act
        val written = fixture.write(payload = byteArrayOf(0x01, 0x02))

        // Assert
        assertFalse(written)
        assertEquals(0, fixture.encodeCalls)
        assertEquals(0, fixture.chunkCalls.size)
    }

    @Test
    fun writeViaAndroidGattNotifyReturnsFalseWhenGattStateIsMissing(): Unit = runBlocking {
        // Arrange
        val fixture = GattNotifyWriteFixture(hasGatt = false)

        // Act
        val written = fixture.write(payload = byteArrayOf(0x01, 0x02))

        // Assert
        assertFalse(written)
        assertEquals(0, fixture.encodeCalls)
        assertEquals(0, fixture.chunkCalls.size)
    }

    @Test
    fun writeViaAndroidGattNotifySplitsEncodedPayloadAcrossChunks(): Unit = runBlocking {
        // Arrange
        val encoded = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09)
        val fixture = GattNotifyWriteFixture(maxChunkBytes = 4, encoded = encoded)

        // Act
        val written = fixture.write(payload = byteArrayOf(0x55, 0x66))

        // Assert
        assertTrue(written)
        assertEquals(1, fixture.encodeCalls)
        assertEquals(3, fixture.chunkCalls.size)
        assertEquals(2, fixture.chunkCalls[0].payloadBytes)
        assertEquals(9, fixture.chunkCalls[0].encodedBytes)
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), fixture.chunkCalls[0].chunk)
        assertContentEquals(byteArrayOf(0x05, 0x06, 0x07, 0x08), fixture.chunkCalls[1].chunk)
        assertContentEquals(byteArrayOf(0x09), fixture.chunkCalls[2].chunk)
    }

    @Test
    fun writeViaAndroidGattNotifyStopsAfterTheFirstChunkFailure(): Unit = runBlocking {
        // Arrange
        val encoded = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val fixture =
            GattNotifyWriteFixture(
                maxChunkBytes = 2,
                encoded = encoded,
                chunkResults = listOf(true, false, true),
            )

        // Act
        val written = fixture.write(payload = byteArrayOf(0x55, 0x66))

        // Assert
        assertFalse(written)
        assertEquals(2, fixture.chunkCalls.size)
        assertContentEquals(byteArrayOf(0x01, 0x02), fixture.chunkCalls[0].chunk)
        assertContentEquals(byteArrayOf(0x03, 0x04), fixture.chunkCalls[1].chunk)
        // A chunk-level enqueue failure aborts before ever draining pipelined writes.
        assertEquals(0, fixture.drainCalls)
    }

    @Test
    fun writeViaAndroidGattNotifyDrainsPipelinedWritesAfterAllChunksAreEnqueued(): Unit =
        runBlocking {
            // Arrange
            val encoded = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09)
            val fixture = GattNotifyWriteFixture(maxChunkBytes = 4, encoded = encoded)

            // Act
            val written = fixture.write(payload = byteArrayOf(0x55, 0x66))

            // Assert: every chunk is enqueued before the pipeline is drained exactly once.
            assertTrue(written)
            assertEquals(3, fixture.chunkCalls.size)
            assertEquals(1, fixture.drainCalls)
        }

    @Test
    fun writeViaAndroidGattNotifyReturnsFalseWhenDrainReportsAFailure(): Unit = runBlocking {
        // Arrange: every chunk enqueues successfully, but a pipelined write later fails.
        val fixture = GattNotifyWriteFixture(maxChunkBytes = 4, drainResult = false)

        // Act
        val written = fixture.write(payload = byteArrayOf(0x55, 0x66))

        // Assert
        assertFalse(written)
        assertEquals(1, fixture.drainCalls)
    }
}

private class GattNotifyWriteFixture(
    private val clientReady: Boolean = true,
    private val hasGatt: Boolean = true,
    private val hasWriteCharacteristic: Boolean = true,
    private val maxChunkBytes: Int = 4,
    private val encoded: ByteArray = byteArrayOf(0x01, 0x02, 0x03),
    private val chunkResults: List<Boolean> = List(16) { true },
    private val drainResult: Boolean = true,
) {
    var encodeCalls: Int = 0
    var drainCalls: Int = 0
    val chunkCalls: MutableList<ChunkCall> = mutableListOf()

    suspend fun write(payload: ByteArray): Boolean {
        return writeViaGattNotify(
            payload = payload,
            context =
                GattNotifyWriteContext(
                    peerLogSuffix = "abc123",
                    clientReady = clientReady,
                    hasGatt = hasGatt,
                    hasWriteCharacteristic = hasWriteCharacteristic,
                    maxChunkBytes = maxChunkBytes,
                ),
            dependencies =
                GattNotifyWriteDependencies(
                    encode = {
                        encodeCalls += 1
                        encoded
                    },
                    writeChunk = { payloadBytes, encodedBytes, chunk ->
                        chunkCalls +=
                            ChunkCall(
                                payloadBytes = payloadBytes,
                                encodedBytes = encodedBytes,
                                chunk = chunk,
                            )
                        chunkResults[chunkCalls.lastIndex]
                    },
                    drain = {
                        drainCalls += 1
                        drainResult
                    },
                    log = {},
                ),
        )
    }
}

private data class ChunkCall(val payloadBytes: Int, val encodedBytes: Int, val chunk: ByteArray)
