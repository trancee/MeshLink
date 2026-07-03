package ch.trancee.meshlink.wire

import ch.trancee.meshlink.api.PeerId

/**
 * Encodes/decodes the relayed end-to-end Noise XX handshake frames. All three handshake stages
 * share one field layout; only the envelope [WireEnvelopeType] distinguishes stage 1/2/3 so relays
 * can forward every stage identically while the origin/destination pair processes the opaque
 * [WireFrame.EndToEndHandshakeMessage1.payload] Noise bytes.
 */
internal object EndToEndHandshakePayloadCodec {
    fun decode(type: WireEnvelopeType, table: FlatBufferTable): WireFrame {
        val route = decodeRoute(table)
        val payload =
            requireByteVector(table, E2E_HANDSHAKE_PAYLOAD_FIELD_INDEX, "E2E_HANDSHAKE.payload")
        return when (type) {
            WireEnvelopeType.E2E_HANDSHAKE_MESSAGE_1 ->
                WireFrame.EndToEndHandshakeMessage1(route = route, payload = payload)
            WireEnvelopeType.E2E_HANDSHAKE_MESSAGE_2 ->
                WireFrame.EndToEndHandshakeMessage2(route = route, payload = payload)
            WireEnvelopeType.E2E_HANDSHAKE_MESSAGE_3 ->
                WireFrame.EndToEndHandshakeMessage3(route = route, payload = payload)
            else -> error("Unsupported end-to-end handshake envelope type $type")
        }
    }

    fun encode(frame: WireFrame): ByteArray {
        require(frame is WireFrame.EndToEndHandshakeFrame) {
            "Unsupported end-to-end handshake frame"
        }
        return FlatBufferTableBuilder(fieldCount = E2E_HANDSHAKE_FIELD_COUNT)
            .addString(E2E_HANDSHAKE_ID_FIELD_INDEX, frame.handshakeId)
            .addString(E2E_HANDSHAKE_ORIGIN_FIELD_INDEX, frame.originPeerId.value)
            .addString(E2E_HANDSHAKE_DESTINATION_FIELD_INDEX, frame.destinationPeerId.value)
            .addByteVector(E2E_HANDSHAKE_PAYLOAD_FIELD_INDEX, frame.payload)
            .finish()
    }

    private fun decodeRoute(table: FlatBufferTable): WireFrame.EndToEndHandshakeRoute {
        return WireFrame.EndToEndHandshakeRoute(
            handshakeId =
                requireString(table, E2E_HANDSHAKE_ID_FIELD_INDEX, "E2E_HANDSHAKE.handshakeId"),
            originPeerId =
                PeerId(
                    requireString(
                        table,
                        E2E_HANDSHAKE_ORIGIN_FIELD_INDEX,
                        "E2E_HANDSHAKE.originPeerId",
                    )
                ),
            destinationPeerId =
                PeerId(
                    requireString(
                        table,
                        E2E_HANDSHAKE_DESTINATION_FIELD_INDEX,
                        "E2E_HANDSHAKE.destinationPeerId",
                    )
                ),
        )
    }
}
