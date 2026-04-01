package io.meshlink.wire

private const val MESSAGE_ID_SIZE = 16
private const val PEER_ID_SIZE = 8
private const val APP_ID_HASH_SIZE = 16

object WireCodec {

    const val TYPE_BROADCAST: Byte = 0x00
    const val TYPE_HANDSHAKE: Byte = 0x01
    const val TYPE_ROUTE_UPDATE: Byte = 0x02
    const val TYPE_CHUNK: Byte = 0x03
    const val TYPE_CHUNK_ACK: Byte = 0x04
    const val TYPE_ROUTED_MESSAGE: Byte = 0x05
    const val TYPE_DELIVERY_ACK: Byte = 0x06
    const val TYPE_RESUME_REQUEST: Byte = 0x07
    const val TYPE_KEEPALIVE: Byte = 0x08
    const val TYPE_NACK: Byte = 0x09
    const val TYPE_ROTATION: Byte = 0x0A
    const val TYPE_ROUTE_REQUEST: Byte = 0x0B
    const val TYPE_ROUTE_REPLY: Byte = 0x0C

    // keepalive: type(1) + flags(1) + timestamp(8 LE ulong)
    private const val KEEPALIVE_SIZE = 10

    // resume_request: type(1) + messageId(16) + bytesReceived(4 LE) = 21
    private const val RESUME_REQUEST_SIZE = 1 + MESSAGE_ID_SIZE + 4 // 21

    // chunk: type(1) + messageId(16) + seqNum(2 LE) + totalChunks(2 LE) + payload
    const val CHUNK_HEADER_SIZE = 1 + MESSAGE_ID_SIZE + 2 + 2 // 21

    // chunk_ack: type(1) + messageId(16) + ackSeq(2 LE) + sackBitmask(8 LE) + sackBitmaskHigh(8 LE)
    private const val CHUNK_ACK_SIZE = 1 + MESSAGE_ID_SIZE + 2 + 8 + 8 // 35

    // handshake: type(1) + step(1) + noiseMessage(variable)
    private const val HANDSHAKE_HEADER_SIZE = 2

    // route_request: type(1) + origin(8) + destination(8) + requestId(4 LE) + hopCount(1) + hopLimit(1)
    const val ROUTE_REQUEST_SIZE = 1 + PEER_ID_SIZE + PEER_ID_SIZE + 4 + 1 + 1 // 23

    // route_reply: type(1) + origin(8) + destination(8) + requestId(4 LE) + hopCount(1)
    const val ROUTE_REPLY_SIZE = 1 + PEER_ID_SIZE + PEER_ID_SIZE + 4 + 1 // 22

    fun encodeHandshake(step: UByte, noiseMessage: ByteArray): ByteArray {
        val buf = ByteArray(HANDSHAKE_HEADER_SIZE + noiseMessage.size)
        buf[0] = TYPE_HANDSHAKE
        buf[1] = step.toByte()
        noiseMessage.copyInto(buf, HANDSHAKE_HEADER_SIZE)
        return buf
    }

    fun decodeHandshake(data: ByteArray): HandshakeMessage {
        require(data.size >= HANDSHAKE_HEADER_SIZE) { "handshake too short: ${data.size}" }
        require(data[0] == TYPE_HANDSHAKE) { "not a handshake: 0x${data[0].toUByte().toString(16)}" }
        val step = data[1].toUByte()
        require(step <= 2u) { "invalid handshake step: $step (must be 0, 1, or 2)" }
        val noiseMessage = data.copyOfRange(HANDSHAKE_HEADER_SIZE, data.size)
        return HandshakeMessage(step, noiseMessage)
    }

    fun encodeChunk(
        messageId: ByteArray,
        sequenceNumber: UShort,
        totalChunks: UShort,
        payload: ByteArray,
    ): ByteArray {
        val buf = ByteArray(CHUNK_HEADER_SIZE + payload.size)
        var offset = 0
        buf[offset++] = TYPE_CHUNK
        messageId.copyInto(buf, offset)
        offset += MESSAGE_ID_SIZE
        buf.putUShortLE(offset, sequenceNumber)
        offset += 2
        buf.putUShortLE(offset, totalChunks)
        offset += 2
        payload.copyInto(buf, offset)
        return buf
    }

    fun decodeChunk(data: ByteArray): ChunkMessage {
        require(data.size >= CHUNK_HEADER_SIZE) { "chunk too short: ${data.size}" }
        require(data[0] == TYPE_CHUNK) { "not a chunk: 0x${data[0].toUByte().toString(16)}" }
        var offset = 1
        val messageId = data.copyOfRange(offset, offset + MESSAGE_ID_SIZE)
        offset += MESSAGE_ID_SIZE
        val sequenceNumber = data.getUShortLE(offset)
        offset += 2
        val totalChunks = data.getUShortLE(offset)
        offset += 2
        val payload = data.copyOfRange(offset, data.size)
        require(sequenceNumber < totalChunks) {
            "chunk sequenceNumber ($sequenceNumber) >= totalChunks ($totalChunks)"
        }
        return ChunkMessage(messageId, sequenceNumber, totalChunks, payload)
    }

    fun encodeChunkAck(
        messageId: ByteArray,
        ackSequence: UShort,
        sackBitmask: ULong,
        sackBitmaskHigh: ULong,
        extensions: List<TlvEntry> = emptyList(),
    ): ByteArray {
        val extBytes = TlvCodec.encode(extensions)
        val buf = ByteArray(CHUNK_ACK_SIZE + extBytes.size)
        var offset = 0
        buf[offset++] = TYPE_CHUNK_ACK
        messageId.copyInto(buf, offset)
        offset += MESSAGE_ID_SIZE
        buf.putUShortLE(offset, ackSequence)
        offset += 2
        buf.putULongLE(offset, sackBitmask)
        offset += 8
        buf.putULongLE(offset, sackBitmaskHigh)
        extBytes.copyInto(buf, CHUNK_ACK_SIZE)
        return buf
    }

    fun decodeChunkAck(data: ByteArray): ChunkAckMessage {
        require(data.size >= CHUNK_ACK_SIZE) { "chunk_ack too short: ${data.size}" }
        require(data[0] == TYPE_CHUNK_ACK) { "not a chunk_ack: 0x${data[0].toUByte().toString(16)}" }
        var offset = 1
        val messageId = data.copyOfRange(offset, offset + MESSAGE_ID_SIZE)
        offset += MESSAGE_ID_SIZE
        val ackSequence = data.getUShortLE(offset)
        offset += 2
        val sackBitmask = data.getULongLE(offset)
        offset += 8
        val sackBitmaskHigh = data.getULongLE(offset)
        val extensions = if (data.size > CHUNK_ACK_SIZE) {
            TlvCodec.decode(data, CHUNK_ACK_SIZE).first
        } else {
            emptyList()
        }
        return ChunkAckMessage(messageId, ackSequence, sackBitmask, sackBitmaskHigh, extensions)
    }

    // routed_message: type(1) + messageId(16) + origin(8) + destination(8) + hopLimit(1) + replayCounter(8 LE) + visitedCount(1) + visited(N×8) + payload
    private const val ROUTED_HEADER_SIZE = 1 + MESSAGE_ID_SIZE + PEER_ID_SIZE + PEER_ID_SIZE + 1 + 8 + 1 // 43

    fun encodeRoutedMessage(
        messageId: ByteArray,
        origin: ByteArray,
        destination: ByteArray,
        hopLimit: UByte,
        visitedList: List<ByteArray>,
        payload: ByteArray,
        replayCounter: ULong = 0u,
    ): ByteArray {
        require(visitedList.size <= 255) { "visitedList too large: ${visitedList.size} (max 255)" }
        val buf = ByteArray(ROUTED_HEADER_SIZE + visitedList.size * PEER_ID_SIZE + payload.size)
        var offset = 0
        buf[offset++] = TYPE_ROUTED_MESSAGE
        messageId.copyInto(buf, offset)
        offset += MESSAGE_ID_SIZE
        origin.copyInto(buf, offset)
        offset += PEER_ID_SIZE
        destination.copyInto(buf, offset)
        offset += PEER_ID_SIZE
        buf[offset++] = hopLimit.toByte()
        buf.putULongLE(offset, replayCounter)
        offset += 8
        buf[offset++] = visitedList.size.toByte()
        for (hash in visitedList) {
            hash.copyInto(buf, offset)
            offset += PEER_ID_SIZE
        }
        payload.copyInto(buf, offset)
        return buf
    }

    fun decodeRoutedMessage(data: ByteArray): RoutedMessage {
        require(data.size >= ROUTED_HEADER_SIZE) { "routed_message too short: ${data.size}" }
        require(data[0] == TYPE_ROUTED_MESSAGE) { "not a routed_message: 0x${data[0].toUByte().toString(16)}" }
        var offset = 1
        val messageId = data.copyOfRange(offset, offset + MESSAGE_ID_SIZE)
        offset += MESSAGE_ID_SIZE
        val origin = data.copyOfRange(offset, offset + PEER_ID_SIZE)
        offset += PEER_ID_SIZE
        val destination = data.copyOfRange(offset, offset + PEER_ID_SIZE)
        offset += PEER_ID_SIZE
        val hopLimit = data[offset++].toUByte()
        val replayCounter = data.getULongLE(offset)
        offset += 8
        val visitedCount = data[offset++].toInt() and 0xFF
        require(data.size >= offset + visitedCount * PEER_ID_SIZE) {
            "routed_message truncated: visitedCount=$visitedCount requires ${offset + visitedCount * PEER_ID_SIZE} bytes, got ${data.size}"
        }
        val visitedList = (0 until visitedCount).map {
            val hash = data.copyOfRange(offset, offset + PEER_ID_SIZE)
            offset += PEER_ID_SIZE
            hash
        }
        val payload = data.copyOfRange(offset, data.size)
        return RoutedMessage(messageId, origin, destination, hopLimit, replayCounter, visitedList, payload)
    }

    // broadcast: type(1) + messageId(16) + origin(8) + remainingHops(1) + appIdHash(16) + flags(1) + [signature(64) + signerPubKey(32)] + payload
    private const val BROADCAST_HEADER_SIZE = 1 + MESSAGE_ID_SIZE + PEER_ID_SIZE + 1 + APP_ID_HASH_SIZE + 1 // 43
    private const val ED25519_SIG_SIZE = 64
    private const val ED25519_PUB_KEY_SIZE = 32
    private const val FLAG_HAS_SIGNATURE = 0x01

    fun encodeBroadcast(
        messageId: ByteArray,
        origin: ByteArray,
        remainingHops: UByte,
        appIdHash: ByteArray = ByteArray(APP_ID_HASH_SIZE),
        payload: ByteArray,
        signature: ByteArray = ByteArray(0),
        signerPublicKey: ByteArray = ByteArray(0),
    ): ByteArray {
        val sigBlock = if (signature.isNotEmpty()) ED25519_SIG_SIZE + ED25519_PUB_KEY_SIZE else 0
        val buf = ByteArray(BROADCAST_HEADER_SIZE + sigBlock + payload.size)
        var offset = 0
        buf[offset++] = TYPE_BROADCAST
        messageId.copyInto(buf, offset)
        offset += MESSAGE_ID_SIZE
        origin.copyInto(buf, offset)
        offset += PEER_ID_SIZE
        buf[offset++] = remainingHops.toByte()
        appIdHash.copyInto(buf, offset)
        offset += APP_ID_HASH_SIZE
        buf[offset++] = if (signature.isNotEmpty()) FLAG_HAS_SIGNATURE.toByte() else 0
        if (signature.isNotEmpty()) {
            signature.copyInto(buf, offset)
            offset += ED25519_SIG_SIZE
            signerPublicKey.copyInto(buf, offset)
            offset += ED25519_PUB_KEY_SIZE
        }
        payload.copyInto(buf, offset)
        return buf
    }

    fun decodeBroadcast(data: ByteArray): BroadcastMessage {
        require(data.size >= BROADCAST_HEADER_SIZE) { "broadcast too short: ${data.size}" }
        require(data[0] == TYPE_BROADCAST) { "not a broadcast: 0x${data[0].toUByte().toString(16)}" }
        var offset = 1
        val messageId = data.copyOfRange(offset, offset + MESSAGE_ID_SIZE)
        offset += MESSAGE_ID_SIZE
        val origin = data.copyOfRange(offset, offset + PEER_ID_SIZE)
        offset += PEER_ID_SIZE
        val remainingHops = data[offset++].toUByte()
        val appIdHash = data.copyOfRange(offset, offset + APP_ID_HASH_SIZE)
        offset += APP_ID_HASH_SIZE
        val flags = data[offset++].toInt() and 0xFF
        val signature: ByteArray
        val signerPublicKey: ByteArray
        if (flags and FLAG_HAS_SIGNATURE != 0) {
            require(data.size >= offset + ED25519_SIG_SIZE + ED25519_PUB_KEY_SIZE) {
                "broadcast signature truncated: requires ${offset + ED25519_SIG_SIZE + ED25519_PUB_KEY_SIZE} bytes, got ${data.size}"
            }
            signature = data.copyOfRange(offset, offset + ED25519_SIG_SIZE)
            offset += ED25519_SIG_SIZE
            signerPublicKey = data.copyOfRange(offset, offset + ED25519_PUB_KEY_SIZE)
            offset += ED25519_PUB_KEY_SIZE
        } else {
            signature = ByteArray(0)
            signerPublicKey = ByteArray(0)
        }
        val payload = data.copyOfRange(offset, data.size)
        return BroadcastMessage(messageId, origin, remainingHops, appIdHash, payload, signature, signerPublicKey)
    }

    // delivery_ack: type(1) + messageId(16) + recipientId(8) + flags(1) + [signature(64) + signerPubKey(32)]
    private const val DELIVERY_ACK_HEADER_SIZE = 1 + MESSAGE_ID_SIZE + PEER_ID_SIZE + 1 // 26

    fun encodeDeliveryAck(
        messageId: ByteArray,
        recipientId: ByteArray,
        signature: ByteArray = ByteArray(0),
        signerPublicKey: ByteArray = ByteArray(0),
        extensions: List<TlvEntry> = emptyList(),
    ): ByteArray {
        val sigBlock = if (signature.isNotEmpty()) ED25519_SIG_SIZE + ED25519_PUB_KEY_SIZE else 0
        val extBytes = TlvCodec.encode(extensions)
        val buf = ByteArray(DELIVERY_ACK_HEADER_SIZE + sigBlock + extBytes.size)
        var offset = 0
        buf[offset++] = TYPE_DELIVERY_ACK
        messageId.copyInto(buf, offset)
        offset += MESSAGE_ID_SIZE
        recipientId.copyInto(buf, offset)
        offset += PEER_ID_SIZE
        buf[offset++] = if (signature.isNotEmpty()) FLAG_HAS_SIGNATURE.toByte() else 0
        if (signature.isNotEmpty()) {
            signature.copyInto(buf, offset)
            offset += ED25519_SIG_SIZE
            signerPublicKey.copyInto(buf, offset)
            offset += ED25519_PUB_KEY_SIZE
        }
        extBytes.copyInto(buf, offset)
        return buf
    }

    fun decodeDeliveryAck(data: ByteArray): DeliveryAckMessage {
        require(data.size >= DELIVERY_ACK_HEADER_SIZE) { "delivery_ack too short: ${data.size}" }
        require(data[0] == TYPE_DELIVERY_ACK) { "not a delivery_ack: 0x${data[0].toUByte().toString(16)}" }
        var offset = 1
        val messageId = data.copyOfRange(offset, offset + MESSAGE_ID_SIZE)
        offset += MESSAGE_ID_SIZE
        val recipientId = data.copyOfRange(offset, offset + PEER_ID_SIZE)
        offset += PEER_ID_SIZE
        val flags = data[offset++].toInt() and 0xFF
        val signature: ByteArray
        val signerPublicKey: ByteArray
        if (flags and FLAG_HAS_SIGNATURE != 0) {
            require(data.size >= offset + ED25519_SIG_SIZE + ED25519_PUB_KEY_SIZE) {
                "delivery_ack signature truncated: requires ${offset + ED25519_SIG_SIZE + ED25519_PUB_KEY_SIZE} bytes, got ${data.size}"
            }
            signature = data.copyOfRange(offset, offset + ED25519_SIG_SIZE)
            offset += ED25519_SIG_SIZE
            signerPublicKey = data.copyOfRange(offset, offset + ED25519_PUB_KEY_SIZE)
            offset += ED25519_PUB_KEY_SIZE
        } else {
            signature = ByteArray(0)
            signerPublicKey = ByteArray(0)
        }
        val extensions = if (data.size > offset) {
            TlvCodec.decode(data, offset).first
        } else {
            emptyList()
        }
        return DeliveryAckMessage(messageId, recipientId, signature, signerPublicKey, extensions)
    }

    // route_update: type(1) + sender(8) + entryCount(1) + entries(N × 21)
    // signed_route_update: route_update + signerPublicKey(32) + signature(64)
    // each entry: destination(8) + cost(8 LE double) + sequenceNumber(4 LE uint) + hopCount(1)
    private const val ROUTE_UPDATE_HEADER_SIZE = 1 + PEER_ID_SIZE + 1 // 10
    private const val ROUTE_ENTRY_SIZE = PEER_ID_SIZE + 8 + 4 + 1 // 21
    private const val SIGNER_PUBLIC_KEY_SIZE = 32
    private const val SIGNATURE_SIZE = 64

    fun encodeRouteUpdate(senderId: ByteArray, entries: List<RouteUpdateEntry>): ByteArray {
        val buf = ByteArray(ROUTE_UPDATE_HEADER_SIZE + entries.size * ROUTE_ENTRY_SIZE)
        var offset = 0
        buf[offset++] = TYPE_ROUTE_UPDATE
        senderId.copyInto(buf, offset)
        offset += PEER_ID_SIZE
        buf[offset++] = entries.size.toByte()
        for (entry in entries) {
            entry.destination.copyInto(buf, offset)
            offset += PEER_ID_SIZE
            buf.putDoubleBitsLE(offset, entry.cost)
            offset += 8
            buf.putUIntLE(offset, entry.sequenceNumber)
            offset += 4
            buf[offset++] = entry.hopCount.toByte()
        }
        return buf
    }

    fun encodeSignedRouteUpdate(
        senderId: ByteArray,
        entries: List<RouteUpdateEntry>,
        signerPublicKey: ByteArray,
        signature: ByteArray,
    ): ByteArray {
        val unsigned = encodeRouteUpdate(senderId, entries)
        val buf = ByteArray(unsigned.size + SIGNER_PUBLIC_KEY_SIZE + SIGNATURE_SIZE)
        unsigned.copyInto(buf)
        signerPublicKey.copyInto(buf, unsigned.size)
        signature.copyInto(buf, unsigned.size + SIGNER_PUBLIC_KEY_SIZE)
        return buf
    }

    fun encodeResumeRequest(
        messageId: ByteArray,
        bytesReceived: UInt,
        extensions: List<TlvEntry> = emptyList(),
    ): ByteArray {
        require(messageId.size == 16) { "messageId must be 16 bytes" }
        val extBytes = TlvCodec.encode(extensions)
        val buf = ByteArray(RESUME_REQUEST_SIZE + extBytes.size)
        var offset = 0
        buf[offset++] = TYPE_RESUME_REQUEST
        messageId.copyInto(buf, offset)
        offset += MESSAGE_ID_SIZE
        buf.putUIntLE(offset, bytesReceived)
        extBytes.copyInto(buf, RESUME_REQUEST_SIZE)
        return buf
    }

    fun decodeResumeRequest(data: ByteArray): ResumeRequestMessage {
        require(data.size >= RESUME_REQUEST_SIZE) { "resume_request too short: ${data.size}" }
        require(data[0] == TYPE_RESUME_REQUEST) { "not a resume_request: 0x${data[0].toUByte().toString(16)}" }
        var offset = 1
        val messageId = data.copyOfRange(offset, offset + MESSAGE_ID_SIZE)
        offset += MESSAGE_ID_SIZE
        val bytesReceived = data.getUIntLE(offset)
        val extensions = if (data.size > RESUME_REQUEST_SIZE) {
            TlvCodec.decode(data, RESUME_REQUEST_SIZE).first
        } else {
            emptyList()
        }
        return ResumeRequestMessage(messageId, bytesReceived, extensions)
    }

    fun decodeRouteUpdate(data: ByteArray): RouteUpdateMessage {
        require(data.size >= ROUTE_UPDATE_HEADER_SIZE) { "route_update too short: ${data.size}" }
        require(data[0] == TYPE_ROUTE_UPDATE) { "not a route_update: 0x${data[0].toUByte().toString(16)}" }
        var offset = 1
        val senderId = data.copyOfRange(offset, offset + PEER_ID_SIZE)
        offset += PEER_ID_SIZE
        val entryCount = data[offset++].toInt() and 0xFF
        val entriesEnd = ROUTE_UPDATE_HEADER_SIZE + entryCount * ROUTE_ENTRY_SIZE
        require(data.size >= entriesEnd) {
            "route_update truncated: expected $entriesEnd, got ${data.size}"
        }
        val entries = (0 until entryCount).map {
            val destination = data.copyOfRange(offset, offset + PEER_ID_SIZE)
            offset += PEER_ID_SIZE
            val cost = data.getDoubleBitsLE(offset)
            offset += 8
            require(cost.isFinite() && cost >= 0.0) {
                "route entry cost invalid: $cost (must be finite and non-negative)"
            }
            val sequenceNumber = data.getUIntLE(offset)
            offset += 4
            val hopCount = data[offset++].toUByte()
            RouteUpdateEntry(destination, cost, sequenceNumber, hopCount)
        }
        // Check for trailing signature (signerPublicKey(32) + signature(64))
        val remaining = data.size - entriesEnd
        val signerPublicKey: ByteArray?
        val signature: ByteArray?
        if (remaining >= SIGNER_PUBLIC_KEY_SIZE + SIGNATURE_SIZE) {
            signerPublicKey = data.copyOfRange(entriesEnd, entriesEnd + SIGNER_PUBLIC_KEY_SIZE)
            signature = data.copyOfRange(entriesEnd + SIGNER_PUBLIC_KEY_SIZE, entriesEnd + SIGNER_PUBLIC_KEY_SIZE + SIGNATURE_SIZE)
        } else {
            signerPublicKey = null
            signature = null
        }
        return RouteUpdateMessage(senderId, entries, signerPublicKey, signature)
    }

    fun encodeKeepalive(
        timestampMillis: ULong,
        flags: UByte = 0u,
        extensions: List<TlvEntry> = emptyList(),
    ): ByteArray {
        val extBytes = TlvCodec.encode(extensions)
        val buf = ByteArray(KEEPALIVE_SIZE + extBytes.size)
        buf[0] = TYPE_KEEPALIVE
        buf[1] = flags.toByte()
        buf.putULongLE(2, timestampMillis)
        extBytes.copyInto(buf, KEEPALIVE_SIZE)
        return buf
    }

    fun decodeKeepalive(data: ByteArray): KeepaliveMessage {
        require(data.size >= KEEPALIVE_SIZE) { "keepalive too short: ${data.size}" }
        require(data[0] == TYPE_KEEPALIVE) { "not a keepalive: 0x${data[0].toUByte().toString(16)}" }
        val flags = data[1].toUByte()
        val timestampMillis = data.getULongLE(2)
        val extensions = if (data.size > KEEPALIVE_SIZE) {
            TlvCodec.decode(data, KEEPALIVE_SIZE).first
        } else {
            emptyList()
        }
        return KeepaliveMessage(flags, timestampMillis, extensions)
    }

    // nack: type(1) + messageId(16) + reason(1) = 18
    private const val NACK_SIZE = 18

    fun encodeNack(
        messageId: ByteArray,
        reason: NackReason = NackReason.UNKNOWN,
        extensions: List<TlvEntry> = emptyList(),
    ): ByteArray {
        require(messageId.size == MESSAGE_ID_SIZE) { "messageId must be $MESSAGE_ID_SIZE bytes" }
        val extBytes = TlvCodec.encode(extensions)
        val buf = ByteArray(NACK_SIZE + extBytes.size)
        buf[0] = TYPE_NACK
        messageId.copyInto(buf, 1)
        buf[17] = reason.code.toByte()
        extBytes.copyInto(buf, NACK_SIZE)
        return buf
    }

    fun decodeNack(data: ByteArray): NackMessage {
        require(data.size >= NACK_SIZE) { "nack too short: ${data.size}" }
        require(data[0] == TYPE_NACK) { "not a nack: 0x${data[0].toUByte().toString(16)}" }
        val messageId = data.copyOfRange(1, 1 + MESSAGE_ID_SIZE)
        val reason = NackReason.fromCode(data[17].toUByte())
        val extensions = if (data.size > NACK_SIZE) {
            TlvCodec.decode(data, NACK_SIZE).first
        } else {
            emptyList()
        }
        return NackMessage(messageId, reason, extensions)
    }

    // ── Route Request (AODV RREQ) ────────────────────────────────

    fun encodeRouteRequest(
        origin: ByteArray,
        destination: ByteArray,
        requestId: UInt,
        hopCount: UByte = 0u,
        hopLimit: UByte = 10u,
        extensions: List<TlvEntry> = emptyList(),
    ): ByteArray {
        require(origin.size == PEER_ID_SIZE) { "origin must be $PEER_ID_SIZE bytes" }
        require(destination.size == PEER_ID_SIZE) { "destination must be $PEER_ID_SIZE bytes" }
        val extBytes = TlvCodec.encode(extensions)
        val buf = ByteArray(ROUTE_REQUEST_SIZE + extBytes.size)
        var offset = 0
        buf[offset++] = TYPE_ROUTE_REQUEST
        origin.copyInto(buf, offset)
        offset += PEER_ID_SIZE
        destination.copyInto(buf, offset)
        offset += PEER_ID_SIZE
        buf.putUIntLE(offset, requestId)
        offset += 4
        buf[offset++] = hopCount.toByte()
        buf[offset] = hopLimit.toByte()
        extBytes.copyInto(buf, ROUTE_REQUEST_SIZE)
        return buf
    }

    fun decodeRouteRequest(data: ByteArray): RouteRequestMessage {
        require(data.size >= ROUTE_REQUEST_SIZE) { "route_request too short: ${data.size}" }
        require(data[0] == TYPE_ROUTE_REQUEST) { "not a route_request: 0x${data[0].toUByte().toString(16)}" }
        var offset = 1
        val origin = data.copyOfRange(offset, offset + PEER_ID_SIZE)
        offset += PEER_ID_SIZE
        val destination = data.copyOfRange(offset, offset + PEER_ID_SIZE)
        offset += PEER_ID_SIZE
        val requestId = data.getUIntLE(offset)
        offset += 4
        val hopCount = data[offset++].toUByte()
        val hopLimit = data[offset].toUByte()
        val extensions = if (data.size > ROUTE_REQUEST_SIZE) {
            TlvCodec.decode(data, ROUTE_REQUEST_SIZE).first
        } else {
            emptyList()
        }
        return RouteRequestMessage(origin, destination, requestId, hopCount, hopLimit, extensions)
    }

    // ── Route Reply (AODV RREP) ──────────────────────────────────

    fun encodeRouteReply(
        origin: ByteArray,
        destination: ByteArray,
        requestId: UInt,
        hopCount: UByte = 0u,
        extensions: List<TlvEntry> = emptyList(),
    ): ByteArray {
        require(origin.size == PEER_ID_SIZE) { "origin must be $PEER_ID_SIZE bytes" }
        require(destination.size == PEER_ID_SIZE) { "destination must be $PEER_ID_SIZE bytes" }
        val extBytes = TlvCodec.encode(extensions)
        val buf = ByteArray(ROUTE_REPLY_SIZE + extBytes.size)
        var offset = 0
        buf[offset++] = TYPE_ROUTE_REPLY
        origin.copyInto(buf, offset)
        offset += PEER_ID_SIZE
        destination.copyInto(buf, offset)
        offset += PEER_ID_SIZE
        buf.putUIntLE(offset, requestId)
        offset += 4
        buf[offset] = hopCount.toByte()
        extBytes.copyInto(buf, ROUTE_REPLY_SIZE)
        return buf
    }

    fun decodeRouteReply(data: ByteArray): RouteReplyMessage {
        require(data.size >= ROUTE_REPLY_SIZE) { "route_reply too short: ${data.size}" }
        require(data[0] == TYPE_ROUTE_REPLY) { "not a route_reply: 0x${data[0].toUByte().toString(16)}" }
        var offset = 1
        val origin = data.copyOfRange(offset, offset + PEER_ID_SIZE)
        offset += PEER_ID_SIZE
        val destination = data.copyOfRange(offset, offset + PEER_ID_SIZE)
        offset += PEER_ID_SIZE
        val requestId = data.getUIntLE(offset)
        offset += 4
        val hopCount = data[offset].toUByte()
        val extensions = if (data.size > ROUTE_REPLY_SIZE) {
            TlvCodec.decode(data, ROUTE_REPLY_SIZE).first
        } else {
            emptyList()
        }
        return RouteReplyMessage(origin, destination, requestId, hopCount, extensions)
    }
}

// --- Little-endian helpers ---

private fun ByteArray.putUShortLE(offset: Int, value: UShort) {
    val v = value.toInt()
    this[offset] = v.toByte()
    this[offset + 1] = (v shr 8).toByte()
}

private fun ByteArray.getUShortLE(offset: Int): UShort =
    (
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8)
        ).toUShort()

private fun ByteArray.putULongLE(offset: Int, value: ULong) {
    val v = value.toLong()
    for (i in 0..7) {
        this[offset + i] = (v shr (i * 8)).toByte()
    }
}

private fun ByteArray.getULongLE(offset: Int): ULong {
    var result = 0L
    for (i in 0..7) {
        result = result or ((this[offset + i].toLong() and 0xFF) shl (i * 8))
    }
    return result.toULong()
}

private fun ByteArray.putUIntLE(offset: Int, value: UInt) {
    val v = value.toInt()
    for (i in 0..3) {
        this[offset + i] = (v shr (i * 8)).toByte()
    }
}

private fun ByteArray.getUIntLE(offset: Int): UInt {
    var result = 0
    for (i in 0..3) {
        result = result or ((this[offset + i].toInt() and 0xFF) shl (i * 8))
    }
    return result.toUInt()
}

private fun ByteArray.putDoubleBitsLE(offset: Int, value: Double) {
    putULongLE(offset, value.toRawBits().toULong())
}

private fun ByteArray.getDoubleBitsLE(offset: Int): Double {
    return Double.fromBits(getULongLE(offset).toLong())
}

// --- Data classes ---

data class ChunkMessage(
    val messageId: ByteArray,
    val sequenceNumber: UShort,
    val totalChunks: UShort,
    val payload: ByteArray,
)

data class ChunkAckMessage(
    val messageId: ByteArray,
    val ackSequence: UShort,
    val sackBitmask: ULong,
    val sackBitmaskHigh: ULong,
    val extensions: List<TlvEntry> = emptyList(),
)

data class RoutedMessage(
    val messageId: ByteArray,
    val origin: ByteArray,
    val destination: ByteArray,
    val hopLimit: UByte,
    val replayCounter: ULong,
    val visitedList: List<ByteArray>,
    val payload: ByteArray,
)

data class BroadcastMessage(
    val messageId: ByteArray,
    val origin: ByteArray,
    val remainingHops: UByte,
    val appIdHash: ByteArray,
    val payload: ByteArray,
    val signature: ByteArray = ByteArray(0),
    val signerPublicKey: ByteArray = ByteArray(0),
)

data class DeliveryAckMessage(
    val messageId: ByteArray,
    val recipientId: ByteArray,
    val signature: ByteArray = ByteArray(0),
    val signerPublicKey: ByteArray = ByteArray(0),
    val extensions: List<TlvEntry> = emptyList(),
)

data class RouteUpdateEntry(
    val destination: ByteArray,
    val cost: Double,
    val sequenceNumber: UInt,
    val hopCount: UByte,
)

data class RouteUpdateMessage(
    val senderId: ByteArray,
    val entries: List<RouteUpdateEntry>,
    val signerPublicKey: ByteArray? = null,
    val signature: ByteArray? = null,
)

data class ResumeRequestMessage(
    val messageId: ByteArray,
    val bytesReceived: UInt,
    val extensions: List<TlvEntry> = emptyList(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ResumeRequestMessage) return false
        return messageId.contentEquals(other.messageId) &&
            bytesReceived == other.bytesReceived &&
            extensions == other.extensions
    }

    override fun hashCode(): Int {
        var result = messageId.contentHashCode()
        result = 31 * result + bytesReceived.hashCode()
        result = 31 * result + extensions.hashCode()
        return result
    }
}

data class HandshakeMessage(
    val step: UByte,
    val noiseMessage: ByteArray,
)

data class KeepaliveMessage(
    val flags: UByte,
    val timestampMillis: ULong,
    val extensions: List<TlvEntry> = emptyList(),
)

data class NackMessage(
    val messageId: ByteArray,
    val reason: NackReason = NackReason.UNKNOWN,
    val extensions: List<TlvEntry> = emptyList(),
)

enum class NackReason(val code: UByte) {
    UNKNOWN(0u),
    BUFFER_FULL(1u),
    UNKNOWN_DESTINATION(2u),
    DECRYPT_FAILED(3u),
    RATE_LIMITED(4u);

    companion object {
        fun fromCode(code: UByte): NackReason =
            entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

data class RouteRequestMessage(
    val origin: ByteArray,
    val destination: ByteArray,
    val requestId: UInt,
    val hopCount: UByte,
    val hopLimit: UByte,
    val extensions: List<TlvEntry> = emptyList(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RouteRequestMessage) return false
        return origin.contentEquals(other.origin) &&
            destination.contentEquals(other.destination) &&
            requestId == other.requestId &&
            hopCount == other.hopCount &&
            hopLimit == other.hopLimit &&
            extensions == other.extensions
    }

    override fun hashCode(): Int {
        var result = origin.contentHashCode()
        result = 31 * result + destination.contentHashCode()
        result = 31 * result + requestId.hashCode()
        result = 31 * result + hopCount.hashCode()
        result = 31 * result + hopLimit.hashCode()
        result = 31 * result + extensions.hashCode()
        return result
    }
}

data class RouteReplyMessage(
    val origin: ByteArray,
    val destination: ByteArray,
    val requestId: UInt,
    val hopCount: UByte,
    val extensions: List<TlvEntry> = emptyList(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RouteReplyMessage) return false
        return origin.contentEquals(other.origin) &&
            destination.contentEquals(other.destination) &&
            requestId == other.requestId &&
            hopCount == other.hopCount &&
            extensions == other.extensions
    }

    override fun hashCode(): Int {
        var result = origin.contentHashCode()
        result = 31 * result + destination.contentHashCode()
        result = 31 * result + requestId.hashCode()
        result = 31 * result + hopCount.hashCode()
        result = 31 * result + extensions.hashCode()
        return result
    }
}
