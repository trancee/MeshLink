package ch.trancee.meshlink.platform.ios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSStreamStatusClosed

class IosL2capOutputWriterTest {
    @Test
    fun writeRetriesReadyFalseUntilSpaceBecomesAvailable(): Unit = runBlocking {
        // Arrange
        val adapter =
            FakeIosL2capOutputStreamAdapter(
                hasSpaceResults = listOf(false, true, true),
                writeResults = listOf(4L, 2L),
            )
        val timing = advancingWriteTiming(startAtMs = 100L, stepMs = 10L, activePollIntervalMs = 0L)
        val writer = IosL2capOutputWriter(outputStream = adapter, timing = timing)
        val buffer = byteArrayOf(1, 2, 3, 4, 5, 6)

        // Act
        val stats = writer.write(buffer)

        // Assert
        assertEquals(2, stats.writeCalls)
        assertEquals(1, stats.writeBatches)
        assertEquals(1, stats.backpressureSpins)
        assertEquals(1, stats.readyFalseCount)
        assertEquals(2, stats.minWriteChunkBytes)
        assertEquals(4, stats.maxWriteChunkBytes)
        assertEquals(
            listOf(
                WriteCall(offset = 0, requestedBytes = 6),
                WriteCall(offset = 4, requestedBytes = 2),
            ),
            adapter.writeCalls,
        )
    }

    @Test
    fun writeTreatsZeroWriteAsBackpressureAndRetries(): Unit = runBlocking {
        // Arrange
        val adapter =
            FakeIosL2capOutputStreamAdapter(
                hasSpaceResults = listOf(true, true),
                writeResults = listOf(0L, 3L),
            )
        val timing = advancingWriteTiming(startAtMs = 100L, stepMs = 10L, activePollIntervalMs = 0L)
        val writer = IosL2capOutputWriter(outputStream = adapter, timing = timing)
        val buffer = byteArrayOf(1, 2, 3)

        // Act
        val stats = writer.write(buffer)

        // Assert
        assertEquals(2, stats.writeCalls)
        assertEquals(1, stats.backpressureSpins)
        assertEquals(0, stats.readyFalseCount)
        assertEquals(3, stats.minWriteChunkBytes)
        assertEquals(3, stats.maxWriteChunkBytes)
        assertEquals(
            listOf(
                WriteCall(offset = 0, requestedBytes = 3),
                WriteCall(offset = 0, requestedBytes = 3),
            ),
            adapter.writeCalls,
        )
    }

    @Test
    fun writeFailsWhenTheStreamIsAlreadyClosed(): Unit = runBlocking {
        // Arrange
        val adapter = FakeIosL2capOutputStreamAdapter(currentStreamStatus = NSStreamStatusClosed)
        val timing = advancingWriteTiming(startAtMs = 100L, stepMs = 10L, activePollIntervalMs = 0L)
        val writer = IosL2capOutputWriter(outputStream = adapter, timing = timing)

        // Act
        val error = assertFailsWith<IllegalStateException> { writer.write(byteArrayOf(1)) }

        // Assert
        assertEquals("iOS L2CAP output stream closed", error.message)
    }

    @Test
    fun writeFailsWhenReadyFalsePersistsPastTheStallTimeout(): Unit = runBlocking {
        // Arrange
        val adapter = FakeIosL2capOutputStreamAdapter(hasSpaceResults = List(4) { false })
        val timing =
            advancingWriteTiming(startAtMs = 0L, stepMs = 6_000L, activePollIntervalMs = 0L)
        val writer = IosL2capOutputWriter(outputStream = adapter, timing = timing)

        // Act
        val error = assertFailsWith<IllegalStateException> { writer.write(byteArrayOf(1)) }

        // Assert
        assertEquals("iOS L2CAP output stream stalled", error.message)
    }
}

private class FakeIosL2capOutputStreamAdapter(
    private val currentStreamStatus: ULong = 2u,
    private val errorPresent: Boolean = false,
    hasSpaceResults: List<Boolean> = listOf(true),
    writeResults: List<Long> = listOf(1L),
) : IosL2capOutputStreamAdapter {
    private val hasSpaceQueue: ArrayDeque<Boolean> = ArrayDeque(hasSpaceResults)
    private val writeQueue: ArrayDeque<Long> = ArrayDeque(writeResults)
    val writeCalls: MutableList<WriteCall> = mutableListOf()

    override fun streamStatus(): ULong {
        return currentStreamStatus
    }

    override fun hasError(): Boolean {
        return errorPresent
    }

    override fun hasSpaceAvailable(): Boolean {
        return if (hasSpaceQueue.isNotEmpty()) hasSpaceQueue.removeFirst() else true
    }

    override fun write(buffer: ByteArray, offset: Int, requestedBytes: Int): Long {
        writeCalls += WriteCall(offset = offset, requestedBytes = requestedBytes)
        return if (writeQueue.isNotEmpty()) writeQueue.removeFirst() else requestedBytes.toLong()
    }
}

private data class WriteCall(val offset: Int, val requestedBytes: Int)

private fun advancingWriteTiming(
    startAtMs: Long,
    stepMs: Long,
    activePollIntervalMs: Long,
): IosL2capWriteTiming {
    var nowMs = startAtMs
    return IosL2capWriteTiming(
        nowMillis = {
            val current = nowMs
            nowMs += stepMs
            current
        },
        activePollIntervalMs = activePollIntervalMs,
    )
}
