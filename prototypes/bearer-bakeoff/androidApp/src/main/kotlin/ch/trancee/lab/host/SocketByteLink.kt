package ch.trancee.lab.host

import android.bluetooth.BluetoothSocket
import bakeoff.ChannelByteLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/** L2CAP CoC stream over a blocking BluetoothSocket; a reader thread feeds the inbound channel. */
class SocketByteLink private constructor(private val socket: BluetoothSocket, label: String) : ChannelByteLink(label) {
    private val output = socket.outputStream

    private fun startReader() {
        thread(name = "reader-$label", isDaemon = true) {
            val buffer = ByteArray(maxOf(socket.maxReceivePacketSize, 4096))
            try {
                val input = socket.inputStream
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) deliver(buffer.copyOf(count))
                }
                closeInbound("eof")
            } catch (failure: Exception) {
                closeInbound("read failed: ${failure.message}")
            }
        }
    }

    override suspend fun write(bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            output.write(bytes)
            output.flush()
        }
    }

    override fun close(reason: String) {
        runCatching { socket.close() }
        closeInbound(reason)
    }

    companion object {
        /** Connects with a watchdog because BluetoothSocket.connect() blocks and ignores coroutine cancellation. */
        suspend fun connect(socket: BluetoothSocket, label: String, timeoutMs: Long = 25_000): SocketByteLink = coroutineScope {
            val watchdog = launch {
                delay(timeoutMs)
                runCatching { socket.close() }
            }
            try {
                withContext(Dispatchers.IO) { socket.connect() }
            } finally {
                watchdog.cancel()
            }
            accepted(socket, label)
        }

        fun accepted(socket: BluetoothSocket, label: String): SocketByteLink = SocketByteLink(socket, label).also { it.startReader() }
    }
}
