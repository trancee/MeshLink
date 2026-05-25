package ch.trancee.meshlink.wire

import ch.trancee.meshlink.api.PeerId

internal object TransferPayloadCodec {
    fun decode(type: WireEnvelopeType, table: FlatBufferTable): WireFrame {
        return when (type) {
            WireEnvelopeType.TRANSFER_START -> decodeTransferStart(table)
            WireEnvelopeType.TRANSFER_CHUNK -> decodeTransferChunk(table)
            WireEnvelopeType.TRANSFER_ACK -> decodeTransferAck(table)
            WireEnvelopeType.TRANSFER_COMPLETE -> decodeTransferComplete(table)
            WireEnvelopeType.TRANSFER_ABORT -> decodeTransferAbort(table)
            else -> error("Unsupported transfer envelope type $type")
        }
    }

    fun encode(frame: WireFrame): ByteArray {
        return when (frame) {
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
            else -> error("Unsupported transfer frame")
        }
    }

    private fun decodeTransferStart(table: FlatBufferTable): WireFrame.TransferStart {
        return WireFrame.TransferStart(
            route =
                WireFrame.TransferStartRoute(
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
                ),
            sizing =
                WireFrame.TransferStartSizing(
                    totalBytes = table.readInt(TRANSFER_START_TOTAL_BYTES_FIELD_INDEX),
                    totalChunks = table.readInt(TRANSFER_START_TOTAL_CHUNKS_FIELD_INDEX),
                    maxChunkPayloadBytes = table.readInt(TRANSFER_START_CHUNK_BYTES_FIELD_INDEX),
                ),
        )
    }

    private fun decodeTransferChunk(table: FlatBufferTable): WireFrame.TransferChunk {
        return WireFrame.TransferChunk(
            transferId =
                requireString(table, TRANSFER_CHUNK_ID_FIELD_INDEX, "TRANSFER_CHUNK.transferId"),
            chunkIndex = table.readInt(TRANSFER_CHUNK_INDEX_FIELD_INDEX),
            payload =
                requireByteVector(
                    table,
                    TRANSFER_CHUNK_PAYLOAD_FIELD_INDEX,
                    "TRANSFER_CHUNK.payload",
                ),
        )
    }

    private fun decodeTransferAck(table: FlatBufferTable): WireFrame.TransferAck {
        return WireFrame.TransferAck(
            transferId =
                requireString(table, TRANSFER_ACK_ID_FIELD_INDEX, "TRANSFER_ACK.transferId"),
            highestContiguousAck = table.readInt(TRANSFER_ACK_HIGHEST_CONTIGUOUS_FIELD_INDEX),
            selectiveRanges =
                requireByteVector(
                    table,
                    TRANSFER_ACK_SELECTIVE_RANGES_FIELD_INDEX,
                    "TRANSFER_ACK.selectiveRanges",
                ),
        )
    }

    private fun decodeTransferComplete(table: FlatBufferTable): WireFrame.TransferComplete {
        return WireFrame.TransferComplete(
            transferId =
                requireString(
                    table,
                    TRANSFER_COMPLETE_ID_FIELD_INDEX,
                    "TRANSFER_COMPLETE.transferId",
                )
        )
    }

    private fun decodeTransferAbort(table: FlatBufferTable): WireFrame.TransferAbort {
        return WireFrame.TransferAbort(
            transferId =
                requireString(table, TRANSFER_ABORT_ID_FIELD_INDEX, "TRANSFER_ABORT.transferId"),
            reasonCode = table.readInt(TRANSFER_ABORT_REASON_FIELD_INDEX),
        )
    }
}
