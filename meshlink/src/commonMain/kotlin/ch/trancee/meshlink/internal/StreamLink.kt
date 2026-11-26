package ch.trancee.meshlink.internal

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A GATT link's ordered byte stream, either end: [write] cuts the bytes into chunks of at most
 * `attMtu - 3` and [send]s them in order; [receive] queues the peer's chunks on [incoming]. A chunk
 * that fails to send breaks the stream, so the link disconnects.
 */
internal abstract class StreamLink(
    override val peer: PeerHandle,
) : RadioLink {
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    private val closing = MutableStateFlow(false)

    /** Whether the link has closed. */
    val isClosed: Boolean get() = closing.value

    override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()

    final override var platformCode: Int? = null
        private set

    override suspend fun write(bytes: ByteArray): RadioResult<Unit> {
        val size = attMtu - RadioPort.ATT_HEADER
        val chunks = (bytes.indices step size).map { bytes.copyOfRange(it, minOf(it + size, bytes.size)) }
        val sent = !isClosed && chunks.all { send(it) }
        if (!sent) disconnect()
        return if (sent) RadioResult.Ok(Unit) else RadioResult.Failed(RadioFailure.LINK_CLOSED)
    }

    override fun disconnect() {
        if (!isClosed) release()
        close()
    }

    /** Queues the peer's next [chunk] on [incoming]; dropped once the link has closed. */
    fun receive(chunk: ByteArray) {
        inbox.trySend(chunk)
    }

    /**
     * Closes the link at this end, once: records [platformCode], the platform's status if the
     * platform ended the connection, then completes [incoming] and calls [closed].
     */
    fun close(platformCode: Int? = null) {
        if (closing.compareAndSet(false, true)) {
            this.platformCode = platformCode
            inbox.close()
            closed()
        }
    }

    /** Sends one [chunk] and waits until the stack took it; false if it did not. */
    protected abstract suspend fun send(chunk: ByteArray): Boolean

    /** Asks the stack to end the connection; [disconnect] calls it before [close]. */
    protected abstract fun release()

    /** Called once when the link closes, whichever side closed it. */
    protected abstract fun closed()
}
