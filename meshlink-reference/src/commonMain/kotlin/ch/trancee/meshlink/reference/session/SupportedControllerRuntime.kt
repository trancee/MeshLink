package ch.trancee.meshlink.reference.session

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal class SupportedControllerRuntime(
    initialSurfaceOfOrigin: String,
    private val supportedControllerFactory: (String) -> ReferenceMeshLinkController,
    private val emitAutomationLog: (String) -> Unit = {},
    private val scope: CoroutineScope,
) {
    private var controller: ReferenceMeshLinkController =
        supportedControllerFactory(initialSurfaceOfOrigin).also { created ->
            emitAutomationLog(
                "REFERENCE_AUTOMATION supported.controller.created surface=$initialSurfaceOfOrigin type=${created::class.simpleName.orEmpty()}"
            )
        }
    private var controllerClosed: Boolean = false
    private var snapshotJob: Job? = null
    private var surfaceOfOrigin: String = initialSurfaceOfOrigin

    fun currentSnapshot(): ReferenceControllerSnapshot {
        return controller.snapshot.value.withSurfaceOfOrigin(surfaceOfOrigin)
    }

    fun bind(onSnapshotChanged: (ReferenceControllerSnapshot) -> Unit): Unit {
        snapshotJob?.cancel()
        snapshotJob = scope.launch {
            controller.snapshot.collect { nextSnapshot ->
                onSnapshotChanged(nextSnapshot.withSurfaceOfOrigin(surfaceOfOrigin))
            }
        }
    }

    suspend fun restart(
        surfaceOfOrigin: String,
        onSnapshotChanged: (ReferenceControllerSnapshot) -> Unit,
    ): ReferenceControllerSnapshot {
        closeCurrent()
        this.surfaceOfOrigin = surfaceOfOrigin
        val createdController = supportedControllerFactory(surfaceOfOrigin)
        emitAutomationLog(
            "REFERENCE_AUTOMATION supported.controller.created surface=$surfaceOfOrigin type=${createdController::class.simpleName.orEmpty()}"
        )
        controller = createdController
        controllerClosed = false
        val initialSnapshot = currentSnapshot()
        onSnapshotChanged(initialSnapshot)
        bind(onSnapshotChanged)
        return initialSnapshot
    }

    suspend fun end(nowProvider: () -> Long): ReferenceControllerSnapshot {
        val currentSnapshot = currentSnapshot()
        val endedSnapshot =
            currentSnapshot.copy(
                session = currentSnapshot.session.copy(endedAtEpochMillis = nowProvider())
            )
        closeCurrent()
        return endedSnapshot
    }

    suspend fun closeCurrent(): Unit {
        snapshotJob?.cancel()
        snapshotJob = null
        if (controllerClosed) {
            return
        }
        controller.close()
        controllerClosed = true
    }

    suspend fun run(action: suspend (ReferenceMeshLinkController) -> Unit): Unit {
        emitAutomationLog(
            "REFERENCE_AUTOMATION supported.controller.dispatch surface=$surfaceOfOrigin type=${controller::class.simpleName.orEmpty()}"
        )
        action(controller)
    }
}
