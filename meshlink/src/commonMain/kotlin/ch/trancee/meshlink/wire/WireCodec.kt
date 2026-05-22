package ch.trancee.meshlink.wire

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId

internal sealed class WireFrame {
    internal class Hello
    internal constructor(public val peerId: PeerId, public val helloIntervalMillis: Int) :
        WireFrame()

    internal class Ihu
    internal constructor(public val peerId: PeerId, public val receiveCost: Int) : WireFrame()

    internal class RouteUpdate
    internal constructor(
        public val destinationPeerId: PeerId,
        public val nextHopPeerId: PeerId,
        public val metric: Int,
        public val seqNo: Long,
        public val feasibilityMetric: Int,
        destinationEd25519PublicKey: ByteArray,
        destinationX25519PublicKey: ByteArray,
    ) : WireFrame() {
        public val destinationEd25519PublicKey: ByteArray = destinationEd25519PublicKey.copyOf()
        public val destinationX25519PublicKey: ByteArray = destinationX25519PublicKey.copyOf()
    }

    internal class RouteRetraction
    internal constructor(public val destinationPeerId: PeerId, public val seqNo: Long) : WireFrame()

    internal class SeqNoRequest
    internal constructor(public val destinationPeerId: PeerId, public val requestedSeqNo: Long) :
        WireFrame()

    internal class RouteDigest internal constructor(public val peerId: PeerId, digest: ByteArray) :
        WireFrame() {
        public val digest: ByteArray = digest.copyOf()
    }

    internal class Message
    internal constructor(
        public val messageId: String,
        public val originPeerId: PeerId,
        public val destinationPeerId: PeerId,
        public val priority: DeliveryPriority,
        public val ttlMillis: Int,
        encryptedPayload: ByteArray,
    ) : WireFrame() {
        public val encryptedPayload: ByteArray = encryptedPayload.copyOf()
    }

    internal class TransferStart
    internal constructor(
        public val transferId: String,
        public val messageId: String,
        public val originPeerId: PeerId,
        public val destinationPeerId: PeerId,
        public val totalBytes: Int,
        public val totalChunks: Int,
        public val maxChunkPayloadBytes: Int,
    ) : WireFrame()

    internal class TransferChunk
    internal constructor(
        public val transferId: String,
        public val chunkIndex: Int,
        payload: ByteArray,
    ) : WireFrame() {
        public val payload: ByteArray = payload.copyOf()
    }

    internal class TransferAck
    internal constructor(
        public val transferId: String,
        public val highestContiguousAck: Int,
        selectiveRanges: ByteArray,
    ) : WireFrame() {
        public val selectiveRanges: ByteArray = selectiveRanges.copyOf()
    }

    internal class TransferComplete internal constructor(public val transferId: String) :
        WireFrame()

    internal class TransferAbort
    internal constructor(public val transferId: String, public val reasonCode: Int) : WireFrame()
}

internal object WireCodec {
    internal const val CURRENT_WIRE_VERSION: UByte = 1u

    internal fun encode(frame: WireFrame): ByteArray {
        return encodeEnvelope(frame).encode()
    }

    internal fun encodeEnvelope(frame: WireFrame): WireEnvelope {
        val type = envelopeType(frame)
        val payload = encodePayload(frame)
        return WireEnvelope(version = CURRENT_WIRE_VERSION, type = type, payload = payload)
    }

    internal fun decode(bytes: ByteArray): WireFrame {
        val envelope = WireEnvelope.decode(bytes)
        return decodePayload(envelope.type, envelope.payload)
    }

    internal fun decodePayload(type: WireEnvelopeType, payload: ByteArray): WireFrame {
        val table = FlatBufferTable.fromRoot(payload)
        return when (type) {
            WireEnvelopeType.HELLO ->
                WireFrame.Hello(
                    peerId =
                        PeerId(requireString(table, HELLO_PEER_ID_FIELD_INDEX, "HELLO.peerId")),
                    helloIntervalMillis = table.readInt(HELLO_INTERVAL_FIELD_INDEX),
                )

            WireEnvelopeType.IHU ->
                WireFrame.Ihu(
                    peerId = PeerId(requireString(table, IHU_PEER_ID_FIELD_INDEX, "IHU.peerId")),
                    receiveCost = table.readInt(IHU_RECEIVE_COST_FIELD_INDEX),
                )

            WireEnvelopeType.ROUTE_UPDATE ->
                WireFrame.RouteUpdate(
                    destinationPeerId =
                        PeerId(
                            requireString(
                                table,
                                ROUTE_UPDATE_DESTINATION_FIELD_INDEX,
                                "ROUTE_UPDATE.destinationPeerId",
                            )
                        ),
                    nextHopPeerId =
                        PeerId(
                            requireString(
                                table,
                                ROUTE_UPDATE_NEXT_HOP_FIELD_INDEX,
                                "ROUTE_UPDATE.nextHopPeerId",
                            )
                        ),
                    metric = table.readInt(ROUTE_UPDATE_METRIC_FIELD_INDEX),
                    seqNo = table.readLong(ROUTE_UPDATE_SEQ_NO_FIELD_INDEX),
                    feasibilityMetric = table.readInt(ROUTE_UPDATE_FEASIBILITY_FIELD_INDEX),
                    destinationEd25519PublicKey =
                        requireByteVector(
                            table,
                            ROUTE_UPDATE_ED25519_FIELD_INDEX,
                            "ROUTE_UPDATE.destinationEd25519PublicKey",
                        ),
                    destinationX25519PublicKey =
                        requireByteVector(
                            table,
                            ROUTE_UPDATE_X25519_FIELD_INDEX,
                            "ROUTE_UPDATE.destinationX25519PublicKey",
                        ),
                )

            WireEnvelopeType.ROUTE_RETRACTION ->
                WireFrame.RouteRetraction(
                    destinationPeerId =
                        PeerId(
                            requireString(
                                table,
                                ROUTE_RETRACTION_DESTINATION_FIELD_INDEX,
                                "ROUTE_RETRACTION.destinationPeerId",
                            )
                        ),
                    seqNo = table.readLong(ROUTE_RETRACTION_SEQ_NO_FIELD_INDEX),
                )

            WireEnvelopeType.SEQNO_REQUEST ->
                WireFrame.SeqNoRequest(
                    destinationPeerId =
                        PeerId(
                            requireString(
                                table,
                                SEQNO_REQUEST_DESTINATION_FIELD_INDEX,
                                "SEQNO_REQUEST.destinationPeerId",
                            )
                        ),
                    requestedSeqNo = table.readLong(SEQNO_REQUEST_SEQ_NO_FIELD_INDEX),
                )

            WireEnvelopeType.ROUTE_DIGEST ->
                WireFrame.RouteDigest(
                    peerId =
                        PeerId(
                            requireString(
                                table,
                                ROUTE_DIGEST_PEER_ID_FIELD_INDEX,
                                "ROUTE_DIGEST.peerId",
                            )
                        ),
                    digest =
                        requireByteVector(
                            table,
                            ROUTE_DIGEST_DIGEST_FIELD_INDEX,
                            "ROUTE_DIGEST.digest",
                        ),
                )

            WireEnvelopeType.MESSAGE ->
                WireFrame.Message(
                    messageId = requireString(table, MESSAGE_ID_FIELD_INDEX, "MESSAGE.messageId"),
                    originPeerId =
                        PeerId(
                            requireString(table, MESSAGE_ORIGIN_FIELD_INDEX, "MESSAGE.originPeerId")
                        ),
                    destinationPeerId =
                        PeerId(
                            requireString(
                                table,
                                MESSAGE_DESTINATION_FIELD_INDEX,
                                "MESSAGE.destinationPeerId",
                            )
                        ),
                    priority = priorityFromCode(table.readByte(MESSAGE_PRIORITY_FIELD_INDEX)),
                    ttlMillis = table.readInt(MESSAGE_TTL_FIELD_INDEX),
                    encryptedPayload =
                        requireByteVector(
                            table,
                            MESSAGE_PAYLOAD_FIELD_INDEX,
                            "MESSAGE.encryptedPayload",
                        ),
                )

            WireEnvelopeType.TRANSFER_START ->
                WireFrame.TransferStart(
                    transferId =
                        requireString(
                            table,
                            TRANSFER_START_ID_FIELD_INDEX,
                            "TRANSFER_START.transferId",
                        ),
                    messageId =
                        requireString(
                            table,
                            TRANSFER_START_MESSAGE_ID_FIELD_INDEX,
                            "TRANSFER_START.messageId",
                        ),
                    originPeerId =
                        PeerId(
                            requireString(
                                table,
                                TRANSFER_START_ORIGIN_FIELD_INDEX,
                                "TRANSFER_START.originPeerId",
                            )
                        ),
                    destinationPeerId =
                        PeerId(
                            requireString(
                                table,
                                TRANSFER_START_DESTINATION_FIELD_INDEX,
                                "TRANSFER_START.destinationPeerId",
                            )
                        ),
                    totalBytes = table.readInt(TRANSFER_START_TOTAL_BYTES_FIELD_INDEX),
                    totalChunks = table.readInt(TRANSFER_START_TOTAL_CHUNKS_FIELD_INDEX),
                    maxChunkPayloadBytes = table.readInt(TRANSFER_START_CHUNK_BYTES_FIELD_INDEX),
                )

            WireEnvelopeType.TRANSFER_CHUNK ->
                WireFrame.TransferChunk(
                    transferId =
                        requireString(
                            table,
                            TRANSFER_CHUNK_ID_FIELD_INDEX,
                            "TRANSFER_CHUNK.transferId",
                        ),
                    chunkIndex = table.readInt(TRANSFER_CHUNK_INDEX_FIELD_INDEX),
                    payload =
                        requireByteVector(
                            table,
                            TRANSFER_CHUNK_PAYLOAD_FIELD_INDEX,
                            "TRANSFER_CHUNK.payload",
                        ),
                )

            WireEnvelopeType.TRANSFER_ACK ->
                WireFrame.TransferAck(
                    transferId =
                        requireString(
                            table,
                            TRANSFER_ACK_ID_FIELD_INDEX,
                            "TRANSFER_ACK.transferId",
                        ),
                    highestContiguousAck =
                        table.readInt(TRANSFER_ACK_HIGHEST_CONTIGUOUS_FIELD_INDEX),
                    selectiveRanges =
                        requireByteVector(
                            table,
                            TRANSFER_ACK_SELECTIVE_RANGES_FIELD_INDEX,
                            "TRANSFER_ACK.selectiveRanges",
                        ),
                )

            WireEnvelopeType.TRANSFER_COMPLETE ->
                WireFrame.TransferComplete(
                    transferId =
                        requireString(
                            table,
                            TRANSFER_COMPLETE_ID_FIELD_INDEX,
                            "TRANSFER_COMPLETE.transferId",
                        )
                )

            WireEnvelopeType.TRANSFER_ABORT ->
                WireFrame.TransferAbort(
                    transferId =
                        requireString(
                            table,
                            TRANSFER_ABORT_ID_FIELD_INDEX,
                            "TRANSFER_ABORT.transferId",
                        ),
                    reasonCode = table.readInt(TRANSFER_ABORT_REASON_FIELD_INDEX),
                )
        }
    }

    private fun encodePayload(frame: WireFrame): ByteArray {
        return when (frame) {
            is WireFrame.Hello ->
                FlatBufferTableBuilder(fieldCount = HELLO_FIELD_COUNT)
                    .addString(HELLO_PEER_ID_FIELD_INDEX, frame.peerId.value)
                    .addInt(HELLO_INTERVAL_FIELD_INDEX, frame.helloIntervalMillis)
                    .finish()

            is WireFrame.Ihu ->
                FlatBufferTableBuilder(fieldCount = IHU_FIELD_COUNT)
                    .addString(IHU_PEER_ID_FIELD_INDEX, frame.peerId.value)
                    .addInt(IHU_RECEIVE_COST_FIELD_INDEX, frame.receiveCost)
                    .finish()

            is WireFrame.RouteUpdate ->
                FlatBufferTableBuilder(fieldCount = ROUTE_UPDATE_FIELD_COUNT)
                    .addString(ROUTE_UPDATE_DESTINATION_FIELD_INDEX, frame.destinationPeerId.value)
                    .addString(ROUTE_UPDATE_NEXT_HOP_FIELD_INDEX, frame.nextHopPeerId.value)
                    .addInt(ROUTE_UPDATE_METRIC_FIELD_INDEX, frame.metric)
                    .addLong(ROUTE_UPDATE_SEQ_NO_FIELD_INDEX, frame.seqNo)
                    .addInt(ROUTE_UPDATE_FEASIBILITY_FIELD_INDEX, frame.feasibilityMetric)
                    .addByteVector(
                        ROUTE_UPDATE_ED25519_FIELD_INDEX,
                        frame.destinationEd25519PublicKey,
                    )
                    .addByteVector(
                        ROUTE_UPDATE_X25519_FIELD_INDEX,
                        frame.destinationX25519PublicKey,
                    )
                    .finish()

            is WireFrame.RouteRetraction ->
                FlatBufferTableBuilder(fieldCount = ROUTE_RETRACTION_FIELD_COUNT)
                    .addString(
                        ROUTE_RETRACTION_DESTINATION_FIELD_INDEX,
                        frame.destinationPeerId.value,
                    )
                    .addLong(ROUTE_RETRACTION_SEQ_NO_FIELD_INDEX, frame.seqNo)
                    .finish()

            is WireFrame.SeqNoRequest ->
                FlatBufferTableBuilder(fieldCount = SEQNO_REQUEST_FIELD_COUNT)
                    .addString(SEQNO_REQUEST_DESTINATION_FIELD_INDEX, frame.destinationPeerId.value)
                    .addLong(SEQNO_REQUEST_SEQ_NO_FIELD_INDEX, frame.requestedSeqNo)
                    .finish()

            is WireFrame.RouteDigest ->
                FlatBufferTableBuilder(fieldCount = ROUTE_DIGEST_FIELD_COUNT)
                    .addString(ROUTE_DIGEST_PEER_ID_FIELD_INDEX, frame.peerId.value)
                    .addByteVector(ROUTE_DIGEST_DIGEST_FIELD_INDEX, frame.digest)
                    .finish()

            is WireFrame.Message ->
                FlatBufferTableBuilder(fieldCount = MESSAGE_FIELD_COUNT)
                    .addString(MESSAGE_ID_FIELD_INDEX, frame.messageId)
                    .addString(MESSAGE_ORIGIN_FIELD_INDEX, frame.originPeerId.value)
                    .addString(MESSAGE_DESTINATION_FIELD_INDEX, frame.destinationPeerId.value)
                    .addByte(MESSAGE_PRIORITY_FIELD_INDEX, priorityCode(frame.priority))
                    .addInt(MESSAGE_TTL_FIELD_INDEX, frame.ttlMillis)
                    .addByteVector(MESSAGE_PAYLOAD_FIELD_INDEX, frame.encryptedPayload)
                    .finish()

            is WireFrame.TransferStart ->
                FlatBufferTableBuilder(fieldCount = TRANSFER_START_FIELD_COUNT)
                    .addString(TRANSFER_START_ID_FIELD_INDEX, frame.transferId)
                    .addString(TRANSFER_START_MESSAGE_ID_FIELD_INDEX, frame.messageId)
                    .addString(TRANSFER_START_ORIGIN_FIELD_INDEX, frame.originPeerId.value)
                    .addString(
                        TRANSFER_START_DESTINATION_FIELD_INDEX,
                        frame.destinationPeerId.value,
                    )
                    .addInt(TRANSFER_START_TOTAL_BYTES_FIELD_INDEX, frame.totalBytes)
                    .addInt(TRANSFER_START_TOTAL_CHUNKS_FIELD_INDEX, frame.totalChunks)
                    .addInt(TRANSFER_START_CHUNK_BYTES_FIELD_INDEX, frame.maxChunkPayloadBytes)
                    .finish()

            is WireFrame.TransferChunk ->
                FlatBufferTableBuilder(fieldCount = TRANSFER_CHUNK_FIELD_COUNT)
                    .addString(TRANSFER_CHUNK_ID_FIELD_INDEX, frame.transferId)
                    .addInt(TRANSFER_CHUNK_INDEX_FIELD_INDEX, frame.chunkIndex)
                    .addByteVector(TRANSFER_CHUNK_PAYLOAD_FIELD_INDEX, frame.payload)
                    .finish()

            is WireFrame.TransferAck ->
                FlatBufferTableBuilder(fieldCount = TRANSFER_ACK_FIELD_COUNT)
                    .addString(TRANSFER_ACK_ID_FIELD_INDEX, frame.transferId)
                    .addInt(TRANSFER_ACK_HIGHEST_CONTIGUOUS_FIELD_INDEX, frame.highestContiguousAck)
                    .addByteVector(TRANSFER_ACK_SELECTIVE_RANGES_FIELD_INDEX, frame.selectiveRanges)
                    .finish()

            is WireFrame.TransferComplete ->
                FlatBufferTableBuilder(fieldCount = TRANSFER_COMPLETE_FIELD_COUNT)
                    .addString(TRANSFER_COMPLETE_ID_FIELD_INDEX, frame.transferId)
                    .finish()

            is WireFrame.TransferAbort ->
                FlatBufferTableBuilder(fieldCount = TRANSFER_ABORT_FIELD_COUNT)
                    .addString(TRANSFER_ABORT_ID_FIELD_INDEX, frame.transferId)
                    .addInt(TRANSFER_ABORT_REASON_FIELD_INDEX, frame.reasonCode)
                    .finish()
        }
    }

    private fun envelopeType(frame: WireFrame): WireEnvelopeType {
        return when (frame) {
            is WireFrame.Hello -> WireEnvelopeType.HELLO
            is WireFrame.Ihu -> WireEnvelopeType.IHU
            is WireFrame.RouteUpdate -> WireEnvelopeType.ROUTE_UPDATE
            is WireFrame.RouteRetraction -> WireEnvelopeType.ROUTE_RETRACTION
            is WireFrame.SeqNoRequest -> WireEnvelopeType.SEQNO_REQUEST
            is WireFrame.RouteDigest -> WireEnvelopeType.ROUTE_DIGEST
            is WireFrame.Message -> WireEnvelopeType.MESSAGE
            is WireFrame.TransferStart -> WireEnvelopeType.TRANSFER_START
            is WireFrame.TransferChunk -> WireEnvelopeType.TRANSFER_CHUNK
            is WireFrame.TransferAck -> WireEnvelopeType.TRANSFER_ACK
            is WireFrame.TransferComplete -> WireEnvelopeType.TRANSFER_COMPLETE
            is WireFrame.TransferAbort -> WireEnvelopeType.TRANSFER_ABORT
        }
    }

    private fun priorityCode(priority: DeliveryPriority): Byte {
        return when (priority) {
            DeliveryPriority.HIGH -> PRIORITY_CODE_HIGH
            DeliveryPriority.NORMAL -> PRIORITY_CODE_NORMAL
            DeliveryPriority.LOW -> PRIORITY_CODE_LOW
        }
    }

    private fun priorityFromCode(code: Byte): DeliveryPriority {
        return when (code.toInt()) {
            PRIORITY_CODE_HIGH.toInt() -> DeliveryPriority.HIGH
            PRIORITY_CODE_NORMAL.toInt() -> DeliveryPriority.NORMAL
            PRIORITY_CODE_LOW.toInt() -> DeliveryPriority.LOW
            else -> error("Unknown delivery priority code ${code.toInt() and PRIORITY_CODE_MASK}")
        }
    }

    private fun requireString(table: FlatBufferTable, fieldIndex: Int, fieldName: String): String {
        return table.readString(fieldIndex) ?: error("$fieldName is missing")
    }

    private fun requireByteVector(
        table: FlatBufferTable,
        fieldIndex: Int,
        fieldName: String,
    ): ByteArray {
        return table.readByteVector(fieldIndex) ?: error("$fieldName is missing")
    }

    private const val PRIORITY_CODE_MASK: Int = 0xFF
    private const val PRIORITY_CODE_HIGH: Byte = 1
    private const val PRIORITY_CODE_NORMAL: Byte = 2
    private const val PRIORITY_CODE_LOW: Byte = 3

    private const val HELLO_FIELD_COUNT: Int = 2
    private const val HELLO_PEER_ID_FIELD_INDEX: Int = 0
    private const val HELLO_INTERVAL_FIELD_INDEX: Int = 1

    private const val IHU_FIELD_COUNT: Int = 2
    private const val IHU_PEER_ID_FIELD_INDEX: Int = 0
    private const val IHU_RECEIVE_COST_FIELD_INDEX: Int = 1

    private const val ROUTE_UPDATE_FIELD_COUNT: Int = 7
    private const val ROUTE_UPDATE_DESTINATION_FIELD_INDEX: Int = 0
    private const val ROUTE_UPDATE_NEXT_HOP_FIELD_INDEX: Int = 1
    private const val ROUTE_UPDATE_METRIC_FIELD_INDEX: Int = 2
    private const val ROUTE_UPDATE_SEQ_NO_FIELD_INDEX: Int = 3
    private const val ROUTE_UPDATE_FEASIBILITY_FIELD_INDEX: Int = 4
    private const val ROUTE_UPDATE_ED25519_FIELD_INDEX: Int = 5
    private const val ROUTE_UPDATE_X25519_FIELD_INDEX: Int = 6

    private const val ROUTE_RETRACTION_FIELD_COUNT: Int = 2
    private const val ROUTE_RETRACTION_DESTINATION_FIELD_INDEX: Int = 0
    private const val ROUTE_RETRACTION_SEQ_NO_FIELD_INDEX: Int = 1

    private const val SEQNO_REQUEST_FIELD_COUNT: Int = 2
    private const val SEQNO_REQUEST_DESTINATION_FIELD_INDEX: Int = 0
    private const val SEQNO_REQUEST_SEQ_NO_FIELD_INDEX: Int = 1

    private const val ROUTE_DIGEST_FIELD_COUNT: Int = 2
    private const val ROUTE_DIGEST_PEER_ID_FIELD_INDEX: Int = 0
    private const val ROUTE_DIGEST_DIGEST_FIELD_INDEX: Int = 1

    private const val MESSAGE_FIELD_COUNT: Int = 6
    private const val MESSAGE_ID_FIELD_INDEX: Int = 0
    private const val MESSAGE_ORIGIN_FIELD_INDEX: Int = 1
    private const val MESSAGE_DESTINATION_FIELD_INDEX: Int = 2
    private const val MESSAGE_PRIORITY_FIELD_INDEX: Int = 3
    private const val MESSAGE_TTL_FIELD_INDEX: Int = 4
    private const val MESSAGE_PAYLOAD_FIELD_INDEX: Int = 5

    private const val TRANSFER_START_FIELD_COUNT: Int = 7
    private const val TRANSFER_START_ID_FIELD_INDEX: Int = 0
    private const val TRANSFER_START_MESSAGE_ID_FIELD_INDEX: Int = 1
    private const val TRANSFER_START_ORIGIN_FIELD_INDEX: Int = 2
    private const val TRANSFER_START_DESTINATION_FIELD_INDEX: Int = 3
    private const val TRANSFER_START_TOTAL_BYTES_FIELD_INDEX: Int = 4
    private const val TRANSFER_START_TOTAL_CHUNKS_FIELD_INDEX: Int = 5
    private const val TRANSFER_START_CHUNK_BYTES_FIELD_INDEX: Int = 6

    private const val TRANSFER_CHUNK_FIELD_COUNT: Int = 3
    private const val TRANSFER_CHUNK_ID_FIELD_INDEX: Int = 0
    private const val TRANSFER_CHUNK_INDEX_FIELD_INDEX: Int = 1
    private const val TRANSFER_CHUNK_PAYLOAD_FIELD_INDEX: Int = 2

    private const val TRANSFER_ACK_FIELD_COUNT: Int = 3
    private const val TRANSFER_ACK_ID_FIELD_INDEX: Int = 0
    private const val TRANSFER_ACK_HIGHEST_CONTIGUOUS_FIELD_INDEX: Int = 1
    private const val TRANSFER_ACK_SELECTIVE_RANGES_FIELD_INDEX: Int = 2

    private const val TRANSFER_COMPLETE_FIELD_COUNT: Int = 1
    private const val TRANSFER_COMPLETE_ID_FIELD_INDEX: Int = 0

    private const val TRANSFER_ABORT_FIELD_COUNT: Int = 2
    private const val TRANSFER_ABORT_ID_FIELD_INDEX: Int = 0
    private const val TRANSFER_ABORT_REASON_FIELD_INDEX: Int = 1
}
