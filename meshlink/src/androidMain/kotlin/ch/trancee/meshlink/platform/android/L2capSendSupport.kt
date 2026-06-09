package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

internal class L2capSendContext
internal constructor(
    internal val hintPeerId: PeerId,
    internal val transportMode: TransportMode,
    internal val advertisedL2capPsm: Int,
)

internal interface L2capSendLink {
    suspend fun send(frame: OutboundFrame): TransportSendResult
}

internal class L2capSendDependencies
internal constructor(
    internal val currentLink: () -> L2capSendLink?,
    internal val shouldInitiateL2cap: () -> Boolean,
    internal val triggerConnectIfNeeded: () -> Unit,
    internal val log: (String) -> Unit,
)

internal suspend fun sendViaL2capWhenReady(
    frame: OutboundFrame,
    context: L2capSendContext,
    dependencies: L2capSendDependencies,
): TransportSendResult {
    val directLink = dependencies.currentLink()
    if (context.transportMode != TransportMode.L2CAP) {
        dependencies.log("send(${context.hintPeerId.value.takeLast(6)}) dropped: peer is GATT-only")
        return TransportSendResult.Dropped("Android BLE GATT fallback transport is not implemented")
    }
    if (context.advertisedL2capPsm == 0 && directLink == null) {
        dependencies.log(
            "send(${context.hintPeerId.value.takeLast(6)}) no advertised PSM, waiting for inbound link"
        )
        return waitForConnectAndSend(frame = frame, context = context, dependencies = dependencies)
    }
    if (directLink == null) {
        if (!dependencies.shouldInitiateL2cap()) {
            dependencies.log(
                "send(${context.hintPeerId.value.takeLast(6)}) no active link, waiting for inbound link"
            )
            return waitForConnectAndSend(
                frame = frame,
                context = context,
                dependencies = dependencies,
            )
        }
        dependencies.log(
            "send(${context.hintPeerId.value.takeLast(6)}) no active link, triggering connect"
        )
        dependencies.triggerConnectIfNeeded()
        return waitForConnectAndSend(frame = frame, context = context, dependencies = dependencies)
    }
    return directLink.send(frame)
}

private suspend fun waitForConnectAndSend(
    frame: OutboundFrame,
    context: L2capSendContext,
    dependencies: L2capSendDependencies,
): TransportSendResult {
    repeat(20) { attempt ->
        val link = dependencies.currentLink()
        if (link != null) {
            return link.send(frame)
        }
        if (attempt < 19) {
            delay(25.milliseconds)
        }
    }
    return TransportSendResult.Dropped("Android BLE L2CAP connection is not ready")
}
