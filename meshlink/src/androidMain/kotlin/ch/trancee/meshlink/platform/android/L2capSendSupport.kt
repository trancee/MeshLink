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
    // See BleTransportAdapterScanSupport.onScanResult's identical use of
    // ch.trancee.meshlink.power.hasConnectionBudget: the discovery-driven auto-connect path was
    // gated against the tier's connection budget, but this send-triggered connect path called
    // triggerConnectIfNeeded() unconditionally -- a send to a freshly-discovered, not-yet-connected
    // peer could still spend a new connection slot even once the device was already at its budget
    // cap. This dependency closes that gap the same way: a peer this device would otherwise
    // locally initiate a connection for is deferred (not connected) once the budget is already
    // spent, exactly mirroring the discovery-time decision.
    internal val hasConnectionBudget: () -> Boolean,
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
        if (!dependencies.hasConnectionBudget()) {
            dependencies.log(
                "send(${context.hintPeerId.value.takeLast(6)}) connection budget exhausted, deferring connect"
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

private const val L2CAP_CONNECT_WAIT_ATTEMPTS: Int = 80
private const val L2CAP_CONNECT_WAIT_DELAY_MILLIS: Long = 25

private suspend fun waitForConnectAndSend(
    frame: OutboundFrame,
    context: L2capSendContext,
    dependencies: L2capSendDependencies,
): TransportSendResult {
    repeat(L2CAP_CONNECT_WAIT_ATTEMPTS) { attempt ->
        val link = dependencies.currentLink()
        if (link != null) {
            return link.send(frame)
        }
        if (attempt < L2CAP_CONNECT_WAIT_ATTEMPTS - 1) {
            delay(L2CAP_CONNECT_WAIT_DELAY_MILLIS.milliseconds)
        }
    }
    return TransportSendResult.Dropped("Android BLE L2CAP connection is not ready")
}
