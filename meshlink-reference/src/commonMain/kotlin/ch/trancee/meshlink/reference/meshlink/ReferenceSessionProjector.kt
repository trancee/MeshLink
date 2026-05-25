package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.ForgetPeerResult
import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.diagnostics.DiagnosticEvent

internal class LiveReferenceSessionProjector(
    private val stateStore: ReferenceControllerStateStore,
    private val runtimeLogger: (String) -> Unit = {},
) {
    internal fun <T : Any> recordMeshCall(
        result: Result<T>,
        successTitle: String,
        successDetail: (T) -> String,
        errorTitle: String,
    ): Unit {
        recordProjectedMeshCall(
            stateStore = stateStore,
            result = result,
            successTitle = successTitle,
            successDetail = successDetail,
            errorTitle = errorTitle,
        )
    }

    internal fun recordPeerTrustReset(peerId: String, result: ForgetPeerResult): Unit {
        recordProjectedPeerTrustReset(stateStore = stateStore, peerId = peerId, result = result)
    }

    internal fun recordPeerTrustResetFailure(peerId: String, error: Throwable): Unit {
        recordProjectedPeerTrustResetFailure(
            stateStore = stateStore,
            peerId = peerId,
            error = error,
        )
    }

    internal fun recordDiagnostic(event: DiagnosticEvent): Unit {
        recordProjectedDiagnostic(
            stateStore = stateStore,
            runtimeLogger = runtimeLogger,
            event = event,
        )
    }

    internal fun recordInboundMessage(message: InboundMessage): Unit {
        recordProjectedInboundMessage(
            stateStore = stateStore,
            runtimeLogger = runtimeLogger,
            message = message,
        )
    }
}
