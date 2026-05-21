package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.wire.WireCodec
import ch.trancee.meshlink.wire.WireFrame
import kotlinx.coroutines.sync.withLock

internal class MeshEngineHopTransportSupport(
    private val localIdentity: LocalIdentity,
    private val routingSupport: MeshEngineRoutingSupport,
    private val establishedHopSession: suspend (PeerId) -> HopSession?,
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
    suspend fun sendEncryptedWireFrame(peerId: PeerId, frame: WireFrame, action: String): Boolean {
        val session = establishedHopSession(peerId)
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
                    sendNonce = session.sendNonce,
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

    fun decryptHopPayload(session: HopSession, ciphertext: ByteArray): ByteArray {
        val plaintext =
            localIdentity.cryptoProvider.chacha20Poly1305Open(
                key = session.receiveKey,
                nonce = hopNonce(session.receiveNonce),
                aad = byteArrayOf(),
                ciphertext = ciphertext,
            )
        session.receiveNonce += 1u
        return plaintext
    }

    fun emitHopSessionEstablished(peerId: PeerId, stage: String): Unit {
        emitDiagnostic(
            DiagnosticCode.HOP_SESSION_ESTABLISHED,
            DiagnosticSeverity.DEBUG,
            stage,
            peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
            DiagnosticReason.STATE_CHANGE,
            routingSupport.peerRouteMetadata(peerId),
        )
    }

    fun emitHopSessionFailed(
        peerId: PeerId,
        stage: String,
        reason: DiagnosticReason,
        metadata: Map<String, String> = emptyMap(),
    ): Unit {
        emitDiagnostic(
            DiagnosticCode.HOP_SESSION_FAILED,
            DiagnosticSeverity.WARN,
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

    private fun encryptHopPayload(
        sendKey: ByteArray,
        sendNonce: ULong,
        plaintext: ByteArray,
    ): ByteArray {
        return localIdentity.cryptoProvider.chacha20Poly1305Seal(
            key = sendKey,
            nonce = hopNonce(sendNonce),
            aad = byteArrayOf(),
            plaintext = plaintext,
        )
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
        private const val NONCE_BYTE_MASK: ULong = 0xFFu
    }
}
