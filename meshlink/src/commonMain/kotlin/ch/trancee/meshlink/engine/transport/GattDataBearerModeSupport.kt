package ch.trancee.meshlink.engine.transport

import ch.trancee.meshlink.transport.GattDataBearerMode

/**
 * Shared, platform-independent bearer-selection policy for outbound direct-wire frames.
 *
 * Handshake and other control frames ([DirectWireFrame.HandshakeMessage1],
 * [DirectWireFrame.HandshakeMessage2], [DirectWireFrame.HandshakeMessage3], and any payload that
 * fails to decode as a [DirectWireFrame]) always resolve to [GattDataBearerMode.GATT_ONLY]: GATT is
 * the one bearer every known BLE peer pairing keeps available, so small, latency-sensitive control
 * traffic always uses it directly rather than racing an L2CAP link that may not exist yet or may be
 * mid-(re)negotiation.
 *
 * Regular data ([DirectWireFrame.Data]) frames resolve to
 * [GattDataBearerMode.L2CAP_PREFERRED_WITH_GATT_FALLBACK]: an already-connected L2CAP link is used
 * for its higher throughput when one exists, and GATT is used as the fallback otherwise.
 *
 * This was previously duplicated (and had drifted) between the Android and iOS platform adapters:
 * the iOS implementation gated GATT to data frames only, forcing handshake frames onto L2CAP_ONLY,
 * but the Android implementation had no such gate at all and would offer every frame type -
 * including in-flight handshake messages - to the GATT side-link whenever it was ready. When both
 * an L2CAP link and a GATT side-link were simultaneously active for the same peer (e.g. immediately
 * after both roles are relaunched together), a handshake message could be delivered to the
 * initiator over both bearers. The initiator processes the first delivery and clears its pending
 * handshake reservation; the duplicate delivery then has no matching reservation and is rejected as
 * `transport.handshake.message2.unexpected` (HOP_SESSION_FAILED), stalling the exchange. Resolving
 * every frame to exactly one bearer mode up front avoids this race entirely. See
 * docs/explanation/reference-app-physical-integration-findings.md for the full investigation.
 */
internal fun resolveGattDataBearerMode(directFrame: DirectWireFrame?): GattDataBearerMode {
    return if (directFrame is DirectWireFrame.Data) {
        GattDataBearerMode.L2CAP_PREFERRED_WITH_GATT_FALLBACK
    } else {
        GattDataBearerMode.GATT_ONLY
    }
}

// Shared log-line format for bearer-selection diagnostics, used identically by the Android and
// iOS transport adapters (BleTransportAdapter.sendToPeerUsingBearerPolicy and
// BleTransportAdapterSendRouting.sendToPeer) so a single "BEARER" grep across either platform's
// logs (or a combined benchmark capture) finds every bearer decision in the same shape. Logged
// once per outbound frame, before the frame is actually handed to a bearer implementation:
// `l2capLinkAlreadyConnected` reflects whether an already-connected L2CAP link existed for the
// peer *at decision time*, which is what determines whether L2CAP_PREFERRED_WITH_GATT_FALLBACK
// frames go out over L2CAP immediately or fall through to the GATT-first path. See
// docs/explanation/why-l2cap-first.md for why this distinction matters for benchmarking.
internal fun gattDataBearerDecisionLogLine(
    directFrame: DirectWireFrame?,
    bearerMode: GattDataBearerMode,
    l2capLinkAlreadyConnected: Boolean,
): String {
    val frameKind = directFrame?.let { it::class.simpleName } ?: "undecoded"
    return "BEARER decision frame=$frameKind mode=$bearerMode l2capReady=$l2capLinkAlreadyConnected"
}

// Companion result log line: logged once the frame has actually been handed to (and accepted by)
// one of the two bearer implementations, so the decision above can be matched against the bearer
// that was actually used (the GATT fallback path means the two can differ).
internal fun gattDataBearerResultLogLine(bearerUsed: String): String = "BEARER result=$bearerUsed"
