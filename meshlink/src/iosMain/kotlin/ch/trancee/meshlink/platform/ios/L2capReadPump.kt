@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.PeerId
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.Foundation.NSInputStream

internal class L2capReadTiming
internal constructor(
    internal val nowMillis: () -> Long,
    internal val activePollIntervalMs: Long,
    internal val idlePollIntervalMs: Long,
)

internal class L2capReadPumpDependencies
internal constructor(
    internal val hintPeerIdProvider: () -> PeerId,
    internal val telemetryEnabled: Boolean,
    internal val telemetryLogger: (String) -> Unit,
    internal val timing: L2capReadTiming,
    internal val awaitReadable: suspend () -> Boolean,
)

internal fun selectReadPollIntervalMs(
    readBytes: Int,
    activePollIntervalMs: Long,
    idlePollIntervalMs: Long,
): Long {
    return if (readBytes > 0) activePollIntervalMs else idlePollIntervalMs
}

internal class L2capReadPump
internal constructor(
    private val inputStream: NSInputStream,
    private val frameBuffer: L2capFrameBuffer,
    private val dependencies: L2capReadPumpDependencies,
) {
    private val readBuffer = ByteArray(STREAM_BUFFER_BYTES)
    private var readSequence: Long = 0L
    private var lastReadFrameAtMs: Long? = null

    internal suspend fun runLoop(onFrameReceived: suspend (ByteArray) -> Unit): Unit {
        var keepReading = true
        while (keepReading) {
            val drainedFrames = drainReadableFrames()
            if (drainedFrames.frames.isEmpty()) {
                if (drainedFrames.streamClosed) {
                    keepReading = false
                } else {
                    keepReading = dependencies.awaitReadable()
                }
            } else {
                drainedFrames.frames.forEach { payload -> onFrameReceived(payload) }
                keepReading = !drainedFrames.streamClosed
            }
        }
    }

    internal fun drainReadableFrames(): ReadDrainResult {
        if (!inputStream.hasBytesAvailable()) {
            return ReadDrainResult(streamClosed = isStreamClosed(inputStream))
        }
        val decodedFrames = mutableListOf<ByteArray>()
        var readBytes = 0
        var readCalls = 0
        var streamClosed = false
        var shouldContinueReading = inputStream.hasBytesAvailable()
        while (shouldContinueReading) {
            val bytesRead = readBuffer.usePinned { pinned ->
                inputStream.read(pinned.addressOf(0).reinterpret(), readBuffer.size.convert())
            }
            when {
                bytesRead < 0 -> {
                    streamClosed = true
                    shouldContinueReading = false
                }

                bytesRead == 0L -> {
                    shouldContinueReading = false
                }

                else -> {
                    val readCount = bytesRead.toInt()
                    readBytes += readCount
                    readCalls += 1
                    decodedFrames += frameBuffer.append(source = readBuffer, length = readCount)
                    shouldContinueReading =
                        readCount == readBuffer.size && inputStream.hasBytesAvailable()
                }
            }
        }
        emitReadTelemetry(
            decodedFrames = decodedFrames,
            readBytes = readBytes,
            readCalls = readCalls,
        )
        return ReadDrainResult(
            frames = decodedFrames,
            readBytes = readBytes,
            readCalls = readCalls,
            streamClosed = streamClosed,
        )
    }

    private fun emitReadTelemetry(
        decodedFrames: List<ByteArray>,
        readBytes: Int,
        readCalls: Int,
    ): Unit {
        if (!dependencies.telemetryEnabled || decodedFrames.isEmpty()) {
            return
        }
        decodedFrames.forEachIndexed { frameIndex, payload ->
            val classification = classifyL2capFrame(payload)
            val nowMs = dependencies.timing.nowMillis()
            val interarrivalMs =
                lastReadFrameAtMs?.let { previousAtMs -> nowMs - previousAtMs } ?: -1L
            lastReadFrameAtMs = nowMs
            emitL2capTelemetry(
                telemetryLogger = dependencies.telemetryLogger,
                event = "read.frame",
                fields =
                    mapOf(
                        "seq" to (++readSequence).toString(),
                        "peer" to dependencies.hintPeerIdProvider().logSuffix(),
                        "batchBytes" to readBytes.toString(),
                        "batchFrames" to decodedFrames.size.toString(),
                        "readCalls" to readCalls.toString(),
                        "frameIndex" to (frameIndex + 1).toString(),
                        "streamReady" to inputStream.hasBytesAvailable().toString(),
                        "directType" to classification.directType,
                        "dataClass" to classification.dataClass,
                        "frameBytes" to payload.size.toString(),
                        "innerBytes" to classification.innerBytes.toString(),
                        "interarrivalMs" to interarrivalMs.toString(),
                    ),
            )
        }
    }

    internal data class ReadDrainResult(
        val frames: List<ByteArray> = emptyList(),
        val readBytes: Int = 0,
        val readCalls: Int = 0,
        val streamClosed: Boolean = false,
    )

    private companion object {
        private const val STREAM_BUFFER_BYTES: Int = 16 * 1024
    }
}
