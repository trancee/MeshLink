package ch.trancee.meshlink.wire

import ch.trancee.meshlink.api.PeerId

internal object RoutingPayloadCodec {
    fun decode(type: WireEnvelopeType, table: FlatBufferTable): WireFrame {
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
            WireEnvelopeType.ROUTE_UPDATE -> decodeRouteUpdate(table)
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
            else -> error("Unsupported routing envelope type $type")
        }
    }

    fun encode(frame: WireFrame): ByteArray {
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
            is WireFrame.RouteUpdate -> encodeRouteUpdate(frame)
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
            else -> error("Unsupported routing frame")
        }
    }

    private fun decodeRouteUpdate(table: FlatBufferTable): WireFrame.RouteUpdate {
        return WireFrame.RouteUpdate(
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
            metrics =
                WireFrame.RouteUpdateMetrics(
                    metric = table.readInt(ROUTE_UPDATE_METRIC_FIELD_INDEX),
                    seqNo = table.readLong(ROUTE_UPDATE_SEQ_NO_FIELD_INDEX),
                    feasibilityMetric = table.readInt(ROUTE_UPDATE_FEASIBILITY_FIELD_INDEX),
                ),
            publicKeys =
                WireFrame.RouteUpdatePublicKeys(
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
                ),
        )
    }

    private fun encodeRouteUpdate(frame: WireFrame.RouteUpdate): ByteArray {
        return FlatBufferTableBuilder(fieldCount = ROUTE_UPDATE_FIELD_COUNT)
            .addString(ROUTE_UPDATE_DESTINATION_FIELD_INDEX, frame.destinationPeerId.value)
            .addString(ROUTE_UPDATE_NEXT_HOP_FIELD_INDEX, frame.nextHopPeerId.value)
            .addInt(ROUTE_UPDATE_METRIC_FIELD_INDEX, frame.metric)
            .addLong(ROUTE_UPDATE_SEQ_NO_FIELD_INDEX, frame.seqNo)
            .addInt(ROUTE_UPDATE_FEASIBILITY_FIELD_INDEX, frame.feasibilityMetric)
            .addByteVector(ROUTE_UPDATE_ED25519_FIELD_INDEX, frame.destinationEd25519PublicKey)
            .addByteVector(ROUTE_UPDATE_X25519_FIELD_INDEX, frame.destinationX25519PublicKey)
            .finish()
    }
}
