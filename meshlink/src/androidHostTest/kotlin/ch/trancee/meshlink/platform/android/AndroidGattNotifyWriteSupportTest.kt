package ch.trancee.meshlink.platform.android

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AndroidGattNotifyWriteSupportTest {
    @Test
    fun writeViaAndroidGattNotifyReturnsFalseWhenTheClientIsNotReady(): Unit = runBlocking {
        // Arrange
        val fixture = AndroidGattNotifyWriteFixture(clientReady = false)

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
        val fixture = AndroidGattNotifyWriteFixture(hasGatt = false)

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
        val fixture = AndroidGattNotifyWriteFixture(maxChunkBytes = 4, encoded = encoded)

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
            AndroidGattNotifyWriteFixture(
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
    }
}

private class AndroidGattNotifyWriteFixture(
    private val clientReady: Boolean = true,
    private val hasGatt: Boolean = true,
    private val hasWriteCharacteristic: Boolean = true,
    private val maxChunkBytes: Int = 4,
    private val encoded: ByteArray = byteArrayOf(0x01, 0x02, 0x03),
    private val chunkResults: List<Boolean> = List(16) { true },
) {
    var encodeCalls: Int = 0
    val chunkCalls: MutableList<ChunkCall> = mutableListOf()

    suspend fun write(payload: ByteArray): Boolean {
        return writeViaAndroidGattNotify(
            payload = payload,
            context =
                AndroidGattNotifyWriteContext(
                    peerLogSuffix = "abc123",
                    clientReady = clientReady,
                    hasGatt = hasGatt,
                    hasWriteCharacteristic = hasWriteCharacteristic,
                    maxChunkBytes = maxChunkBytes,
                ),
            dependencies =
                AndroidGattNotifyWriteDependencies(
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
                    log = {},
                ),
        )
    }
}

private data class ChunkCall(val payloadBytes: Int, val encodedBytes: Int, val chunk: ByteArray)
