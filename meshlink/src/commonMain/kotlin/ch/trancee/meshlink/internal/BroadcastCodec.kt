package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DiagnosticEvent
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.ReceivedMessage

/**
 * The `BROADCAST` container of this node's profile (spec "BROADCAST"): the `unsecured` profile
 * writes and opens kind `0x04`, which names its sender unauthenticated; a [secured] profile writes
 * kind `0x05`, signed by the installation identity, and opens only a kind `0x05` whose signature
 * verifies, from the NodeId of its `origin_key`, and [diagnose]s every other kind `0x05`. Each frame
 * it writes gets a `message_id` from [random].
 */
internal class BroadcastCodec(
    private val crypto: CryptoProvider,
    private val secured: Boolean,
    private val diagnose: (DiagnosticEvent) -> Unit = {},
    private val random: (size: Int) -> ByteArray = crypto::randomBytes,
) {
    private lateinit var identity: InstallationIdentity

    /** Starts a run of the node with [identity]. */
    fun begin(identity: InstallationIdentity) {
        this.identity = identity
    }

    /**
     * This node's broadcast of [payload] with [hopLimit], expiring at Unix second [expiry], under a new
     * `message_id`.
     */
    fun write(
        hopLimit: Int,
        expiry: Long,
        payload: ByteArray,
    ): BroadcastFrame {
        val header = FloodHeader(hopLimit, random(MESSAGE_ID_LENGTH), expiry.toUInt())
        val origin = identity
        return if (secured) {
            BroadcastFrame.sign(crypto, header, origin.keyPair, payload)
        } else {
            BroadcastFrame(header, Container.Unsecured(origin.nodeId, payload))
        }
    }

    /** The message in [bytes] if they are a `BROADCAST` this profile takes; `null` if not. */
    fun open(bytes: ByteArray): ReceivedMessage? {
        val frame = (BroadcastFrame.decode(bytes) as? Decoded.Valid ?: return null).value
        return when (val container = frame.container) {
            is Container.Unsecured -> {
                ReceivedMessage(NodeId(container.senderNodeId), container.plaintext, broadcast = true)
                    .takeUnless { secured }
            }

            else -> {
                if (secured) signed(frame, container as Container.SignedBroadcast) else null
            }
        }
    }

    // The message in a kind `0x05` [frame] if its signature verifies; if not, the frame is diagnosed.
    private fun signed(
        frame: BroadcastFrame,
        container: Container.SignedBroadcast,
    ): ReceivedMessage? {
        val origin = frame.verifiedOrigin(crypto)
        if (origin == null) diagnose(DiagnosticEvent.BroadcastSignatureInvalid())
        return origin?.let { ReceivedMessage(NodeId(it), container.payload, broadcast = true) }
    }

    private companion object {
        const val MESSAGE_ID_LENGTH = 12
    }
}
