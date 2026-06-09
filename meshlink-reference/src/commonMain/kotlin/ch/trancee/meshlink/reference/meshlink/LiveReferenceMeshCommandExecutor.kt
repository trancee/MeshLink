package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.api.PauseResult
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.ResumeResult
import ch.trancee.meshlink.api.StartResult
import ch.trancee.meshlink.api.StopResult
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity

/**
 * Centralizes live MeshLink command execution so binding, runtime calls, and app-facing side
 * effects stay local to one module.
 */
internal class LiveReferenceMeshCommandExecutor(
    private val runtime: LiveReferenceMeshRuntime,
    private val stateStore: ReferenceControllerStateStore,
    private val nowProvider: () -> Long,
    private val sessionProjector: LiveReferenceSessionProjector,
    private val sendRecorder: LiveReferenceSendRecorder,
) {
    suspend fun start(): Unit {
        stateStore.appendEvent(
            ReferenceTimelineEvent(
                family = TimelineFamily.LIFECYCLE,
                severity = TimelineSeverity.INFO,
                title = "Mesh start requested",
                detail = "mesh.start() requested from app coordinator",
            )
        )
        recordLifecycleCommand(
            successTitle = "Mesh started",
            successDetail = { result: StartResult -> "mesh.start() -> $result" },
            errorTitle = "Mesh start failed",
        ) {
            this.start()
        }
    }

    suspend fun pause(): Unit {
        recordLifecycleCommand(
            successTitle = "Mesh paused",
            successDetail = { result: PauseResult -> "mesh.pause() -> $result" },
            errorTitle = "Mesh pause failed",
        ) {
            this.pause()
        }
    }

    suspend fun resume(): Unit {
        recordLifecycleCommand(
            successTitle = "Mesh resumed",
            successDetail = { result: ResumeResult -> "mesh.resume() -> $result" },
            errorTitle = "Mesh resume failed",
        ) {
            this.resume()
        }
    }

    suspend fun stop(): Unit {
        recordLifecycleCommand(
            successTitle = "Mesh stopped",
            successDetail = { result: StopResult -> "mesh.stop() -> $result" },
            errorTitle = "Mesh stop failed",
        ) {
            this.stop()
        }
    }

    suspend fun sendPayload(peerId: String, payloadText: String, priority: DeliveryPriority): Unit {
        executeCommand(
            operation = {
                send(
                    peerId = PeerId(peerId),
                    payload = payloadText.encodeToByteArray(),
                    priority = priority,
                )
            },
            onSuccess = { result ->
                sendRecorder.recordOutcome(
                    peerId = peerId,
                    payloadText = payloadText,
                    priority = priority,
                    result = result,
                )
            },
            onFailure = { error ->
                sendRecorder.recordFailure(
                    peerId = peerId,
                    payloadText = payloadText,
                    error = error,
                )
            },
        )
    }

    suspend fun forgetPeer(peerId: String): Unit {
        executeCommand(
            operation = { forgetPeer(PeerId(peerId)) },
            onSuccess = { result -> sessionProjector.recordPeerTrustReset(peerId, result) },
            onFailure = { error -> sessionProjector.recordPeerTrustResetFailure(peerId, error) },
        )
    }

    private suspend fun <T> executeCommand(
        operation: suspend MeshLink.() -> T,
        onSuccess: (T) -> Unit = {},
        onFailure: (Throwable) -> Unit = {},
    ): Unit {
        val outcome =
            runtime.execute(
                stateStore = stateStore,
                nowProvider = nowProvider,
                sessionProjector = sessionProjector,
                operation = operation,
            )
        outcome.onSuccess(onSuccess).onFailure(onFailure)
    }

    private suspend fun <T : Any> recordLifecycleCommand(
        successTitle: String,
        successDetail: (T) -> String,
        errorTitle: String,
        operation: suspend MeshLink.() -> T,
    ): Unit {
        val outcome =
            runtime.execute(
                stateStore = stateStore,
                nowProvider = nowProvider,
                sessionProjector = sessionProjector,
                operation = operation,
            )
        sessionProjector.recordMeshCall(
            result = outcome,
            successTitle = successTitle,
            successDetail = successDetail,
            errorTitle = errorTitle,
        )
    }
}
