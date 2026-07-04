package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.crypto.NoiseXXHandshakeManager
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.power.PowerPolicy
import ch.trancee.meshlink.transfer.InboundChunkAcceptance
import ch.trancee.meshlink.transfer.InboundTransferSession
import ch.trancee.meshlink.transfer.OutboundTransferSession
import ch.trancee.meshlink.transfer.PreparedInboundTransferAck
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.wire.WireFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex

internal const val DIAGNOSTIC_PEER_SUFFIX_LENGTH: Int = 6

internal sealed class SessionEstablishmentOutcome {
    class Established internal constructor(internal val session: HopSession) :
        SessionEstablishmentOutcome()

    data object TrustFailure : SessionEstablishmentOutcome()

    data object Unreachable : SessionEstablishmentOutcome()
}

internal class HopSession internal constructor(sendKey: ByteArray, receiveKey: ByteArray) {
    internal val sendKey: ByteArray = sendKey.copyOf()
    internal val receiveKey: ByteArray = receiveKey.copyOf()
    internal val outboundMutex: Mutex = Mutex()
    internal var sendNonce: ULong = 0u
    // Guards the inbound decrypt-and-advance sequence below so that two DirectWireFrame.Data
    // deliveries for this session (whether genuinely concurrent, or redundant deliveries of the
    // same wire frame over the GATT/L2CAP side-link transports) can never race on receiveNonce.
    // Without this, two concurrent decrypts could both read the same receiveNonce value before
    // either advances it, corrupting the nonce sequence and causing the *next* legitimate frame
    // to fail decryption with AEADBadTagException.
    internal val inboundMutex: Mutex = Mutex()
    internal var receiveNonce: ULong = 0u
    // Ciphertext of the most recently successfully-decrypted DirectWireFrame.Data frame, checked
    // under inboundMutex so a redundant delivery of the exact same wire frame is recognized
    // atomically with the receiveNonce read/advance instead of racing it.
    internal var lastInboundCiphertext: ByteArray? = null
}

internal class PendingInitiatorHandshake
internal constructor(
    internal val manager: NoiseXXHandshakeManager,
    internal val sessionDeferred: CompletableDeferred<SessionEstablishmentOutcome>,
)

internal class PendingResponderHandshake
internal constructor(internal val manager: NoiseXXHandshakeManager)

internal sealed class OutboundTransferPreparation {
    data object PendingRoute : OutboundTransferPreparation()

    class Ready internal constructor(internal val session: OutboundTransferSession) :
        OutboundTransferPreparation()

    class Failed internal constructor(internal val result: SendResult) :
        OutboundTransferPreparation()
}

internal fun chunkTransferPayload(payload: ByteArray, chunkSize: Int): List<ByteArray> {
    check(chunkSize > 0) { "chunkSize must be positive" }
    val chunkCount = (payload.size + chunkSize - 1) / chunkSize
    return List(chunkCount) { chunkIndex ->
        val chunkStart = chunkIndex * chunkSize
        val chunkEnd = minOf(chunkStart + chunkSize, payload.size)
        payload.copyOfRange(chunkStart, chunkEnd)
    }
}

/**
 * Rejects [WireFrame.TransferStart] frames whose sizing fields would let a peer force an oversized
 * allocation before a single chunk byte has actually been received.
 *
 * [InboundTransferSession] eagerly allocates `totalChunks`-sized arrays up front, so `totalChunks`
 * must be consistent with `totalBytes` and `maxChunkPayloadBytes` (matching the same
 * ceiling-division chunking [chunkTransferPayload] uses on the sending side), and `totalBytes` must
 * not exceed [MAX_SUPPORTED_PAYLOAD_BYTES], the same limit already enforced on outbound sends.
 */
internal fun WireFrame.TransferStart.isWithinInboundTransferSizeLimits(): Boolean {
    if (totalBytes <= 0 || totalBytes > MAX_SUPPORTED_PAYLOAD_BYTES) {
        return false
    }
    if (maxChunkPayloadBytes <= 0) {
        return false
    }
    if (totalChunks <= 0 || totalChunks > totalBytes) {
        return false
    }
    val expectedTotalChunks = (totalBytes + maxChunkPayloadBytes - 1) / maxChunkPayloadBytes
    return totalChunks == expectedTotalChunks
}

internal fun inboundTransferChunkStage(acceptance: InboundChunkAcceptance): String {
    return if (acceptance.accepted) {
        "transfer.receive.chunk"
    } else {
        "transfer.receive.invalidChunk"
    }
}

internal fun inboundTransferChunkMetadata(acceptance: InboundChunkAcceptance): Map<String, String> {
    return mapOf(
        "accepted" to acceptance.accepted.toString(),
        "chunkBytes" to acceptance.payloadBytes.toString(),
        "chunkIndex" to acceptance.chunkIndex.toString(),
        "complete" to acceptance.complete.toString(),
        "duplicateChunk" to acceptance.duplicateChunk.toString(),
        "highestContiguousAck" to acceptance.highestContiguousAck.toString(),
        "newlyReceivedChunksSinceLastAck" to acceptance.newlyReceivedChunksSinceLastAck.toString(),
        "receivedChunks" to acceptance.receivedChunkCount.toString(),
        "shouldAcknowledge" to acceptance.shouldAcknowledge.toString(),
    )
}

internal fun inboundTransferMetadata(session: InboundTransferSession): Map<String, String> {
    return mapOf(
        "destinationPeerId" to session.destinationPeerId.value,
        "maxChunkPayloadBytes" to session.maxChunkPayloadBytes.toString(),
        "messageId" to session.messageId,
        "originPeerId" to session.originPeerId.value,
        "totalBytes" to session.totalBytes.toString(),
        "totalChunks" to session.totalChunks.toString(),
        "transferId" to session.transferId,
        "upstreamPeerId" to session.upstreamPeerId.value,
    )
}

internal fun inboundAckMetadata(
    preparedAck: PreparedInboundTransferAck,
    ackSent: Boolean,
): Map<String, String> {
    return mapOf(
        "ackContiguousChunks" to (preparedAck.highestContiguousAck + 1).coerceAtLeast(0).toString(),
        "ackHighestContiguous" to preparedAck.highestContiguousAck.toString(),
        "ackSent" to ackSent.toString(),
        "complete" to preparedAck.complete.toString(),
        "newlyReceivedChunksSinceLastAck" to preparedAck.newlyReceivedChunksSinceLastAck.toString(),
        "receivedChunks" to preparedAck.receivedChunkCount.toString(),
        "selectiveRangesBytes" to preparedAck.frame.selectiveRanges.size.toString(),
    )
}

internal fun routeRemovalStage(stage: String, removalCode: DiagnosticCode): String {
    return if (removalCode == DiagnosticCode.ROUTE_EXPIRED) {
        "$stage.routeExpired"
    } else {
        "$stage.routeRetracted"
    }
}

internal fun routeRemovalLabel(removalCode: DiagnosticCode): String {
    return if (removalCode == DiagnosticCode.ROUTE_EXPIRED) {
        "expired"
    } else {
        "retracted"
    }
}

internal fun preferredTransportModeForEncryptedFrame(frame: WireFrame): TransportMode? {
    return if (frame is WireFrame.TransferAck) TransportMode.GATT else null
}

internal fun powerPolicyMetadata(
    policy: PowerPolicy,
    level: Float,
    isCharging: Boolean,
): Map<String, String> {
    return buildMap {
        put("level", level.toString())
        put("isCharging", isCharging.toString())
        put("tier", policy.tier.name)
        put("advertisementIntervalMillis", policy.advertisementIntervalMillis.toString())
        put("connectionIntervalMillis", policy.connectionIntervalMillis.toString())
        put("scanDutyCyclePercent", policy.scanDutyCyclePercent.toString())
        put("maxConnections", policy.maxConnections.toString())
        put("chunkBudgetBytes", policy.chunkBudgetBytes.toString())
        put("region", policy.region.name)
        if (policy.clampWarnings.isNotEmpty()) {
            put("clampWarnings", policy.clampWarnings.joinToString(separator = " | "))
        }
    }
}
