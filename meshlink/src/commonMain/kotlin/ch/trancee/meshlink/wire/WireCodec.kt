package ch.trancee.meshlink.wire

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId

internal sealed class WireFrame {
    internal class Hello
    internal constructor(public val peerId: PeerId, public val helloIntervalMillis: Int) :
        WireFrame()

    internal class Ihu
    internal constructor(public val peerId: PeerId, public val receiveCost: Int) : WireFrame()

    internal class RouteUpdateMetrics
    internal constructor(
        public val metric: Int,
        public val seqNo: Long,
        public val feasibilityMetric: Int,
    )

    internal class RouteUpdatePublicKeys
    internal constructor(
        destinationEd25519PublicKey: ByteArray,
        destinationX25519PublicKey: ByteArray,
    ) {
        internal val destinationEd25519PublicKey: ByteArray = destinationEd25519PublicKey.copyOf()
        internal val destinationX25519PublicKey: ByteArray = destinationX25519PublicKey.copyOf()
    }

    internal class RouteUpdate
    internal constructor(
        public val destinationPeerId: PeerId,
        public val nextHopPeerId: PeerId,
        metrics: RouteUpdateMetrics,
        publicKeys: RouteUpdatePublicKeys,
    ) : WireFrame() {
        public val metric: Int = metrics.metric
        public val seqNo: Long = metrics.seqNo
        public val feasibilityMetric: Int = metrics.feasibilityMetric
        public val destinationEd25519PublicKey: ByteArray =
            publicKeys.destinationEd25519PublicKey.copyOf()
        public val destinationX25519PublicKey: ByteArray =
            publicKeys.destinationX25519PublicKey.copyOf()
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

    internal class TransferStartRoute
    internal constructor(
        public val transferId: String,
        public val messageId: String,
        public val originPeerId: PeerId,
        public val destinationPeerId: PeerId,
    )

    internal class TransferStartSizing
    internal constructor(
        public val totalBytes: Int,
        public val totalChunks: Int,
        public val maxChunkPayloadBytes: Int,
    )

    internal class TransferStart
    internal constructor(route: TransferStartRoute, sizing: TransferStartSizing) : WireFrame() {
        public val transferId: String = route.transferId
        public val messageId: String = route.messageId
        public val originPeerId: PeerId = route.originPeerId
        public val destinationPeerId: PeerId = route.destinationPeerId
        public val totalBytes: Int = sizing.totalBytes
        public val totalChunks: Int = sizing.totalChunks
        public val maxChunkPayloadBytes: Int = sizing.maxChunkPayloadBytes
    }

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
            WireEnvelopeType.MESSAGE -> MessagePayloadCodec.decode(table)
            WireEnvelopeType.HELLO,
            WireEnvelopeType.IHU,
            WireEnvelopeType.ROUTE_UPDATE,
            WireEnvelopeType.ROUTE_RETRACTION,
            WireEnvelopeType.SEQNO_REQUEST,
            WireEnvelopeType.ROUTE_DIGEST -> RoutingPayloadCodec.decode(type, table)
            WireEnvelopeType.TRANSFER_START,
            WireEnvelopeType.TRANSFER_CHUNK,
            WireEnvelopeType.TRANSFER_ACK,
            WireEnvelopeType.TRANSFER_COMPLETE,
            WireEnvelopeType.TRANSFER_ABORT -> TransferPayloadCodec.decode(type, table)
        }
    }

    private fun encodePayload(frame: WireFrame): ByteArray {
        return when (frame) {
            is WireFrame.Message -> MessagePayloadCodec.encode(frame)
            is WireFrame.Hello,
            is WireFrame.Ihu,
            is WireFrame.RouteUpdate,
            is WireFrame.RouteRetraction,
            is WireFrame.SeqNoRequest,
            is WireFrame.RouteDigest -> RoutingPayloadCodec.encode(frame)
            is WireFrame.TransferStart,
            is WireFrame.TransferChunk,
            is WireFrame.TransferAck,
            is WireFrame.TransferComplete,
            is WireFrame.TransferAbort -> TransferPayloadCodec.encode(frame)
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
}
