package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DiagnosticEvent.BearerFallback
import ch.trancee.meshlink.DiagnosticEvent.BusyReply
import ch.trancee.meshlink.DiagnosticEvent.ConnectFailed
import ch.trancee.meshlink.DiagnosticEvent.LinkClosed

/** The public reason of a failed connect. */
internal fun RadioFailure.connectReason(): ConnectFailed.Reason =
    when (this) {
        RadioFailure.INVALID_ARGUMENT -> ConnectFailed.Reason.InvalidArgument
        RadioFailure.PEER_UNREACHABLE -> ConnectFailed.Reason.PeerUnreachable
        RadioFailure.CAPABILITY_MISSING -> ConnectFailed.Reason.CapabilityMissing
        RadioFailure.LINK_CLOSED -> ConnectFailed.Reason.LinkClosed
        RadioFailure.UNSUPPORTED -> ConnectFailed.Reason.Unsupported
        RadioFailure.RADIO_ERROR -> ConnectFailed.Reason.RadioError
        RadioFailure.TRANSIENT -> ConnectFailed.Reason.Transient
    }

/**
 * The public reason a channel failed to open, or `null` if nothing fell back: a radio without L2CAP CoC
 * was never going to switch, and a channel never fails for a missing capability.
 */
internal fun RadioFailure.fallbackReason(): BearerFallback.Reason? =
    when (this) {
        RadioFailure.INVALID_ARGUMENT -> BearerFallback.Reason.InvalidArgument
        RadioFailure.PEER_UNREACHABLE -> BearerFallback.Reason.PeerUnreachable
        RadioFailure.LINK_CLOSED -> BearerFallback.Reason.LinkClosed
        RadioFailure.RADIO_ERROR -> BearerFallback.Reason.RadioError
        RadioFailure.TRANSIENT -> BearerFallback.Reason.Transient
        RadioFailure.UNSUPPORTED, RadioFailure.CAPABILITY_MISSING -> null
    }

/** The public reason a link closed: the reason it closed with, or [LinkClosed.Reason.ConnectionLost] for none. */
internal fun CloseReason?.closedReason(): LinkClosed.Reason =
    if (this == null) {
        LinkClosed.Reason.ConnectionLost
    } else {
        when (this) {
            CloseReason.NORMAL -> LinkClosed.Reason.Normal
            CloseReason.DUPLICATE_LINK -> LinkClosed.Reason.DuplicateLink
            CloseReason.PROTOCOL_ERROR -> LinkClosed.Reason.ProtocolError
            CloseReason.HANDSHAKE_FAILED -> LinkClosed.Reason.HandshakeFailed
            CloseReason.UNSUPPORTED_VERSION -> LinkClosed.Reason.UnsupportedVersion
            CloseReason.WRONG_MESH -> LinkClosed.Reason.WrongMesh
            CloseReason.RESOURCE_LIMIT -> LinkClosed.Reason.ResourceLimit
            CloseReason.SHUTTING_DOWN -> LinkClosed.Reason.ShuttingDown
        }
    }

/** The public reason of a `BUSY` reply. */
internal fun BusyReason.busyReason(): BusyReply.Reason =
    when (this) {
        BusyReason.REASSEMBLY_SLOTS -> BusyReply.Reason.Slots
        BusyReason.LARGE_BUDGET -> BusyReply.Reason.Budget
    }
