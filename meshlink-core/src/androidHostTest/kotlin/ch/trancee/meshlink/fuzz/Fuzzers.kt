package ch.trancee.meshlink.fuzz

import ch.trancee.meshlink.internal.Advertisement
import ch.trancee.meshlink.internal.BroadcastFrame
import ch.trancee.meshlink.internal.CapabilityRecord
import ch.trancee.meshlink.internal.Decoded
import ch.trancee.meshlink.internal.FloodFrame
import ch.trancee.meshlink.internal.HandshakePayload
import ch.trancee.meshlink.internal.IdentityCertificate
import ch.trancee.meshlink.internal.LinkFrame
import ch.trancee.meshlink.internal.LinkRecord
import ch.trancee.meshlink.internal.MessageContent
import ch.trancee.meshlink.internal.MessageFrame
import ch.trancee.meshlink.internal.ReceiptContent
import ch.trancee.meshlink.internal.RecordReader
import ch.trancee.meshlink.internal.ResolvingKey
import ch.trancee.meshlink.internal.RouteReply
import ch.trancee.meshlink.internal.RouteRequest
import ch.trancee.meshlink.internal.UnicastFrame

// Jazzer targets (ADR 0017 decision 9). A decoder returns a value or a typed error; anything it
// throws, a hang or an unbounded allocation is a finding. A value must re-encode to its input,
// the invariant the deterministic mutation tier already holds for every vector.

object AdvertisementFuzzer {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray): Unit = roundTrip(data, Advertisement.decode(data)) { it.encode() }
}

object CapabilityRecordFuzzer {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray): Unit = roundTrip(data, CapabilityRecord.decode(data)) { it.encode() }
}

/** One record, and the same bytes as a stream cut into chunks of each of [CHUNKS] bytes. */
object LinkRecordFuzzer {
    private val CHUNKS = listOf(1, 7, 64)

    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray) {
        val record = LinkRecord.decode(data)
        roundTrip(data, record, LinkRecord::encode)
        CHUNKS.forEach { size ->
            val reader = RecordReader()
            val bodies =
                (data.indices step size).flatMap { start ->
                    val read = reader.read(data.copyOfRange(start, minOf(start + size, data.size)))
                    (read as? Decoded.Valid)?.value.orEmpty()
                }
            if (record is Decoded.Valid) check(bodies.single().contentEquals(record.value)) { "stream read differs" }
        }
    }
}

object LinkFrameFuzzer {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray): Unit = roundTrip(data, LinkFrame.decode(data)) { it.encode() }
}

object HandshakePayloadFuzzer {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray): Unit = roundTrip(data, HandshakePayload.decode(data)) { it.encode() }
}

object IdentityCertificateFuzzer {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray): Unit = roundTrip(data, IdentityCertificate.decode(data)) { it.encode() }
}

object MessageFrameFuzzer {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray): Unit = roundTrip(data, MessageFrame.decode(data)) { it.encode() }
}

object UnicastFrameFuzzer {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray): Unit = roundTrip(data, UnicastFrame.decode(data)) { it.encode() }
}

object RouteRequestFuzzer {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray): Unit = roundTrip(data, RouteRequest.decode(data)) { it.encode() }
}

object BroadcastFrameFuzzer {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray): Unit = roundTrip(data, BroadcastFrame.decode(data)) { it.encode() }
}

object FloodFrameFuzzer {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray): Unit = roundTrip(data, FloodFrame.decode(data)) { it.encode() }
}

object RouteReplyFuzzer {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray): Unit = roundTrip(data, RouteReply.decode(data)) { it.encode() }
}

/** Both the secured and the unsecured framing. */
object MessageContentFuzzer {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray): Unit =
        listOf(true, false).forEach { secured ->
            roundTrip(data, MessageContent.decode(data, secured)) { it.encode(secured) }
        }
}

/** Both the secured and the unsecured framing. */
object ReceiptContentFuzzer {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray): Unit =
        listOf(true, false).forEach { secured ->
            roundTrip(data, ReceiptContent.decode(data, secured)) { it.encode(secured) }
        }
}

object ResolvingKeyFuzzer {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray): Unit = roundTrip(data, ResolvingKey.decode(data)) { it.encode() }
}

private fun <T> roundTrip(
    data: ByteArray,
    decoded: Decoded<T>,
    encode: (T) -> ByteArray,
) {
    if (decoded is Decoded.Valid) check(encode(decoded.value).contentEquals(data)) { "re-encoding differs" }
}
