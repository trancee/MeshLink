package ch.trancee.meshlink.reference.session

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class JsonSessionHistoryRepositoryTest {
    @Test
    fun retainsOnlyMostRecentTwentySessions() = runTest {
        val repository =
            JsonSessionHistoryRepository(documentStore = InMemoryReferenceDocumentStore())

        repeat(21) { index ->
            repository.retainSnapshot(
                ReferenceControllerSnapshot(
                    session =
                        ReferenceSession(
                            sessionId = "session-$index",
                            scenarioId = "guided-first-exchange",
                            authorityMode = ReferenceAuthorityMode.LIVE,
                            startedAtEpochMillis = index.toLong(),
                            historyStatus = ReferenceHistoryStatus.RETAINED,
                        ),
                    peers = emptyList(),
                    timeline = emptyList(),
                    activePowerModeLabel = "Automatic",
                )
            )
        }

        val retained = repository.loadRetainedSessions()
        assertEquals(20, retained.size)
        assertEquals("session-20", retained.first().sessionId)
        assertEquals("session-1", retained.last().sessionId)
    }
}
