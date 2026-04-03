package io.meshlink.wire

import kotlin.random.Random
import kotlin.test.Test

/**
 * Fuzz tests for all MeshLink wire-format parsers.
 *
 * Each decoder is fed 10,000+ random byte arrays.  Only [IllegalArgumentException]
 * (thrown by `require()`) is caught — any other throwable (e.g.
 * [IndexOutOfBoundsException]) propagates and fails the test, exposing a parser bug.
 */
class WireFormatFuzzTest {

    private val rng = Random(seed = 42)
    private val iterations = 10_000

    // --- helpers --------------------------------------------------------

    private fun randomBytes(maxSize: Int = 512): ByteArray {
        val size = rng.nextInt(0, maxSize + 1)
        return ByteArray(size) { rng.nextInt(256).toByte() }
    }

    /** Random buffer whose first byte is forced to [typeByte]. */
    private fun randomBytesWithType(typeByte: Byte, maxSize: Int = 512): ByteArray {
        val buf = randomBytes(maxSize)
        if (buf.isEmpty()) return buf
        buf[0] = typeByte
        return buf
    }

    // --- per-decoder fuzz tests -----------------------------------------

    @Test
    fun fuzz_decodeHandshake_never_crashes() {
        repeat(iterations) {
            try {
                WireCodec.decodeHandshake(randomBytes())
            } catch (_: IllegalArgumentException) { }
        }
        // Targeted: correct type but truncated / bad step
        repeat(iterations) {
            try {
                WireCodec.decodeHandshake(randomBytesWithType(WireCodec.TYPE_HANDSHAKE, maxSize = 8))
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test
    fun fuzz_decodeChunk_never_crashes() {
        repeat(iterations) {
            try {
                WireCodec.decodeChunk(randomBytes())
            } catch (_: IllegalArgumentException) { }
        }
        repeat(iterations) {
            try {
                WireCodec.decodeChunk(randomBytesWithType(WireCodec.TYPE_CHUNK))
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test
    fun fuzz_decodeChunkAck_never_crashes() {
        repeat(iterations) {
            try {
                WireCodec.decodeChunkAck(randomBytes())
            } catch (_: IllegalArgumentException) { }
        }
        repeat(iterations) {
            try {
                WireCodec.decodeChunkAck(randomBytesWithType(WireCodec.TYPE_CHUNK_ACK))
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test
    fun fuzz_decodeRoutedMessage_never_crashes() {
        repeat(iterations) {
            try {
                WireCodec.decodeRoutedMessage(randomBytes())
            } catch (_: IllegalArgumentException) { }
        }
        // Targeted: correct type, random visitedCount can cause OOB
        repeat(iterations) {
            try {
                WireCodec.decodeRoutedMessage(randomBytesWithType(WireCodec.TYPE_ROUTED_MESSAGE))
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test
    fun fuzz_decodeBroadcast_never_crashes() {
        repeat(iterations) {
            try {
                WireCodec.decodeBroadcast(randomBytes())
            } catch (_: IllegalArgumentException) { }
        }
        // Targeted: correct type, random sigLen can cause OOB
        repeat(iterations) {
            try {
                WireCodec.decodeBroadcast(randomBytesWithType(WireCodec.TYPE_BROADCAST))
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test
    fun fuzz_decodeDeliveryAck_never_crashes() {
        repeat(iterations) {
            try {
                WireCodec.decodeDeliveryAck(randomBytes())
            } catch (_: IllegalArgumentException) { }
        }
        repeat(iterations) {
            try {
                WireCodec.decodeDeliveryAck(randomBytesWithType(WireCodec.TYPE_DELIVERY_ACK))
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test
    fun fuzz_decodeResumeRequest_never_crashes() {
        repeat(iterations) {
            try {
                WireCodec.decodeResumeRequest(randomBytes())
            } catch (_: IllegalArgumentException) { }
        }
        repeat(iterations) {
            try {
                WireCodec.decodeResumeRequest(randomBytesWithType(WireCodec.TYPE_RESUME_REQUEST, maxSize = 30))
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test
    fun fuzz_decodeKeepalive_never_crashes() {
        repeat(iterations) {
            try {
                WireCodec.decodeKeepalive(randomBytes())
            } catch (_: IllegalArgumentException) { }
        }
        repeat(iterations) {
            try {
                WireCodec.decodeKeepalive(randomBytesWithType(WireCodec.TYPE_KEEPALIVE, maxSize = 20))
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test
    fun fuzz_decodeNack_never_crashes() {
        repeat(iterations) {
            try {
                WireCodec.decodeNack(randomBytes())
            } catch (_: IllegalArgumentException) { }
        }
        repeat(iterations) {
            try {
                WireCodec.decodeNack(randomBytesWithType(WireCodec.TYPE_NACK, maxSize = 24))
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test
    fun fuzz_decodeRouteRequest_never_crashes() {
        repeat(iterations) {
            try {
                WireCodec.decodeRouteRequest(randomBytes())
            } catch (_: IllegalArgumentException) { }
        }
        repeat(iterations) {
            try {
                WireCodec.decodeRouteRequest(randomBytesWithType(WireCodec.TYPE_ROUTE_REQUEST, maxSize = 30))
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test
    fun fuzz_decodeRouteReply_never_crashes() {
        repeat(iterations) {
            try {
                WireCodec.decodeRouteReply(randomBytes())
            } catch (_: IllegalArgumentException) { }
        }
        repeat(iterations) {
            try {
                WireCodec.decodeRouteReply(randomBytesWithType(WireCodec.TYPE_ROUTE_REPLY, maxSize = 28))
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test
    fun fuzz_advertisementDecode_never_crashes() {
        repeat(iterations) {
            try {
                AdvertisementCodec.decode(randomBytes())
            } catch (_: IllegalArgumentException) { }
        }
    }

    // --- edge-case battery (empty, single-byte, exact boundary) ---------

    @Test
    fun edge_emptyAndSingleByte_never_crashes() {
        val decoders: List<(ByteArray) -> Any> = listOf(
            WireCodec::decodeHandshake,
            WireCodec::decodeChunk,
            WireCodec::decodeChunkAck,
            WireCodec::decodeRoutedMessage,
            WireCodec::decodeBroadcast,
            WireCodec::decodeDeliveryAck,
            WireCodec::decodeResumeRequest,
            WireCodec::decodeKeepalive,
            WireCodec::decodeNack,
            WireCodec::decodeRouteRequest,
            WireCodec::decodeRouteReply,
            AdvertisementCodec::decode,
        )
        for (decoder in decoders) {
            for (input in listOf(ByteArray(0), byteArrayOf(0), byteArrayOf(-1))) {
                try {
                    decoder(input)
                } catch (_: IllegalArgumentException) { }
            }
        }
    }

    // --- type-dispatch fuzz test ----------------------------------------

    @Test
    fun fuzz_randomTypeDispatch_never_crashes() {
        repeat(iterations) {
            val data = randomBytes()
            if (data.isEmpty()) return@repeat
            try {
                when (data[0]) {
                    WireCodec.TYPE_BROADCAST      -> WireCodec.decodeBroadcast(data)
                    WireCodec.TYPE_HANDSHAKE       -> WireCodec.decodeHandshake(data)
                    WireCodec.TYPE_CHUNK           -> WireCodec.decodeChunk(data)
                    WireCodec.TYPE_CHUNK_ACK       -> WireCodec.decodeChunkAck(data)
                    WireCodec.TYPE_ROUTED_MESSAGE  -> WireCodec.decodeRoutedMessage(data)
                    WireCodec.TYPE_DELIVERY_ACK    -> WireCodec.decodeDeliveryAck(data)
                    WireCodec.TYPE_RESUME_REQUEST  -> WireCodec.decodeResumeRequest(data)
                    WireCodec.TYPE_KEEPALIVE       -> WireCodec.decodeKeepalive(data)
                    WireCodec.TYPE_NACK            -> WireCodec.decodeNack(data)
                    WireCodec.TYPE_ROUTE_REQUEST   -> WireCodec.decodeRouteRequest(data)
                    WireCodec.TYPE_ROUTE_REPLY     -> WireCodec.decodeRouteReply(data)
                    else -> { /* unknown type — nothing to dispatch */ }
                }
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test
    fun fuzz_decodeHello_never_crashes() {
        repeat(iterations) {
            try {
                WireCodec.decodeHello(randomBytes())
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test
    fun fuzz_decodeUpdate_never_crashes() {
        repeat(iterations) {
            try {
                WireCodec.decodeUpdate(randomBytes())
            } catch (_: IllegalArgumentException) { }
        }
    }
}
