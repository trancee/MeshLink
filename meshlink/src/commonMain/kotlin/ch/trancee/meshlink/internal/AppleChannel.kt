package ch.trancee.meshlink.internal

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One unencrypted L2CAP CoC channel to [peer] over a `CBL2CAPChannel`'s streams (spec "Bearer
 * switch"; library rule R2), opened as it is made. Each `hasBytesAvailable` reads every available
 * chunk onto [incoming]; the end of the input or an end or error on either stream closes the
 * channel. Writes go whole, one at a time, waiting while the output stream has no space until
 * `hasSpaceAvailable`; a failed write closes the channel. [onClosed] runs once, when it closes.
 */
internal class AppleChannel(
    override val peer: PeerHandle,
    private val channel: CbChannel,
    private val onClosed: (AppleChannel) -> Unit,
) : RadioStream,
    CbChannelEvents {
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    private val writing = Mutex()
    private var closed = false

    /** Counts the moments the output stream may have space, or the channel closed. */
    private val wakeups = MutableStateFlow(0)

    init {
        channel.open(this)
    }

    override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()

    override fun readable() {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (!closed && channel.hasBytes) {
            val count = channel.read(buffer)
            if (count > 0) inbox.trySend(buffer.copyOf(count)) else disconnect()
        }
    }

    override fun writable() {
        wakeups.value++
    }

    override fun ended() = disconnect()

    override suspend fun write(bytes: ByteArray): RadioResult<Unit> =
        writing.withLock {
            var offset = 0
            while (!closed && offset < bytes.size) {
                val seen = wakeups.value
                if (channel.hasSpace) {
                    val count = channel.write(bytes, offset)
                    if (count > 0) offset += count else disconnect()
                } else {
                    wakeups.first { it != seen }
                }
            }
            if (closed) RadioResult.Failed(RadioFailure.LINK_CLOSED) else RadioResult.Ok(Unit)
        }

    override fun disconnect() {
        if (!closed) {
            closed = true
            channel.close()
            inbox.close()
            wakeups.value++
            onClosed(this)
        }
    }

    private companion object {
        /** The most bytes one read takes; the stream carries chunks of any size. */
        const val READ_BUFFER_BYTES = 4_096
    }
}
