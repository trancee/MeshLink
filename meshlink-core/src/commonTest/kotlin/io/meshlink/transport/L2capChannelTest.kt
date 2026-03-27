package io.meshlink.transport

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class L2capChannelTest {

    // Concrete test implementation of the interface
    private class TestL2capChannel(
        override val psm: Int,
        private var _isOpen: Boolean = true,
    ) : L2capChannel {
        private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
        override val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()
        override val isOpen: Boolean get() = _isOpen

        val writtenData = mutableListOf<ByteArray>()

        override suspend fun write(data: ByteArray) {
            check(_isOpen) { "Channel is closed" }
            writtenData.add(data)
        }

        override fun close() {
            _isOpen = false
        }

        suspend fun emitIncoming(data: ByteArray) {
            _incoming.emit(data)
        }
    }

    @Test
    fun channelReportsPsm() {
        val ch = TestL2capChannel(psm = 128)
        assertEquals(128, ch.psm)
    }

    @Test
    fun channelIsOpenByDefault() {
        val ch = TestL2capChannel(psm = 128)
        assertTrue(ch.isOpen)
    }

    @Test
    fun closeMarksChannelClosed() {
        val ch = TestL2capChannel(psm = 128)
        ch.close()
        assertFalse(ch.isOpen)
    }

    @Test
    fun writeRecordsData() = runTest {
        val ch = TestL2capChannel(psm = 128)
        val data = byteArrayOf(1, 2, 3)
        ch.write(data)
        assertEquals(1, ch.writtenData.size)
        assertTrue(ch.writtenData[0].contentEquals(data))
    }

    @Test
    fun writeAfterCloseThrows() = runTest {
        val ch = TestL2capChannel(psm = 128)
        ch.close()
        var threw = false
        try {
            ch.write(byteArrayOf(1))
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw, "Write on closed channel should throw")
    }

    @Test
    fun incomingFlowReceivesData() = runTest {
        val ch = TestL2capChannel(psm = 128)
        val received = mutableListOf<ByteArray>()

        // Subscribe in the background using backgroundScope (auto-cancelled after test)
        backgroundScope.launch {
            ch.incoming.collect { received.add(it) }
        }
        // Let the collector subscribe before emitting
        yield()

        ch.emitIncoming(byteArrayOf(10, 20))
        yield()
        ch.emitIncoming(byteArrayOf(30, 40))
        yield()

        assertEquals(2, received.size, "Should have received 2 data frames")
        assertTrue(received[0].contentEquals(byteArrayOf(10, 20)))
        assertTrue(received[1].contentEquals(byteArrayOf(30, 40)))
    }
}
