package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.wire.decodeLinkIdentityPeerIdOrNull

internal class UnknownGattWriteProcessingResult
internal constructor(
    internal val accepted: Boolean,
    internal val claimedHintPeerIdValue: String?,
    decodedFrames: List<ByteArray>,
) {
    internal val decodedFrames: List<ByteArray> = decodedFrames.map(ByteArray::copyOf)
}

internal fun processUnknownGattWriteChunks(
    identifier: String,
    chunks: List<ByteArray>,
    buffer: L2capFrameBuffer,
    peerBindings: PeerBindings,
    log: (String) -> Unit,
): UnknownGattWriteProcessingResult {
    var claimedHintPeerIdValue =
        peerBindings.hintForIdentifier(identifier)
            ?: peerBindings.temporaryHintForIdentifier(identifier)
    val decodedFrames = mutableListOf<ByteArray>()

    chunks.forEach { chunk ->
        val frames = buffer.append(chunk)
        frames.forEach { frame ->
            if (claimedHintPeerIdValue == null) {
                val claimedPeerId = decodeLinkIdentityPeerIdOrNull(frame)
                if (claimedPeerId == null) {
                    return UnknownGattWriteProcessingResult(
                        accepted = false,
                        claimedHintPeerIdValue = null,
                        decodedFrames = emptyList(),
                    )
                }
                // The claim is intentionally cleartext and unauthenticated. It only lets the local
                // transport bind this BLE central identifier to an already-known logical peer hint;
                // the real confidentiality/integrity boundary still lives in the hop-session keys.
                peerBindings.bindHintToIdentifier(identifier, claimedPeerId.value)
                claimedHintPeerIdValue = claimedPeerId.value
                log(
                    "bound GATT peripheral connection id=$identifier to claimed peerId=${claimedPeerId.value} via LinkIdentity"
                )
            } else {
                decodedFrames += frame
            }
        }
    }

    return UnknownGattWriteProcessingResult(
        accepted = true,
        claimedHintPeerIdValue = claimedHintPeerIdValue,
        decodedFrames = decodedFrames,
    )
}
