@file:OptIn(ExperimentalForeignApi::class)

package ch.trancee.meshlink.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreBluetooth.CBL2CAPChannel
import platform.Foundation.NSInputStream
import platform.Foundation.NSOutputStream
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSStream
import platform.Foundation.NSStreamDelegateProtocol
import platform.Foundation.NSStreamEvent
import platform.Foundation.NSStreamEventEndEncountered
import platform.Foundation.NSStreamEventErrorOccurred
import platform.Foundation.NSStreamEventHasBytesAvailable
import platform.Foundation.NSStreamEventHasSpaceAvailable
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_t

/**
 * The thin layer over a `CBL2CAPChannel` (Constitution T3, `config/thin-adapters.txt`): each call
 * calls one `NSInputStream` or `NSOutputStream` method. Holding [channel] keeps it open. Its
 * streams run on the main run loop in its common modes, as the bake-off's did (ADR 0009), and
 * each stream event moves to [queue], where CoreBluetooth and the node run. Device runs prove it
 * (ticket 17).
 */
internal class IosChannel(
    private val channel: CBL2CAPChannel,
    private val queue: dispatch_queue_t,
) : CbChannel {
    private val input: NSInputStream = checkNotNull(channel.inputStream)
    private val output: NSOutputStream = checkNotNull(channel.outputStream)
    private val streams = listOf<NSStream>(input, output)
    private var delegate: StreamDelegate? = null

    override val peerId: String = checkNotNull(channel.peer).identifier.UUIDString

    override val hasBytes: Boolean get() = input.hasBytesAvailable

    override val hasSpace: Boolean get() = output.hasSpaceAvailable

    override fun open(events: CbChannelEvents) {
        val handler = StreamDelegate(events, queue)
        delegate = handler
        streams.forEach {
            it.delegate = handler
            it.scheduleInRunLoop(NSRunLoop.mainRunLoop, NSRunLoopCommonModes)
            it.open()
        }
    }

    override fun read(buffer: ByteArray): Int =
        buffer.usePinned { input.read(it.addressOf(0).reinterpret(), buffer.size.convert()).toInt() }

    override fun write(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        bytes.usePinned {
            output.write(it.addressOf(offset).reinterpret(), (bytes.size - offset).convert()).toInt()
        }

    override fun close() {
        streams.forEach {
            it.delegate = null
            it.close()
            it.removeFromRunLoop(NSRunLoop.mainRunLoop, NSRunLoopCommonModes)
        }
        delegate = null
    }
}

/** Passes each stream event on to [events] on [queue]. */
private class StreamDelegate(
    private val events: CbChannelEvents,
    private val queue: dispatch_queue_t,
) : NSObject(),
    NSStreamDelegateProtocol {
    override fun stream(
        aStream: NSStream,
        handleEvent: NSStreamEvent,
    ) = dispatch_async(queue) {
        when (handleEvent) {
            NSStreamEventHasBytesAvailable -> events.readable()
            NSStreamEventHasSpaceAvailable -> events.writable()
            NSStreamEventEndEncountered, NSStreamEventErrorOccurred -> events.ended()
            else -> Unit
        }
    }
}
