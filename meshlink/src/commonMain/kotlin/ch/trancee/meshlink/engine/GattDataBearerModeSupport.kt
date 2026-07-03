package ch.trancee.meshlink.engine

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
