package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportSendResult
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

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
    // See BleTransportAdapterDiscoverySupport.handleL2capDiscovery's identical use of
    // ch.trancee.meshlink.power.hasConnectionBudget: the discovery-driven auto-connect path was
    // gated against the tier's connection budget (issue #101), but this send-triggered connect
    // path called ensureConnectAttempt() unconditionally -- a send to a freshly-discovered,
    // not-yet-connected peer could still spend a new connection slot even once the device was
    // already at its budget cap. This dependency closes that gap the same way: a peer this device
    // would otherwise locally initiate a connection for is deferred (not connected) once the
    // budget is already spent, exactly mirroring the discovery-time decision.
    internal val hasConnectionBudget: () -> Boolean,
    internal val closeLink: (String, String) -> Unit,
    internal val log: (String) -> Unit,
)

/**
 * Mirrors the Android L2CAP send path (see `platform.android.L2capSendSupport`): rather than
 * failing fast the instant no link exists yet, this waits up to
 * [L2CAP_CONNECT_WAIT_ATTEMPTS] * [L2CAP_CONNECT_WAIT_DELAY_MILLIS] (~2s) for CoreBluetooth to
 * finish connecting before dropping the frame. Without this, iOS drops sends far more eagerly than
 * Android for the same transient "still connecting" window.
 */
internal suspend fun sendViaL2capWhenReady(
    frame: OutboundFrame,
    context: L2capSendContext,
    dependencies: L2capSendDependencies,
): TransportSendResult {
    val directLink = dependencies.currentLink()
    if (directLink != null) {
        return sendViaLink(link = directLink, frame = frame, dependencies = dependencies)
    }
    if (!dependencies.shouldInitiateL2cap()) {
        dependencies.log("send(${context.hintPeerId.logSuffix()}) waiting for inbound L2CAP link")
    } else if (!dependencies.hasConnectionBudget()) {
        dependencies.log(
            "send(${context.hintPeerId.logSuffix()}) connection budget exhausted, deferring connect"
        )
    } else {
        dependencies.ensureConnectAttempt()
        dependencies.log(
            "send(${context.hintPeerId.logSuffix()}) no active link, triggering connect"
        )
    }
    return waitForConnectAndSend(frame = frame, dependencies = dependencies)
}

private const val L2CAP_CONNECT_WAIT_ATTEMPTS: Int = 80
private const val L2CAP_CONNECT_WAIT_DELAY_MILLIS: Long = 25

private suspend fun waitForConnectAndSend(
    frame: OutboundFrame,
    dependencies: L2capSendDependencies,
): TransportSendResult {
    repeat(L2CAP_CONNECT_WAIT_ATTEMPTS) { attempt ->
        val link = dependencies.currentLink()
        if (link != null) {
            return sendViaLink(link = link, frame = frame, dependencies = dependencies)
        }
        if (attempt < L2CAP_CONNECT_WAIT_ATTEMPTS - 1) {
            delay(L2CAP_CONNECT_WAIT_DELAY_MILLIS.milliseconds)
        }
    }
    return TransportSendResult.Dropped("iOS BLE L2CAP connection is not ready")
}

private suspend fun sendViaLink(
    link: L2capSendLink,
    frame: OutboundFrame,
    dependencies: L2capSendDependencies,
): TransportSendResult {
    return runCatching {
            if (!link.enqueue(frame.payload)) {
                dependencies.closeLink(link.hintPeerId.value, "send queue closed")
                return@runCatching TransportSendResult.Dropped(
                    "iOS BLE send queue is not accepting frames"
                )
            }
            TransportSendResult.Delivered
        }
        .getOrElse { error ->
            dependencies.closeLink(link.hintPeerId.value, "send failed: ${error.message.orEmpty()}")
            TransportSendResult.Dropped("iOS BLE send failed: ${error.message.orEmpty()}")
        }
}
