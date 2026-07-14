package ch.trancee.meshlink.engine.transport

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.engine.assembly.MeshEngineHardRunToken
import ch.trancee.meshlink.engine.assembly.MeshEngineRuntimeAwaitActiveResult
import ch.trancee.meshlink.engine.assembly.MeshEngineRuntimeGate
import ch.trancee.meshlink.engine.internal.DIAGNOSTIC_PEER_SUFFIX_LENGTH
import ch.trancee.meshlink.engine.internal.HopSession
import ch.trancee.meshlink.engine.internal.SessionEstablishmentOutcome
import ch.trancee.meshlink.engine.internal.preferredTransportModeForEncryptedFrame
import ch.trancee.meshlink.engine.routing.MeshEngineRoutingSupport
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.wire.WireCodec
import ch.trancee.meshlink.wire.WireFrame
import kotlinx.coroutines.sync.withLock

/**
 * Signals that [MeshEngineHopTransportSupport.decryptHopPayload] recognized the frame's sequence
 * number as one already accepted (or as older than the replay window), rather than a genuine
 * decrypt failure. This covers both a redundant delivery of an already-processed
 * DirectWireFrame.Data frame (see that function's documentation) and a genuine replay attempt.
 * Callers should treat this distinctly from a real authentication failure -- for example, by
 * silently dropping the frame instead of surfacing a decrypt-failure diagnostic.
 */
internal object ReplayedHopPayloadException :
    Exception("replayed or duplicate DirectWireFrame.Data sequence number ignored")

/**
 * Signals that [MeshEngineHopTransportSupport.decryptHopPayload] could not parse the hop frame
 * header (too short, or an unsupported [HOP_FRAME_VERSION]). Distinct from a genuine AEAD
 * authentication failure so diagnostics can tell the two apart.
 */
internal class HopFrameFormatException(message: String) : Exception(message)

internal class MeshEngineHopTransportSupport(
    private val localIdentity: LocalIdentity,
    private val runtimeGate: MeshEngineRuntimeGate,
    private val routingSupport: MeshEngineRoutingSupport,
    private val establishedHopSession: suspend (PeerId) -> HopSession?,
    private val ensureHopSession:
        suspend (PeerId, MeshEngineHardRunToken) -> SessionEstablishmentOutcome,
    private val sendDirectWireFrame:
        suspend (PeerId, DirectWireFrame, String, TransportMode?) -> TransportSendResult,
    private val emitDiagnostic:
        (
            DiagnosticCode,
            DiagnosticSeverity,
            String,
            String?,
            DiagnosticReason?,
            Map<String, String>,
        ) -> Unit,
) {
    suspend fun sendEncryptedWireFrame(
        peerId: PeerId,
        frame: WireFrame,
        action: String,
        hardRunToken: MeshEngineHardRunToken? = null,
    ): Boolean {
        val session =
            if (hardRunToken != null) {
                when (runtimeGate.awaitActive(hardRunToken)) {
                    MeshEngineRuntimeAwaitActiveResult.Active ->
                        (ensureHopSession(peerId, hardRunToken)
                                as? SessionEstablishmentOutcome.Established)
                            ?.session
                    MeshEngineRuntimeAwaitActiveResult.HardRunEnded -> null
                }
            } else {
                establishedHopSession(peerId)
            }
        val transportResult =
            if (session != null) {
                sendEncryptedWireFrameWithSession(
                    peerId = peerId,
                    session = session,
                    frame = frame,
                    action = action,
                )
            } else {
                null
            }

        return when (transportResult) {
            TransportSendResult.Delivered -> true
            is TransportSendResult.Dropped -> {
                emitHopSessionFailed(
                    peerId = peerId,
                    stage = "$action.send",
                    reason = DiagnosticReason.DELIVERY_FAILURE,
                )
                false
            }
            null -> false
        }
    }

    suspend fun sendEncryptedDirectWireFrame(
        peerId: PeerId,
        session: HopSession,
        frame: WireFrame,
        action: String,
    ): TransportSendResult {
        val encodedFrame = WireCodec.encode(frame)
        return session.outboundMutex.withLock {
            val encryptedFrame =
                encryptHopPayload(
                    sendKey = session.sendKey,
                    sequence = session.sendNonce,
                    plaintext = encodedFrame,
                )
            val sendResult =
                sendDirectWireFrame(
                    peerId,
                    DirectWireFrame.Data(encryptedFrame),
                    action,
                    preferredTransportModeForEncryptedFrame(frame),
                )
            if (sendResult is TransportSendResult.Delivered) {
                session.sendNonce += 1uL
            }
            sendResult
        }
    }

    // Every hop-encrypted frame carries its sender-declared sequence number as an explicit,
    // AAD-bound header (see encryptHopPayload) rather than relying on the receiver's own implicit
    // counter to guess the nonce that was used. This deliberately trades the previous design's
    // brittleness (any single frame dropped before decryption -- e.g. because no session existed
    // yet -- permanently desynchronized the two sides' counters, breaking every subsequent frame
    // with AEADBadTagException) for a WireGuard/IPsec-ESP-style sliding replay window that
    // tolerates loss and reordering while still rejecting genuine replays. See
    // docs/explanation/hop-session-replay-protection.md for the full threat model and rationale,
    // including why the sequence number MUST be authenticated (bound into the AEAD's AAD) rather
    // than left as a bare, tamperable plaintext header field.
    //
    // Hardware captures confirmed that a DirectWireFrame.Data frame can be delivered to this
    // session more than once for the same logical send -- either via the redundant GATT/L2CAP
    // side-link transports both having a ready channel, or via an app-level retry after the
    // sender's transport misreported a send as failed when it had actually reached the peer. Such
    // a retry re-encrypts the identical plaintext with the same key and sequence number, producing
    // byte-identical ciphertext; the replay window recognizes the repeated sequence number and
    // rejects it via ReplayedHopPayloadException regardless of whether the bytes are identical.
    // The whole replay-check/decrypt/window-update sequence runs under session.inboundMutex so two
    // deliveries -- redundant or genuinely concurrent -- can never race on the window state.
    suspend fun decryptHopPayload(session: HopSession, wireBytes: ByteArray): ByteArray {
        return session.inboundMutex.withLock {
            if (wireBytes.size < HOP_FRAME_HEADER_SIZE_BYTES) {
                throw HopFrameFormatException(
                    "hop frame too short: ${wireBytes.size} bytes, need at least " +
                        "$HOP_FRAME_HEADER_SIZE_BYTES"
                )
            }
            val header = wireBytes.copyOfRange(0, HOP_FRAME_HEADER_SIZE_BYTES)
            val version = header[0]
            if (version != HOP_FRAME_VERSION) {
                throw HopFrameFormatException("unsupported hop frame version $version")
            }
            val sequence = readSequence(header)
            val ciphertext = wireBytes.copyOfRange(HOP_FRAME_HEADER_SIZE_BYTES, wireBytes.size)

            // Check the replay window BEFORE attempting decryption, and only commit the sequence
            // number as "seen" AFTER a successful decrypt (see recordAcceptedSequence). A forged
            // or corrupted frame that fails authentication must never consume a window slot, or a
            // subsequent legitimate retransmission of that same sequence number would be wrongly
            // rejected as a replay.
            if (!session.isWithinReplayWindow(sequence)) {
                throw ReplayedHopPayloadException
            }
            val plaintext =
                localIdentity.cryptoProvider.chacha20Poly1305Open(
                    key = session.receiveKey,
                    nonce = hopNonce(sequence),
                    aad = header,
                    ciphertext = ciphertext,
                )
            session.recordAcceptedSequence(sequence)
            plaintext
        }
    }

    suspend fun emitHopSessionEstablished(peerId: PeerId, stage: String): Unit {
        emitDiagnostic(
            DiagnosticCode.HOP_SESSION_ESTABLISHED,
            DiagnosticSeverity.DEBUG,
            stage,
            peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
            DiagnosticReason.STATE_CHANGE,
            routingSupport.peerRouteMetadata(peerId),
        )
    }

    suspend fun emitHopSessionFailed(
        peerId: PeerId,
        stage: String,
        reason: DiagnosticReason,
        metadata: Map<String, String> = emptyMap(),
    ): Unit {
        emitDiagnostic(
            DiagnosticCode.HOP_SESSION_FAILED,
            severityForHopSessionFailedStage(stage),
            stage,
            peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
            reason,
            routingSupport.peerRouteMetadata(peerId, metadata = metadata),
        )
    }

    private suspend fun sendEncryptedWireFrameWithSession(
        peerId: PeerId,
        session: HopSession,
        frame: WireFrame,
        action: String,
    ): TransportSendResult? {
        return runCatching {
                sendEncryptedDirectWireFrame(
                    peerId = peerId,
                    session = session,
                    frame = frame,
                    action = action,
                )
            }
            .getOrElse { exception ->
                emitHopSessionFailed(
                    peerId = peerId,
                    stage = "$action.encrypt",
                    reason = DiagnosticReason.DELIVERY_FAILURE,
                    metadata = mapOf("cause" to exception::class.simpleName.orEmpty()),
                )
                null
            }
    }

    // Encodes the hop frame wire format: [version: 1 byte][sequence: 8 bytes little-endian][AEAD
    // ciphertext]. The version+sequence header is passed as the AEAD's associated data (AAD) so
    // it is cryptographically bound to the ciphertext -- an on-path relay cannot alter the
    // declared sequence number (e.g. to smuggle a captured old frame past the replay window, or to
    // move a frame outside its authenticated position) without invalidating the Poly1305 tag. See
    // docs/explanation/hop-session-replay-protection.md for why this matters.
    private fun encryptHopPayload(
        sendKey: ByteArray,
        sequence: ULong,
        plaintext: ByteArray,
    ): ByteArray {
        val header = hopFrameHeader(sequence)
        val ciphertext =
            localIdentity.cryptoProvider.chacha20Poly1305Seal(
                key = sendKey,
                nonce = hopNonce(sequence),
                aad = header,
                plaintext = plaintext,
            )
        return header + ciphertext
    }

    private fun hopFrameHeader(sequence: ULong): ByteArray {
        val header = ByteArray(HOP_FRAME_HEADER_SIZE_BYTES)
        header[0] = HOP_FRAME_VERSION
        repeat(NONCE_COUNTER_BYTES) { index ->
            header[HOP_FRAME_VERSION_SIZE_BYTES + index] =
                ((sequence shr (index * BITS_PER_BYTE)) and NONCE_BYTE_MASK).toByte()
        }
        return header
    }

    private fun readSequence(header: ByteArray): ULong {
        var sequence = 0uL
        repeat(NONCE_COUNTER_BYTES) { index ->
            val byteValue = header[HOP_FRAME_VERSION_SIZE_BYTES + index].toInt() and BYTE_MASK
            sequence = sequence or (byteValue.toULong() shl (index * BITS_PER_BYTE))
        }
        return sequence
    }

    private fun hopNonce(value: ULong): ByteArray {
        val nonce = ByteArray(NONCE_SIZE_BYTES)
        repeat(NONCE_COUNTER_BYTES) { index ->
            nonce[NONCE_PREFIX_BYTES + index] =
                ((value shr (index * BITS_PER_BYTE)) and NONCE_BYTE_MASK).toByte()
        }
        return nonce
    }

    private companion object {
        private const val NONCE_SIZE_BYTES: Int = 12
        private const val NONCE_COUNTER_BYTES: Int = 8
        private const val NONCE_PREFIX_BYTES: Int = 4
        private const val BITS_PER_BYTE: Int = 8
        private const val BYTE_MASK: Int = 0xFF
        private const val NONCE_BYTE_MASK: ULong = 0xFFu

        // See docs/explanation/hop-session-replay-protection.md. Bumping this would be required for
        // any future change to the hop frame's header layout; decryptHopPayload rejects any other
        // value as HopFrameFormatException rather than guessing at a format it doesn't recognize.
        private const val HOP_FRAME_VERSION: Byte = 1
        private const val HOP_FRAME_VERSION_SIZE_BYTES: Int = 1
        private const val HOP_FRAME_HEADER_SIZE_BYTES: Int =
            HOP_FRAME_VERSION_SIZE_BYTES + NONCE_COUNTER_BYTES
    }
}

// Returns true if `sequence` is new enough to still be eligible for acceptance: either ahead of
// the current high water mark, or within REPLAY_WINDOW_SIZE behind it and not already recorded as
// seen. Does NOT record `sequence` as seen -- callers must call recordAcceptedSequence after (and
// only after) the frame has passed AEAD authentication, so a forged/corrupted frame can never
// consume a legitimate sequence number's window slot.
private fun HopSession.isWithinReplayWindow(sequence: ULong): Boolean {
    val highWaterMark = receiveHighWaterMark ?: return true
    if (sequence > highWaterMark) {
        return true
    }
    val behind = highWaterMark - sequence
    if (behind >= REPLAY_WINDOW_SIZE.toULong()) {
        return false
    }
    val bit = 1uL shl behind.toInt()
    return receiveWindowBitmap and bit == 0uL
}

// Records `sequence` as accepted, advancing the high water mark and/or setting its bit in the
// window bitmap as appropriate. Must only be called after `sequence`'s frame has passed AEAD
// authentication, and while holding session.inboundMutex (see decryptHopPayload).
private fun HopSession.recordAcceptedSequence(sequence: ULong) {
    val highWaterMark = receiveHighWaterMark
    if (highWaterMark == null) {
        receiveHighWaterMark = sequence
        receiveWindowBitmap = 1uL
        return
    }
    if (sequence > highWaterMark) {
        val advance = sequence - highWaterMark
        receiveWindowBitmap =
            if (advance >= REPLAY_WINDOW_SIZE.toULong()) {
                1uL
            } else {
                (receiveWindowBitmap shl advance.toInt()) or 1uL
            }
        receiveHighWaterMark = sequence
    } else {
        val behind = highWaterMark - sequence
        receiveWindowBitmap = receiveWindowBitmap or (1uL shl behind.toInt())
    }
}

// Number of trailing sequence numbers (relative to the high water mark) tracked by the sliding
// replay window, matching the bit width of the ULong bitmap used to track them (see
// HopSession.receiveWindowBitmap). A sequence number more than this far behind the high water mark
// is rejected unconditionally as too old, regardless of whether it was actually seen before.
// WireGuard and IPsec ESP use the same window size for the same reason: large enough to absorb
// realistic reordering (here, redundant GATT/L2CAP side-link delivery of the same or nearby
// frames) without giving a replay attacker an impractically long horizon to reuse a captured
// frame. See docs/explanation/hop-session-replay-protection.md.
private const val REPLAY_WINDOW_SIZE: Int = 64

// Stage suffixes that represent a redundant/duplicate handshake frame being safely ignored (the
// same peer's message already succeeded, or a concurrent duplicate delivery of the same frame is
// already being processed) rather than a genuine handshake failure. MeshLink's redundant
// GATT/L2CAP side-link transports routinely deliver the same frame twice under normal, healthy
// operation, so these are expected/benign and would otherwise flood diagnostics/logs at WARN for
// conditions the mesh already recovers from automatically -- surfacing them at DEBUG keeps the
// signal-to-noise ratio of WARN-and-above diagnostics meaningful for conditions that actually need
// attention.
private val DUPLICATE_IGNORED_STAGE_SUFFIXES: List<String> =
    listOf("duplicateIgnored", "duplicateInFlightIgnored", "staleAttemptIgnored")

private fun severityForHopSessionFailedStage(stage: String): DiagnosticSeverity {
    return if (DUPLICATE_IGNORED_STAGE_SUFFIXES.any { stage.endsWith(it) }) {
        DiagnosticSeverity.DEBUG
    } else {
        DiagnosticSeverity.WARN
    }
}

internal fun buildMeshEngineRuntimeHopTransportSupport(
    localIdentity: LocalIdentity,
    runtimeGate: MeshEngineRuntimeGate,
    routingSupport: MeshEngineRoutingSupport,
    establishedHopSession: suspend (PeerId) -> HopSession?,
    ensureHopSession: suspend (PeerId, MeshEngineHardRunToken) -> SessionEstablishmentOutcome,
    sendDirectWireFrame:
        suspend (PeerId, DirectWireFrame, String, TransportMode?) -> TransportSendResult,
    emitDiagnostic:
        (
            DiagnosticCode,
            DiagnosticSeverity,
            String,
            String?,
            DiagnosticReason?,
            Map<String, String>,
        ) -> Unit,
): MeshEngineHopTransportSupport {
    return MeshEngineHopTransportSupport(
        localIdentity = localIdentity,
        runtimeGate = runtimeGate,
        routingSupport = routingSupport,
        establishedHopSession = establishedHopSession,
        ensureHopSession = ensureHopSession,
        sendDirectWireFrame = sendDirectWireFrame,
        emitDiagnostic = emitDiagnostic,
    )
}
