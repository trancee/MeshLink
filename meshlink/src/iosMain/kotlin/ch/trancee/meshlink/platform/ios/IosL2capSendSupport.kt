package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportSendResult

internal class L2capSendContext internal constructor(internal val hintPeerId: PeerId)

internal interface L2capSendLink {
    val hintPeerId: PeerId

    suspend fun enqueue(payload: ByteArray): Boolean
}

internal class L2capSendDependencies
internal constructor(
    internal val currentLink: () -> L2capSendLink?,
    internal val ensureConnectAttempt: () -> Unit,
    internal val shouldInitiateL2cap: () -> Boolean,
    internal val closeLink: (String, String) -> Unit,
    internal val log: (String) -> Unit,
)

internal suspend fun sendViaIosL2capWhenReady(
    frame: OutboundFrame,
    context: L2capSendContext,
    dependencies: L2capSendDependencies,
): TransportSendResult {
    val link = dependencies.currentLink()
    return if (link == null) {
        if (dependencies.shouldInitiateL2cap()) {
            dependencies.ensureConnectAttempt()
            dependencies.log(
                "send(${context.hintPeerId.logSuffix()}) no active link, triggering connect"
            )
        } else {
            dependencies.log(
                "send(${context.hintPeerId.logSuffix()}) waiting for inbound L2CAP link"
            )
        }
        TransportSendResult.Dropped("iOS BLE L2CAP connection is not ready")
    } else {
        runCatching {
                if (!link.enqueue(frame.payload)) {
                    dependencies.closeLink(link.hintPeerId.value, "send queue closed")
                    return@runCatching TransportSendResult.Dropped(
                        "iOS BLE send queue is not accepting frames"
                    )
                }
                TransportSendResult.Delivered
            }
            .getOrElse { error ->
                dependencies.closeLink(
                    link.hintPeerId.value,
                    "send failed: ${error.message.orEmpty()}",
                )
                TransportSendResult.Dropped("iOS BLE send failed: ${error.message.orEmpty()}")
            }
    }
}
