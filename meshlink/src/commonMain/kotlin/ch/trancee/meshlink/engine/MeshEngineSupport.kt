package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.power.PowerPolicy
import ch.trancee.meshlink.transfer.InboundChunkAcceptance
import ch.trancee.meshlink.transfer.InboundTransferSession
import ch.trancee.meshlink.transfer.OutboundTransferSession
import ch.trancee.meshlink.transfer.PreparedInboundTransferAck
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.wire.WireFrame

internal data class InlineRetryWakeupState(val attempt: Int, val topologyVersion: Long)

internal sealed class OutboundTransferPreparation {
    data object PendingRoute : OutboundTransferPreparation()

    class Ready internal constructor(internal val session: OutboundTransferSession) :
        OutboundTransferPreparation()

    class Failed internal constructor(internal val result: SendResult) :
        OutboundTransferPreparation()
}

internal data class LargeTransferSessionResolution(
    val session: OutboundTransferSession?,
    val lastRouteAvailable: Boolean,
    val result: SendResult? = null,
)

internal data class LargeTransferIterationResult(
    val lastRouteAvailable: Boolean,
    val transferProgressObserved: Boolean,
    val result: SendResult? = null,
)

internal data class LargeTransferLoopResult(
    val session: OutboundTransferSession?,
    val lastRouteAvailable: Boolean,
    val transferProgressObserved: Boolean = false,
    val result: SendResult? = null,
)

internal data class LargeTransferRetryWakeupState(val attempt: Int, val topologyVersion: Long)

internal fun SendResult.isRetryableInlineFailure(): Boolean {
    return this is SendResult.NotSent &&
        reason == ch.trancee.meshlink.api.SendFailureReason.UNREACHABLE
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
