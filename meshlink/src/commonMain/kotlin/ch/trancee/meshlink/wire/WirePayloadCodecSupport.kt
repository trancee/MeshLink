package ch.trancee.meshlink.wire

import ch.trancee.meshlink.api.DeliveryPriority

internal fun priorityCode(priority: DeliveryPriority): Byte {
    return when (priority) {
        DeliveryPriority.HIGH -> PRIORITY_CODE_HIGH
        DeliveryPriority.NORMAL -> PRIORITY_CODE_NORMAL
        DeliveryPriority.LOW -> PRIORITY_CODE_LOW
    }
}

internal fun priorityFromCode(code: Byte): DeliveryPriority {
    return when (code.toInt()) {
        PRIORITY_CODE_HIGH.toInt() -> DeliveryPriority.HIGH
        PRIORITY_CODE_NORMAL.toInt() -> DeliveryPriority.NORMAL
        PRIORITY_CODE_LOW.toInt() -> DeliveryPriority.LOW
        else -> error("Unknown delivery priority code ${code.toInt() and PRIORITY_CODE_MASK}")
    }
}

internal fun requireString(table: FlatBufferTable, fieldIndex: Int, fieldName: String): String {
    return table.readString(fieldIndex) ?: error("$fieldName is missing")
}

internal fun requireByteVector(
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

internal const val HELLO_FIELD_COUNT: Int = 2
internal const val HELLO_PEER_ID_FIELD_INDEX: Int = 0
internal const val HELLO_INTERVAL_FIELD_INDEX: Int = 1

internal const val IHU_FIELD_COUNT: Int = 2
internal const val IHU_PEER_ID_FIELD_INDEX: Int = 0
internal const val IHU_RECEIVE_COST_FIELD_INDEX: Int = 1

internal const val ROUTE_UPDATE_FIELD_COUNT: Int = 7
internal const val ROUTE_UPDATE_DESTINATION_FIELD_INDEX: Int = 0
internal const val ROUTE_UPDATE_NEXT_HOP_FIELD_INDEX: Int = 1
internal const val ROUTE_UPDATE_METRIC_FIELD_INDEX: Int = 2
internal const val ROUTE_UPDATE_SEQ_NO_FIELD_INDEX: Int = 3
internal const val ROUTE_UPDATE_FEASIBILITY_FIELD_INDEX: Int = 4
internal const val ROUTE_UPDATE_ED25519_FIELD_INDEX: Int = 5
internal const val ROUTE_UPDATE_X25519_FIELD_INDEX: Int = 6

internal const val ROUTE_RETRACTION_FIELD_COUNT: Int = 2
internal const val ROUTE_RETRACTION_DESTINATION_FIELD_INDEX: Int = 0
internal const val ROUTE_RETRACTION_SEQ_NO_FIELD_INDEX: Int = 1

internal const val SEQNO_REQUEST_FIELD_COUNT: Int = 2
internal const val SEQNO_REQUEST_DESTINATION_FIELD_INDEX: Int = 0
internal const val SEQNO_REQUEST_SEQ_NO_FIELD_INDEX: Int = 1

internal const val ROUTE_DIGEST_FIELD_COUNT: Int = 2
internal const val ROUTE_DIGEST_PEER_ID_FIELD_INDEX: Int = 0
internal const val ROUTE_DIGEST_DIGEST_FIELD_INDEX: Int = 1

internal const val MESSAGE_FIELD_COUNT: Int = 6
internal const val MESSAGE_ID_FIELD_INDEX: Int = 0
internal const val MESSAGE_ORIGIN_FIELD_INDEX: Int = 1
internal const val MESSAGE_DESTINATION_FIELD_INDEX: Int = 2
internal const val MESSAGE_PRIORITY_FIELD_INDEX: Int = 3
internal const val MESSAGE_TTL_FIELD_INDEX: Int = 4
internal const val MESSAGE_PAYLOAD_FIELD_INDEX: Int = 5

internal const val TRANSFER_START_FIELD_COUNT: Int = 7
internal const val TRANSFER_START_ID_FIELD_INDEX: Int = 0
internal const val TRANSFER_START_MESSAGE_ID_FIELD_INDEX: Int = 1
internal const val TRANSFER_START_ORIGIN_FIELD_INDEX: Int = 2
internal const val TRANSFER_START_DESTINATION_FIELD_INDEX: Int = 3
internal const val TRANSFER_START_TOTAL_BYTES_FIELD_INDEX: Int = 4
internal const val TRANSFER_START_TOTAL_CHUNKS_FIELD_INDEX: Int = 5
internal const val TRANSFER_START_CHUNK_BYTES_FIELD_INDEX: Int = 6

internal const val TRANSFER_CHUNK_FIELD_COUNT: Int = 3
internal const val TRANSFER_CHUNK_ID_FIELD_INDEX: Int = 0
internal const val TRANSFER_CHUNK_INDEX_FIELD_INDEX: Int = 1
internal const val TRANSFER_CHUNK_PAYLOAD_FIELD_INDEX: Int = 2

internal const val TRANSFER_ACK_FIELD_COUNT: Int = 3
internal const val TRANSFER_ACK_ID_FIELD_INDEX: Int = 0
internal const val TRANSFER_ACK_HIGHEST_CONTIGUOUS_FIELD_INDEX: Int = 1
internal const val TRANSFER_ACK_SELECTIVE_RANGES_FIELD_INDEX: Int = 2

internal const val TRANSFER_COMPLETE_FIELD_COUNT: Int = 1
internal const val TRANSFER_COMPLETE_ID_FIELD_INDEX: Int = 0

internal const val TRANSFER_ABORT_FIELD_COUNT: Int = 2
internal const val TRANSFER_ABORT_ID_FIELD_INDEX: Int = 0
internal const val TRANSFER_ABORT_REASON_FIELD_INDEX: Int = 1
