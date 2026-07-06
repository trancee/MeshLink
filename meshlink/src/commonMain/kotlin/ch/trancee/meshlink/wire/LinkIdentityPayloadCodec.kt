package ch.trancee.meshlink.wire

import ch.trancee.meshlink.api.PeerId

/**
 * LinkIdentity is intentionally cleartext and transport-scoped: it only lets a platform transport
 * correlate a newly observed BLE connection with an already-known logical peer hint before the
 * encrypted hop session can be used. It does not authenticate the claimed peer identity and must
 * never be treated as a trust signal by higher layers.
 */
internal object LinkIdentityPayloadCodec {
    internal fun decode(table: FlatBufferTable): WireFrame.LinkIdentity {
        return WireFrame.LinkIdentity(
            peerId =
                PeerId(
                    requireString(table, LINK_IDENTITY_PEER_ID_FIELD_INDEX, "LINK_IDENTITY.peerId")
                )
        )
    }

    internal fun encode(frame: WireFrame.LinkIdentity): ByteArray {
        return FlatBufferTableBuilder(fieldCount = LINK_IDENTITY_FIELD_COUNT)
            .addString(LINK_IDENTITY_PEER_ID_FIELD_INDEX, frame.peerId.value)
            .finish()
    }
}

internal fun decodeLinkIdentityPeerIdOrNull(bytes: ByteArray): PeerId? {
    val frame = runCatching { WireCodec.decode(bytes) }.getOrNull()
    return (frame as? WireFrame.LinkIdentity)?.peerId
}

/**
 * Returns true if [bytes] decode to a transport-level [WireFrame.KeepAlive] control frame. Callers
 * consume such frames at the transport layer (resetting an idle link's inactivity clock) and must
 * not forward them to application-facing inbound frame events.
 */
internal fun decodeIsKeepAlive(bytes: ByteArray): Boolean {
    val frame = runCatching { WireCodec.decode(bytes) }.getOrNull()
    return frame is WireFrame.KeepAlive
}
