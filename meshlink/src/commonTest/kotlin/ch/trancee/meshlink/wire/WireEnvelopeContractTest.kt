package ch.trancee.meshlink.wire

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WireEnvelopeContractTest {
    @Test
    fun `message frame round-trips through a FlatBuffers-compatible envelope`() {
        // Arrange
        val frame =
            WireFrame.Message(
                messageId = "message-001",
                originPeerId = PeerId("origin-peer-001"),
                destinationPeerId = PeerId("destination-peer-001"),
                priority = DeliveryPriority.NORMAL,
                ttlMillis = 30_000,
                encryptedPayload = byteArrayOf(1, 2, 3, 4, 5),
            )

        // Act
        val encoded = WireCodec.encode(frame)
        val envelope = WireEnvelope.decode(encoded)
        val decoded = WireCodec.decode(encoded)

        // Assert
        assertTrue(
            encoded.size > frame.encryptedPayload.size,
            "The envelope should add FlatBuffer table metadata",
        )
        assertEquals(WireCodec.CURRENT_WIRE_VERSION, envelope.version)
        assertEquals(WireEnvelopeType.MESSAGE, envelope.type)
        assertFrameEquals(frame, decoded)
    }

    @Test
    fun `routing control frames round-trip through the shared wire codec`() {
        // Arrange
        val frames =
            listOf<WireFrame>(
                WireFrame.Hello(peerId = PeerId("hello-peer-001"), helloIntervalMillis = 2_000),
                WireFrame.Ihu(peerId = PeerId("ihu-peer-001"), receiveCost = 96),
                WireFrame.RouteUpdate(
                    destinationPeerId = PeerId("destination-peer-002"),
                    nextHopPeerId = PeerId("next-hop-peer-002"),
                    metric = 3,
                    seqNo = 4_294_967_300L,
                    feasibilityMetric = 2,
                    destinationEd25519PublicKey = byteArrayOf(1, 2, 3, 4),
                    destinationX25519PublicKey = byteArrayOf(5, 6, 7, 8),
                ),
                WireFrame.RouteRetraction(
                    destinationPeerId = PeerId("destination-peer-003"),
                    seqNo = 77,
                ),
                WireFrame.SeqNoRequest(
                    destinationPeerId = PeerId("destination-peer-004"),
                    requestedSeqNo = 101,
                ),
                WireFrame.RouteDigest(
                    peerId = PeerId("digest-peer-001"),
                    digest = byteArrayOf(9, 8, 7, 6),
                ),
            )

        // Act
        val decodedFrames = frames.map { frame -> WireCodec.decode(WireCodec.encode(frame)) }

        // Assert
        frames.zip(decodedFrames).forEach { (expected, actual) ->
            assertFrameEquals(expected, actual)
        }
    }

    @Test
    fun `transfer frames round-trip through the shared wire codec`() {
        // Arrange
        val frames =
            listOf<WireFrame>(
                WireFrame.TransferStart(
                    transferId = "transfer-001",
                    messageId = "message-001",
                    originPeerId = PeerId("origin-peer-001"),
                    destinationPeerId = PeerId("destination-peer-001"),
                    totalBytes = 65_520,
                    totalChunks = 64,
                    maxChunkPayloadBytes = 1_024,
                ),
                WireFrame.TransferChunk(
                    transferId = "transfer-001",
                    chunkIndex = 7,
                    payload = byteArrayOf(3, 1, 4, 1, 5, 9),
                ),
                WireFrame.TransferAck(
                    transferId = "transfer-001",
                    highestContiguousAck = 5,
                    selectiveRanges = byteArrayOf(0, 5, 9, 12),
                ),
                WireFrame.TransferComplete(transferId = "transfer-001"),
                WireFrame.TransferAbort(transferId = "transfer-002", reasonCode = 42),
            )

        // Act
        val decodedFrames = frames.map { frame -> WireCodec.decode(WireCodec.encode(frame)) }

        // Assert
        frames.zip(decodedFrames).forEach { (expected, actual) ->
            assertFrameEquals(expected, actual)
        }
    }

    @Test
    fun `decoder ignores unknown future envelope fields`() {
        // Arrange
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        val encoded =
            FlatBufferTableBuilder(fieldCount = 4)
                .addByte(fieldIndex = 0, value = WireCodec.CURRENT_WIRE_VERSION.toByte())
                .addByte(fieldIndex = 1, value = WireEnvelopeType.MESSAGE.code)
                .addByteVector(fieldIndex = 2, value = payload)
                .addInt(fieldIndex = 3, value = 99)
                .finish()

        // Act
        val decoded = WireEnvelope.decode(encoded)

        // Assert
        assertEquals(WireCodec.CURRENT_WIRE_VERSION, decoded.version)
        assertEquals(WireEnvelopeType.MESSAGE, decoded.type)
        assertContentEquals(payload, decoded.payload)
    }

    @Test
    fun `deployed wire compatibility fixtures still decode and re-encode unchanged`() {
        // Arrange
        val fixtures = loadWireCompatibilityFixtures() ?: return

        // Act / Assert
        fixtures.forEach { fixture ->
            val envelope = WireEnvelope.decode(fixture.encodedBytes)
            val decoded = WireCodec.decode(fixture.encodedBytes)
            val reencoded = WireCodec.encode(decoded)

            assertEquals(
                expected = WireCodec.CURRENT_WIRE_VERSION,
                actual = envelope.version,
                message = "Expected ${fixture.fileName} to remain on the current wire version",
            )
            assertEquals(
                expected = fixture.expectedType,
                actual = envelope.type,
                message = "Expected ${fixture.fileName} to decode as ${fixture.expectedType}",
            )
            assertFrameEquals(expected = fixture.expectedFrame, actual = decoded)
            assertContentEquals(
                expected = fixture.encodedBytes,
                actual = reencoded,
                message = "Expected ${fixture.fileName} to remain byte-for-byte stable",
            )
        }
    }

    @Test
    fun `decoder rejects truncated wire envelopes`() {
        // Arrange
        val encoded = WireCodec.encode(WireFrame.TransferComplete(transferId = "transfer-003"))
        val truncated = encoded.copyOf(encoded.size - 1)

        // Act
        val failure = assertFailsWith<IllegalStateException> { WireCodec.decode(truncated) }

        // Assert
        assertTrue(
            failure.message?.contains("FlatBuffer", ignoreCase = true) == true,
            "Malformed envelopes should fail with a FlatBuffer-related error",
        )
    }

    private fun loadWireCompatibilityFixtures(): List<WireCompatibilityFixture>? {
        val indexText =
            WireCompatibilitySupport.resourceTextOrNull(WIRE_COMPAT_INDEX_RESOURCE) ?: return null
        val fileNames =
            indexText.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        return fileNames.map { fileName ->
            val hex =
                WireCompatibilitySupport.resourceTextOrNull(
                        "$WIRE_COMPAT_RESOURCE_PREFIX/$fileName"
                    )
                    ?.trim() ?: error("Missing wire compatibility fixture $fileName")
            wireCompatibilityFixture(fileName = fileName, encodedBytes = hex.hexToByteArray())
        }
    }

    private fun wireCompatibilityFixture(
        fileName: String,
        encodedBytes: ByteArray,
    ): WireCompatibilityFixture {
        return when (fileName) {
            "v1_message.hex" ->
                WireCompatibilityFixture(
                    fileName = fileName,
                    expectedType = WireEnvelopeType.MESSAGE,
                    expectedFrame =
                        WireFrame.Message(
                            messageId = "fixture-message-001",
                            originPeerId = PeerId("fixture-origin-001"),
                            destinationPeerId = PeerId("fixture-destination-001"),
                            priority = DeliveryPriority.NORMAL,
                            ttlMillis = 45_000,
                            encryptedPayload = byteArrayOf(0x01, 0x02, 0x03, 0x04),
                        ),
                    encodedBytes = encodedBytes,
                )
            "v1_route_update.hex" ->
                WireCompatibilityFixture(
                    fileName = fileName,
                    expectedType = WireEnvelopeType.ROUTE_UPDATE,
                    expectedFrame =
                        WireFrame.RouteUpdate(
                            destinationPeerId = PeerId("fixture-destination-002"),
                            nextHopPeerId = PeerId("fixture-next-hop-002"),
                            metric = 2,
                            seqNo = 4_294_967_300L,
                            feasibilityMetric = 1,
                            destinationEd25519PublicKey = byteArrayOf(0x11, 0x12, 0x13, 0x14),
                            destinationX25519PublicKey = byteArrayOf(0x21, 0x22, 0x23, 0x24),
                        ),
                    encodedBytes = encodedBytes,
                )
            "v1_transfer_ack.hex" ->
                WireCompatibilityFixture(
                    fileName = fileName,
                    expectedType = WireEnvelopeType.TRANSFER_ACK,
                    expectedFrame =
                        WireFrame.TransferAck(
                            transferId = "fixture-transfer-003",
                            highestContiguousAck = 7,
                            selectiveRanges = byteArrayOf(0x00, 0x03, 0x05, 0x07),
                        ),
                    encodedBytes = encodedBytes,
                )
            else -> error("Unsupported wire compatibility fixture $fileName")
        }
    }

    private fun String.hexToByteArray(): ByteArray {
        if (length % 2 != 0) {
            error("Hex fixture content must contain an even number of characters")
        }
        return ByteArray(length / 2) { index ->
            val high = hexDigitValue(this[index * 2])
            val low = hexDigitValue(this[index * 2 + 1])
            ((high shl 4) or low).toByte()
        }
    }

    private fun hexDigitValue(char: Char): Int {
        return when (char) {
            in '0'..'9' -> char.code - '0'.code
            in 'a'..'f' -> char.code - 'a'.code + 10
            in 'A'..'F' -> char.code - 'A'.code + 10
            else -> error("Invalid hex digit '$char' in wire compatibility fixture")
        }
    }

    private fun assertFrameEquals(expected: WireFrame, actual: WireFrame): Unit {
        when (expected) {
            is WireFrame.Hello -> {
                val decoded = actual as WireFrame.Hello
                assertEquals(expected.peerId.value, decoded.peerId.value)
                assertEquals(expected.helloIntervalMillis, decoded.helloIntervalMillis)
            }

            is WireFrame.Ihu -> {
                val decoded = actual as WireFrame.Ihu
                assertEquals(expected.peerId.value, decoded.peerId.value)
                assertEquals(expected.receiveCost, decoded.receiveCost)
            }

            is WireFrame.RouteUpdate -> {
                val decoded = actual as WireFrame.RouteUpdate
                assertEquals(expected.destinationPeerId.value, decoded.destinationPeerId.value)
                assertEquals(expected.nextHopPeerId.value, decoded.nextHopPeerId.value)
                assertEquals(expected.metric, decoded.metric)
                assertEquals(expected.seqNo, decoded.seqNo)
                assertEquals(expected.feasibilityMetric, decoded.feasibilityMetric)
                assertContentEquals(
                    expected.destinationEd25519PublicKey,
                    decoded.destinationEd25519PublicKey,
                )
                assertContentEquals(
                    expected.destinationX25519PublicKey,
                    decoded.destinationX25519PublicKey,
                )
            }

            is WireFrame.RouteRetraction -> {
                val decoded = actual as WireFrame.RouteRetraction
                assertEquals(expected.destinationPeerId.value, decoded.destinationPeerId.value)
                assertEquals(expected.seqNo, decoded.seqNo)
            }

            is WireFrame.SeqNoRequest -> {
                val decoded = actual as WireFrame.SeqNoRequest
                assertEquals(expected.destinationPeerId.value, decoded.destinationPeerId.value)
                assertEquals(expected.requestedSeqNo, decoded.requestedSeqNo)
            }

            is WireFrame.RouteDigest -> {
                val decoded = actual as WireFrame.RouteDigest
                assertEquals(expected.peerId.value, decoded.peerId.value)
                assertContentEquals(expected.digest, decoded.digest)
            }

            is WireFrame.Message -> {
                val decoded = actual as WireFrame.Message
                assertEquals(expected.messageId, decoded.messageId)
                assertEquals(expected.originPeerId.value, decoded.originPeerId.value)
                assertEquals(expected.destinationPeerId.value, decoded.destinationPeerId.value)
                assertEquals(expected.priority, decoded.priority)
                assertEquals(expected.ttlMillis, decoded.ttlMillis)
                assertContentEquals(expected.encryptedPayload, decoded.encryptedPayload)
            }

            is WireFrame.TransferStart -> {
                val decoded = actual as WireFrame.TransferStart
                assertEquals(expected.transferId, decoded.transferId)
                assertEquals(expected.messageId, decoded.messageId)
                assertEquals(expected.originPeerId.value, decoded.originPeerId.value)
                assertEquals(expected.destinationPeerId.value, decoded.destinationPeerId.value)
                assertEquals(expected.totalBytes, decoded.totalBytes)
                assertEquals(expected.totalChunks, decoded.totalChunks)
                assertEquals(expected.maxChunkPayloadBytes, decoded.maxChunkPayloadBytes)
            }

            is WireFrame.TransferChunk -> {
                val decoded = actual as WireFrame.TransferChunk
                assertEquals(expected.transferId, decoded.transferId)
                assertEquals(expected.chunkIndex, decoded.chunkIndex)
                assertContentEquals(expected.payload, decoded.payload)
            }

            is WireFrame.TransferAck -> {
                val decoded = actual as WireFrame.TransferAck
                assertEquals(expected.transferId, decoded.transferId)
                assertEquals(expected.highestContiguousAck, decoded.highestContiguousAck)
                assertContentEquals(expected.selectiveRanges, decoded.selectiveRanges)
            }

            is WireFrame.TransferComplete -> {
                val decoded = actual as WireFrame.TransferComplete
                assertEquals(expected.transferId, decoded.transferId)
            }

            is WireFrame.TransferAbort -> {
                val decoded = actual as WireFrame.TransferAbort
                assertEquals(expected.transferId, decoded.transferId)
                assertEquals(expected.reasonCode, decoded.reasonCode)
            }
        }
    }

    private companion object {
        private const val WIRE_COMPAT_RESOURCE_PREFIX = "wire-compat"
        private const val WIRE_COMPAT_INDEX_RESOURCE = "$WIRE_COMPAT_RESOURCE_PREFIX/index.txt"
    }
}

private class WireCompatibilityFixture
internal constructor(
    internal val fileName: String,
    internal val expectedType: WireEnvelopeType,
    internal val expectedFrame: WireFrame,
    encodedBytes: ByteArray,
) {
    internal val encodedBytes: ByteArray = encodedBytes.copyOf()
}
