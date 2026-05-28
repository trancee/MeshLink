@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ch.trancee.meshlink.platform.ios

import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
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
import platform.darwin.NSObject

internal class StreamReadinessSignal {
    private val signals = Channel<Unit>(capacity = Channel.CONFLATED)

    internal fun signal(): Unit {
        signals.trySend(Unit)
    }

    internal suspend fun await(timeoutMs: Long? = null): Boolean {
        return if (timeoutMs == null) {
            signals.receiveCatching().isSuccess
        } else {
            withTimeoutOrNull(timeoutMs) { signals.receiveCatching().isSuccess } ?: false
        }
    }

    internal fun close(): Unit {
        signals.close()
    }
}

internal class StreamReadinessBinding
private constructor(
    private val signal: StreamReadinessSignal,
    private val stream: NSStream,
    private val runLoop: NSRunLoop,
    @Suppress("unused") private val delegate: StreamReadinessDelegate,
) {
    internal suspend fun await(timeoutMs: Long? = null): Boolean {
        return signal.await(timeoutMs)
    }

    internal fun signal(): Unit {
        signal.signal()
    }

    internal fun close(): Unit {
        stream.removeFromRunLoop(runLoop, NSDefaultRunLoopMode)
        stream.delegate = null
        signal.close()
    }

    internal companion object {
        internal fun forInputStream(inputStream: NSInputStream): StreamReadinessBinding {
            return create(
                stream = inputStream,
                readyEvent = NSStreamEventHasBytesAvailable,
                initiallyReady = inputStream.hasBytesAvailable(),
            )
        }

        internal fun forOutputStream(outputStream: NSOutputStream): StreamReadinessBinding {
            return create(
                stream = outputStream,
                readyEvent = NSStreamEventHasSpaceAvailable,
                initiallyReady = outputStream.hasSpaceAvailable(),
            )
        }

        private fun create(
            stream: NSStream,
            readyEvent: NSStreamEvent,
            initiallyReady: Boolean,
        ): StreamReadinessBinding {
            val signal = StreamReadinessSignal()
            val delegate = StreamReadinessDelegate(signal = signal, readyEvent = readyEvent)
            val runLoop = NSRunLoop.mainRunLoop
            stream.delegate = delegate
            stream.scheduleInRunLoop(runLoop, NSDefaultRunLoopMode)
            if (initiallyReady) {
                signal.signal()
            }
            return StreamReadinessBinding(
                signal = signal,
                stream = stream,
                runLoop = runLoop,
                delegate = delegate,
            )
        }
    }
}

private class StreamReadinessDelegate(
    private val signal: StreamReadinessSignal,
    private val readyEvent: NSStreamEvent,
) : NSObject(), NSStreamDelegateProtocol {
    @ObjCSignatureOverride
    override fun stream(aStream: NSStream, handleEvent: NSStreamEvent) {
        when (handleEvent) {
            readyEvent,
            NSStreamEventEndEncountered,
            NSStreamEventErrorOccurred -> signal.signal()
        }
    }
}
