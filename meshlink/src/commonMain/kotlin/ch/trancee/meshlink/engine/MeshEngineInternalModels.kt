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

/**
 * A single-hop (directly connected peer) transport session.
 *
 * ### Nonce/sequence design (see `docs/explanation/hop-session-replay-protection.md` for the full
 * writeup, threat model, and rationale)
 *
 * Each hop-encrypted [DirectWireFrame.Data] frame carries an explicit, AAD-bound sequence number
 * (see [MeshEngineHopTransportSupport.encryptHopPayload]/[MeshEngineHopTransportSupport.decryptHopPayload]).
 * The sender's sequence counter is [sendNonce]; the receiver tracks the highest sequence number
 * seen so far ([receiveHighWaterMark]) plus a sliding bitmap of recently-seen sequence numbers
 * within the replay window ([receiveWindowBitmap]), following the same anti-replay design used by
 * WireGuard and IPsec ESP. This replaces an earlier design where both sides derived the AEAD nonce
 * from their own independently-incrementing, implicit counters: that design silently desynced
 * whenever the receiver dropped a frame before decrypting it (for example, because no session was
 * established yet), permanently breaking the session with `AEADBadTagException` on every
 * subsequent frame. Binding an explicit, authenticated sequence number to each frame and tracking
 * receipt with a proper replay window tolerates that kind of loss/reordering without weakening
 * replay protection -- see the design doc for the security analysis.
 */
internal class HopSession internal constructor(sendKey: ByteArray, receiveKey: ByteArray) {
    internal val sendKey: ByteArray = sendKey.copyOf()
    internal val receiveKey: ByteArray = receiveKey.copyOf()
    internal val outboundMutex: Mutex = Mutex()
    internal var sendNonce: ULong = 0u

    // Guards the inbound replay-check/decrypt/window-update sequence below so that two
    // DirectWireFrame.Data deliveries for this session (whether genuinely concurrent, or redundant
    // deliveries of the same wire frame over the GATT/L2CAP side-link transports) can never race on
    // the replay window state. Without this, two concurrent decrypts could both pass the replay
    // check for the same sequence number before either records it, corrupting the window and
    // potentially admitting a genuine replay.
    internal val inboundMutex: Mutex = Mutex()

    // Highest sequence number successfully authenticated and accepted so far, or null if no frame
    // has been accepted yet. Only ever updated (in decryptHopPayload) *after* a frame passes AEAD
    // authentication -- a frame that fails the tag check must never advance the window or mark its
    // sequence number as "seen", or a legitimate retransmission of that same sequence would be
    // wrongly rejected as a replay later.
    internal var receiveHighWaterMark: ULong? = null

    // Bitmap of the REPLAY_WINDOW_SIZE most recent sequence numbers at-or-below
    // receiveHighWaterMark that have already been accepted; bit N (0-indexed from the high water
    // mark) set means "receiveHighWaterMark - N has already been accepted". Sequence numbers older
    // than the window (i.e. more than REPLAY_WINDOW_SIZE behind the high water mark) are rejected
    // unconditionally as too-old replays.
    internal var receiveWindowBitmap: ULong = 0u
}

internal class PendingInitiatorHandshake
internal constructor(
    internal val manager: NoiseXXHandshakeManager,
    internal val sessionDeferred: CompletableDeferred<SessionEstablishmentOutcome>,
    // Monotonically increasing per-peer counter (see MeshEngineSessionRegistry) identifying which
    // initiator handshake attempt this is -- temporary diagnostic aid for correlating a
    // transport.handshake.message2.* diagnostic with the specific message1/manager attempt it
    // belongs to, to confirm whether a stale message2 from a superseded attempt (for example one
    // interrupted mid-flight by lifecycle pause/resume or a retry-after-timeout) is being fed into
    // a later, unrelated attempt's NoiseXXHandshakeManager instance.
    internal val attemptId: Long = 0L,
) {
    // Guards against two concurrent deliveries of message2 for the same pending handshake (the
    // same redundant GATT/L2CAP side-link transports that can duplicate other handshake frames
    // can duplicate message2 too) both calling manager.processMessage2AndCreateMessage3() on the
    // same NoiseXXHandshakeManager instance -- the second call throws InvalidStateTransition,
    // aborting the session entirely instead of harmlessly ignoring the duplicate. Set (under
    // MeshEngineSessionRegistry's sessionMutex, see tryBeginProcessingMessage2) before the first
    // caller invokes the manager, so a second concurrent caller can detect the race and back off.
    // Reset back to false once that call completes (success or failure) so a later, genuinely new
    // message2 delivery for the same still-pending attempt is not permanently blocked.
    internal var processing: Boolean = false

    // Set once a message2 payload has been cryptographically confirmed (via a successful Noise
    // AEAD decrypt) to be a stale/late reply belonging to this now-superseded attempt (see
    // MeshEngineInitiatorHandshakeSupport.supersededAttemptIdMatching). NoiseXXHandshakeManager's
    // internal transcript state advances irreversibly on a successful decrypt, so a *second*
    // delivery of the exact same stale payload (the same redundant transports that duplicate other
    // handshake frames can duplicate this one too) can no longer be verified by re-invoking the
    // manager. Recording the matched payload here lets a repeat delivery be recognized by a cheap
    // equality check instead.
    internal var matchedStaleMessage2Payload: ByteArray? = null
}

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
