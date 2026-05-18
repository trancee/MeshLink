package ch.trancee.meshlink.reference.parity

import ch.trancee.meshlink.reference.navigation.ReferenceSurfaceId
import ch.trancee.meshlink.reference.navigation.ReferenceWorkflowCatalog
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkflowCatalogParityTest {
    @Test
    fun workflowCatalogMatchesExpectedSurfaceOrder() {
        val surfaces =
            ReferenceWorkflowCatalog.descriptors().map { descriptor -> descriptor.surfaceId }

        assertEquals(
            listOf(
                ReferenceSurfaceId.MAIN_GUIDED,
                ReferenceSurfaceId.SOLO_EXPLORATION,
                ReferenceSurfaceId.ADVANCED_CONTROLS,
                ReferenceSurfaceId.TECHNICAL_TIMELINE,
                ReferenceSurfaceId.LAB,
                ReferenceSurfaceId.RECENT_HISTORY,
            ),
            surfaces,
        )
    }
}
