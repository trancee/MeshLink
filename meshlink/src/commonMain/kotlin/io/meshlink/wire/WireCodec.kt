package io.meshlink.wire

/**
 * Central wire codec façade – delegates to specialised codecs while
 * keeping a single entry-point for callers.
 *
 * Sub-codecs:
 *  • [ControlCodec]   – Handshake, Keepalive, Nack, ResumeRequest
 *  • [ChunkCodec]     – Chunk, ChunkAck
 *  • [RoutingCodec]   – Hello (Babel), Update (Babel)
 *  • [MessagingCodec] – RoutedMessage, Broadcast, DeliveryAck
 */
object WireCodec {

    /** Public peer ID size for use by tests and other modules. */
    const val PEER_ID_BYTES = 12

    // Connection & Control (0x00–0x02)
    const val TYPE_HANDSHAKE: Byte = 0x00
    const val TYPE_KEEPALIVE: Byte = 0x01
    const val TYPE_ROTATION: Byte = 0x02

    // Routing (0x03–0x04) — Babel Hello/Update (was AODV RREQ/RREP)
    const val TYPE_ROUTE_REQUEST: Byte = 0x03
    const val TYPE_ROUTE_REPLY: Byte = 0x04
    const val TYPE_HELLO: Byte = TYPE_ROUTE_REQUEST
    const val TYPE_UPDATE: Byte = TYPE_ROUTE_REPLY

    // Data Transfer (0x05–0x08)
    const val TYPE_CHUNK: Byte = 0x05
    const val TYPE_CHUNK_ACK: Byte = 0x06
    const val TYPE_NACK: Byte = 0x07
    const val TYPE_RESUME_REQUEST: Byte = 0x08

    // Messaging (0x09–0x0B)
    const val TYPE_BROADCAST: Byte = 0x09
    const val TYPE_ROUTED_MESSAGE: Byte = 0x0A
    const val TYPE_DELIVERY_ACK: Byte = 0x0B

    // ── Chunk delegates (implementation in ChunkCodec) ─────────

    const val CHUNK_HEADER_SIZE_FIRST = ChunkCodec.CHUNK_HEADER_SIZE_FIRST
    const val CHUNK_HEADER_SIZE_SUBSEQUENT = ChunkCodec.CHUNK_HEADER_SIZE_SUBSEQUENT

    @Deprecated("Use CHUNK_HEADER_SIZE_FIRST for seq=0 or CHUNK_HEADER_SIZE_SUBSEQUENT for seq>0")
    const val CHUNK_HEADER_SIZE = CHUNK_HEADER_SIZE_FIRST

    fun encodeChunk(
        messageId: ByteArray,
        sequenceNumber: UShort,
        totalChunks: UShort,
        payload: ByteArray,
    ) = ChunkCodec.encodeChunk(messageId, sequenceNumber, totalChunks, payload)

    fun decodeChunk(data: ByteArray) = ChunkCodec.decodeChunk(data)

    fun encodeChunkAck(
        messageId: ByteArray,
        ackSequence: UShort,
        sackBitmask: ULong,
        extensions: List<TlvEntry> = emptyList(),
    ) = ChunkCodec.encodeChunkAck(messageId, ackSequence, sackBitmask, extensions)

    fun decodeChunkAck(data: ByteArray) = ChunkCodec.decodeChunkAck(data)

    // ── Routing delegates (implementation in RoutingCodec) ───────

    fun encodeHello(sender: ByteArray, seqNo: UShort) =
        RoutingCodec.encodeHello(sender, seqNo)

    fun decodeHello(data: ByteArray) = RoutingCodec.decodeHello(data)

    fun encodeUpdate(
        destination: ByteArray,
        metric: UShort,
        seqNo: UShort,
        publicKey: ByteArray,
    ) = RoutingCodec.encodeUpdate(destination, metric, seqNo, publicKey)

    fun decodeUpdate(data: ByteArray) = RoutingCodec.decodeUpdate(data)

    // ── Control delegates (implementation in ControlCodec) ──────

    fun encodeHandshake(step: UByte, noiseMessage: ByteArray) =
        ControlCodec.encodeHandshake(step, noiseMessage)

    fun decodeHandshake(data: ByteArray) = ControlCodec.decodeHandshake(data)

    fun encodeKeepalive(
        timestampMillis: ULong,
        flags: UByte = 0u,
        extensions: List<TlvEntry> = emptyList(),
    ) = ControlCodec.encodeKeepalive(timestampMillis, flags, extensions)

    fun decodeKeepalive(data: ByteArray) = ControlCodec.decodeKeepalive(data)

    fun encodeNack(
        messageId: ByteArray,
        reason: NackReason = NackReason.UNKNOWN,
        extensions: List<TlvEntry> = emptyList(),
    ) = ControlCodec.encodeNack(messageId, reason, extensions)

    fun decodeNack(data: ByteArray) = ControlCodec.decodeNack(data)

    fun encodeResumeRequest(
        messageId: ByteArray,
        bytesReceived: UInt,
        extensions: List<TlvEntry> = emptyList(),
    ) = ControlCodec.encodeResumeRequest(messageId, bytesReceived, extensions)

    fun decodeResumeRequest(data: ByteArray) = ControlCodec.decodeResumeRequest(data)

    // ── Messaging delegates (implementation in MessagingCodec) ──

    fun encodeRoutedMessage(
        messageId: ByteArray,
        origin: ByteArray,
        destination: ByteArray,
        hopLimit: UByte,
        visitedList: List<ByteArray>,
        payload: ByteArray,
        replayCounter: ULong = 0u,
        priority: Byte = 0,
    ) = MessagingCodec.encodeRoutedMessage(
        messageId,
        origin,
        destination,
        hopLimit,
        visitedList,
        payload,
        replayCounter,
        priority,
    )

    fun decodeRoutedMessage(data: ByteArray) = MessagingCodec.decodeRoutedMessage(data)

    fun encodeBroadcast(
        messageId: ByteArray,
        origin: ByteArray,
        remainingHops: UByte,
        appIdHash: ByteArray = ByteArray(8),
        payload: ByteArray,
        signature: ByteArray = ByteArray(0),
        signerPublicKey: ByteArray = ByteArray(0),
        priority: Byte = 0,
    ) = MessagingCodec.encodeBroadcast(
        messageId,
        origin,
        remainingHops,
        appIdHash,
        payload,
        signature,
        signerPublicKey,
        priority,
    )

    fun decodeBroadcast(data: ByteArray) = MessagingCodec.decodeBroadcast(data)

    fun encodeDeliveryAck(
        messageId: ByteArray,
        recipientId: ByteArray,
        signature: ByteArray = ByteArray(0),
        signerPublicKey: ByteArray = ByteArray(0),
        extensions: List<TlvEntry> = emptyList(),
    ) = MessagingCodec.encodeDeliveryAck(messageId, recipientId, signature, signerPublicKey, extensions)

    fun decodeDeliveryAck(data: ByteArray) = MessagingCodec.decodeDeliveryAck(data)
}
