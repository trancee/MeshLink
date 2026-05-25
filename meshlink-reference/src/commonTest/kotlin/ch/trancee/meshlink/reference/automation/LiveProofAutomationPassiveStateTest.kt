package ch.trancee.meshlink.reference.automation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveProofAutomationPassiveStateTest {
    @Test
    fun passiveProofRetainAndExportOnlyAdvanceWhenPrerequisitesExist() {
        // Arrange
        val retainRequested = false
        val exportRequested = false

        // Act
        val retainReady =
            shouldRetainPassiveLiveProof(
                retainRequested = retainRequested,
                hasTrustEstablished = true,
                hasInboundMessage = true,
            )
        val retainBlocked =
            shouldRetainPassiveLiveProof(
                retainRequested = retainRequested,
                hasTrustEstablished = true,
                hasInboundMessage = false,
            )
        val exportReady =
            shouldExportPassiveLiveProof(
                retainRequested = true,
                exportRequested = exportRequested,
                retainedSessionCount = 1,
            )
        val exportBlocked =
            shouldExportPassiveLiveProof(
                retainRequested = false,
                exportRequested = exportRequested,
                retainedSessionCount = 1,
            )

        // Assert
        assertTrue(retainReady)
        assertFalse(retainBlocked)
        assertTrue(exportReady)
        assertFalse(exportBlocked)
    }
}
