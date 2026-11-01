package bakeoff.ios

import bakeoff.ChannelByteLink
import bakeoff.LinkClosedException
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreBluetooth.CBL2CAPChannel
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSInputStream
import platform.Foundation.NSOutputStream
import platform.Foundation.NSRunLoop
import platform.Foundation.NSStream
import platform.Foundation.NSStreamDelegateProtocol
import platform.Foundation.NSStreamEvent
import platform.Foundation.NSStreamEventEndEncountered
import platform.Foundation.NSStreamEventErrorOccurred
import platform.Foundation.NSStreamEventHasBytesAvailable
import platform.Foundation.NSStreamEventHasSpaceAvailable
import platform.Foundation.NSStreamEventOpenCompleted
import platform.darwin.NSObject

/**
 * L2CAP CoC byte stream over the channel's NSInputStream/NSOutputStream, scheduled on the main run loop
 * (all bake-off code runs on the main thread, so stream events and coroutines never race).
 */
class StreamByteLink(private val channel: CBL2CAPChannel, label: String) : ChannelByteLink(label) {
    private val input: NSInputStream = channel.inputStream ?: throw IllegalStateException("channel without input stream")
    private val output: NSOutputStream = channel.outputStream ?: throw IllegalStateException("channel without output stream")
    private val writeMutex = Mutex()
    private var spaceAvailable: CompletableDeferred<Unit>? = null
    private var closedReason: String? = null
    private val readBuffer = ByteArray(8192)

    private val delegate = object : NSObject(), NSStreamDelegateProtocol {
        override fun stream(aStream: NSStream, handleEvent: NSStreamEvent) {
            when (handleEvent) {
                NSStreamEventOpenCompleted -> Unit
                NSStreamEventHasBytesAvailable -> drainInput()
                NSStreamEventHasSpaceAvailable -> spaceAvailable?.complete(Unit)
                NSStreamEventEndEncountered -> close("stream end")
                NSStreamEventErrorOccurred -> close("stream error ${aStream.streamError?.describe()}")
                else -> Unit
            }
        }
    }

    init {
        for (stream in listOf<NSStream>(input, output)) {
            stream.delegate = delegate
            stream.scheduleInRunLoop(NSRunLoop.mainRunLoop, NSDefaultRunLoopMode)
            stream.open()
        }
    }

    private fun drainInput() {
        while (closedReason == null && input.hasBytesAvailable) {
            val count = readBuffer.usePinned { input.read(it.addressOf(0).reinterpret(), readBuffer.size.convert()) }
            if (count <= 0) {
                if (count < 0) close("read error ${input.streamError?.describe()}")
                return
            }
            deliver(readBuffer.copyOf(count.toInt()))
        }
    }

    override suspend fun write(bytes: ByteArray) {
        writeMutex.withLock {
            var offset = 0
            while (offset < bytes.size) {
                closedReason?.let { throw LinkClosedException("$label closed: $it") }
                if (!output.hasSpaceAvailable) {
                    val waiter = CompletableDeferred<Unit>()
                    spaceAvailable = waiter
                    // HasSpaceAvailable is edge-triggered; the timeout re-polls in case the edge was consumed earlier.
                    withTimeoutOrNull(50) { waiter.await() }
                    spaceAvailable = null
                    continue
                }
                val written = bytes.usePinned {
                    output.write(it.addressOf(offset).reinterpret(), (bytes.size - offset).convert())
                }
                if (written < 0) {
                    close("write error ${output.streamError?.describe()}")
                    throw LinkClosedException("$label write failed")
                }
                offset += written.toInt()
            }
        }
    }

    override fun close(reason: String) {
        if (closedReason != null) return
        closedReason = reason
        spaceAvailable?.complete(Unit)
        for (stream in listOf<NSStream>(input, output)) {
            stream.delegate = null
            stream.close()
            stream.removeFromRunLoop(NSRunLoop.mainRunLoop, NSDefaultRunLoopMode)
        }
        closeInbound(reason)
    }

    /** Keeps the CBL2CAPChannel alive: CoreBluetooth closes the channel when its last reference goes away. */
    val psm: Int get() = channel.PSM.toInt()
}
