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
    private val scope: CoroutineScope,
) {
    private var controller: ReferenceMeshLinkController =
        supportedControllerFactory(initialSurfaceOfOrigin)
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
        controller = supportedControllerFactory(surfaceOfOrigin)
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
        controller.close()
    }

    suspend fun run(action: suspend (ReferenceMeshLinkController) -> Unit): Unit {
        action(controller)
    }
}
