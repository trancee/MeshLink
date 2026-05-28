package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.TransportEvent
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal class InboundFrameQueue
internal constructor(
    private val scope: CoroutineScope,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val onFrameReceived: suspend (TransportEvent.FrameReceived) -> Unit,
) : Closeable {
    private val channel: Channel<TransportEvent.FrameReceived> = Channel(capacity = capacity)
    private val dispatchJob: Job = scope.launch {
        for (event in channel) {
            onFrameReceived(event)
        }
    }

    init {
        require(capacity > 0) { "capacity must be greater than zero" }
    }

    internal fun enqueue(peerId: PeerId, payload: ByteArray): Boolean {
        return enqueue(TransportEvent.FrameReceived(peerId = peerId, payload = payload))
    }

    internal fun enqueue(event: TransportEvent.FrameReceived): Boolean {
        return channel.trySend(event).isSuccess
    }

    override fun close(): Unit {
        channel.close()
        dispatchJob.cancel()
    }

    internal companion object {
        internal const val DEFAULT_CAPACITY: Int = 64
    }
}
