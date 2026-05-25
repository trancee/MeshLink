package ch.trancee.meshlink.reference.session

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class JsonSessionHistoryRepositoryTest {
    @Test
    fun retainsOnlyMostRecentTwentySessions() = runTest {
        // Arrange
        val repository =
            JsonSessionHistoryRepository(documentStore = InMemoryReferenceDocumentStore())

        repeat(21) { index -> repository.retainSnapshot(referenceSnapshot(index)) }

        // Act
        val retained = repository.loadRetainedSessions()

        // Assert
        assertEquals(20, retained.size)
        assertEquals("session-20", retained.first().sessionId)
        assertEquals("session-1", retained.last().sessionId)
    }

    @Test
    fun retainSessionMarksTheStoredSessionAsRetained() = runTest {
        // Arrange
        val repository =
            JsonSessionHistoryRepository(documentStore = InMemoryReferenceDocumentStore())

        // Act
        repository.retainSession(referenceSession(sessionId = "session-retained", startedAt = 42L))
        val retained = repository.loadRetainedSessions()

        // Assert
        assertEquals(ReferenceHistoryStatus.RETAINED, retained.single().historyStatus)
        assertEquals("session-retained", retained.single().sessionId)
    }

    @Test
    fun deleteSessionRemovesTheStoredSnapshot() = runTest {
        // Arrange
        val repository =
            JsonSessionHistoryRepository(documentStore = InMemoryReferenceDocumentStore())
        repository.retainSnapshot(referenceSnapshot(1))

        // Act
        repository.deleteSession("session-1")
        val retainedSnapshot = repository.loadRetainedSnapshot("session-1")

        // Assert
        assertNull(retainedSnapshot)
    }
}

private fun referenceSnapshot(index: Int): ReferenceControllerSnapshot {
    return ReferenceControllerSnapshot(
        session = referenceSession(sessionId = "session-$index", startedAt = index.toLong()),
        peers = emptyList(),
        timeline = emptyList(),
        activePowerModeLabel = "Automatic",
    )
}

private fun referenceSession(sessionId: String, startedAt: Long): ReferenceSession {
    return ReferenceSession(
        sessionId = sessionId,
        scenarioId = "guided-first-exchange",
        authorityMode = ReferenceAuthorityMode.LIVE,
        startedAtEpochMillis = startedAt,
        historyStatus = ReferenceHistoryStatus.LIVE,
    )
}
