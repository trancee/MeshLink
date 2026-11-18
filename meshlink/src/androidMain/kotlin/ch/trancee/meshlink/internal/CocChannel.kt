package ch.trancee.meshlink.internal

import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One unencrypted L2CAP CoC channel to [peer] over [socket] (spec "Bearer switch"; library rule
 * R2). The socket's blocking calls run on [io]. Collecting [incoming] reads the socket: each read
 * becomes one chunk, and EOF or a failed read ends the stream and closes the channel. Writes go
 * whole, one at a time; a failed write closes the channel. Closing the socket aborts a blocked
 * connect, read or write, as the platform does, so a caller cancelled during a connect or read,
 * such as a collector that stops early, closes the channel. [onClosed] runs once, when it closes.
 */
internal class CocChannel(
    override val peer: PeerHandle,
    private val socket: BluetoothSocket,
    private val io: CoroutineDispatcher,
    private val onClosed: (CocChannel) -> Unit,
) : RadioStream {
    private val closed = AtomicBoolean(false)
    private val writing = Mutex()

    override val incoming: Flow<ByteArray> =
        flow {
            val input = socket.inputStream
            val buffer = ByteArray(READ_BUFFER_BYTES)
            var count = blocking { read(input, buffer) }
            while (count >= 0) {
                emit(buffer.copyOf(count))
                count = blocking { read(input, buffer) }
            }
            disconnect()
        }

    /** Connects the socket as a client; false if it failed. */
    suspend fun connect(): Boolean = blocking { runCatching { socket.connect() }.isSuccess }

    override suspend fun write(bytes: ByteArray): RadioResult<Unit> =
        writing.withLock {
            val sent = !closed.get() && withContext(io) { runCatching { socket.outputStream.write(bytes) }.isSuccess }
            if (!sent) disconnect()
            if (sent) RadioResult.Ok(Unit) else RadioResult.Failed(RadioFailure.LINK_CLOSED)
        }

    override fun disconnect() {
        if (closed.compareAndSet(false, true)) {
            runCatching { socket.close() }
            onClosed(this)
        }
    }

    /** Runs the blocking [call] on [io]; cancelling the caller closes the channel, which aborts it. */
    private suspend fun <T> blocking(call: () -> T): T =
        coroutineScope {
            val running = async(io) { call() }
            try {
                running.await()
            } catch (e: CancellationException) {
                disconnect()
                throw e
            }
        }

    /** The bytes read into [buffer], or -1 at EOF or when the read failed. */
    private fun read(
        input: InputStream,
        buffer: ByteArray,
    ): Int = runCatching { input.read(buffer) }.getOrDefault(EOF)

    private companion object {
        /** The most bytes one read takes; the stream carries chunks of any size. */
        const val READ_BUFFER_BYTES = 4_096
        const val EOF = -1
    }
}
