package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DiagnosticEvent.BearerFallback
import ch.trancee.meshlink.DiagnosticEvent.ConnectFailed
import kotlin.test.Test
import kotlin.test.assertEquals

class DiagnosticReasonsTest {
    @Test
    fun everyRadioFailureHasItsPublicConnectReason() {
        // Act
        val reasons = RadioFailure.entries.associateWith { it.connectReason() }

        // Assert
        assertEquals(
            mapOf(
                RadioFailure.INVALID_ARGUMENT to ConnectFailed.Reason.InvalidArgument,
                RadioFailure.PEER_UNREACHABLE to ConnectFailed.Reason.PeerUnreachable,
                RadioFailure.CAPABILITY_MISSING to ConnectFailed.Reason.CapabilityMissing,
                RadioFailure.LINK_CLOSED to ConnectFailed.Reason.LinkClosed,
                RadioFailure.UNSUPPORTED to ConnectFailed.Reason.Unsupported,
                RadioFailure.RADIO_ERROR to ConnectFailed.Reason.RadioError,
                RadioFailure.TRANSIENT to ConnectFailed.Reason.Transient,
            ),
            reasons,
        )
    }

    @Test
    fun aChannelFailureFallsBackForEveryReasonButAMissingRadioOrCapability() {
        // Act
        val reasons = RadioFailure.entries.associateWith { it.fallbackReason() }

        // Assert
        assertEquals(
            mapOf(
                RadioFailure.INVALID_ARGUMENT to BearerFallback.Reason.InvalidArgument,
                RadioFailure.PEER_UNREACHABLE to BearerFallback.Reason.PeerUnreachable,
                RadioFailure.CAPABILITY_MISSING to null,
                RadioFailure.LINK_CLOSED to BearerFallback.Reason.LinkClosed,
                RadioFailure.UNSUPPORTED to null,
                RadioFailure.RADIO_ERROR to BearerFallback.Reason.RadioError,
                RadioFailure.TRANSIENT to BearerFallback.Reason.Transient,
            ),
            reasons,
        )
    }
}
