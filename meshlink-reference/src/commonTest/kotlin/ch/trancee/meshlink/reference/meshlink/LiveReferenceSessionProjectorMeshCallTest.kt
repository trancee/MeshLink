package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.ForgetPeerResult
import ch.trancee.meshlink.api.StartResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveReferenceSessionProjectorMeshCallTest {
    @Test
    fun `recordMeshCall appends a lifecycle event and tracks lifecycle outcomes`() {
        // Arrange
        val stateStore = referenceStateStore()
        val runtimeLogs = mutableListOf<String>()
        val projector =
            LiveReferenceSessionProjector(stateStore = stateStore, runtimeLogger = runtimeLogs::add)

        // Act
        projector.recordMeshCall(
            result = Result.success(StartResult.Started),
            successTitle = "Mesh started",
            successDetail = { result -> "mesh.start() -> $result" },
            errorTitle = "Mesh start failed",
        )

        // Assert
        val snapshot = stateStore.currentSnapshot
        val timelineEntry = snapshot.timeline.last()
        assertEquals("Mesh started", timelineEntry.title)
        assertEquals("mesh.start() -> Started", timelineEntry.detail)
        assertEquals("Started", snapshot.session.lastOutcomeSummary)
        assertTrue(runtimeLogs.isEmpty(), "Lifecycle projection should not emit runtime logs")
    }

    @Test
    fun `recordMeshCall leaves the last outcome unchanged for non lifecycle results`() {
        // Arrange
        val stateStore = referenceStateStore(lastOutcomeSummary = "Existing outcome")
        val projector = LiveReferenceSessionProjector(stateStore = stateStore)

        // Act
        projector.recordMeshCall(
            result = Result.success(ForgetPeerResult.Forgotten),
            successTitle = "Trust reset",
            successDetail = { result -> "forgetPeer() -> $result" },
            errorTitle = "Trust reset failed",
        )

        // Assert
        val snapshot = stateStore.currentSnapshot
        assertEquals("Existing outcome", snapshot.session.lastOutcomeSummary)
        assertEquals("Trust reset", snapshot.timeline.last().title)
    }

    @Test
    fun `recordMeshCall appends an error lifecycle event when the mesh call fails`() {
        // Arrange
        val stateStore = referenceStateStore()
        val projector = LiveReferenceSessionProjector(stateStore = stateStore)

        // Act
        projector.recordMeshCall(
            result = Result.failure(IllegalStateException("boom")),
            successTitle = "Mesh started",
            successDetail = { result: StartResult -> "mesh.start() -> $result" },
            errorTitle = "Mesh start failed",
        )

        // Assert
        val snapshot = stateStore.currentSnapshot
        val timelineEntry = snapshot.timeline.last()
        assertEquals("Mesh start failed", timelineEntry.title)
        assertEquals("boom", timelineEntry.detail)
        assertEquals("Mesh start failed", snapshot.session.lastOutcomeSummary)
    }
}
