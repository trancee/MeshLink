package ch.trancee.meshlink.reference.parity

import ch.trancee.meshlink.reference.navigation.ReferenceSurface
import ch.trancee.meshlink.reference.navigation.ReferenceWorkflowCatalog
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkflowCatalogParityTest {
    @Test
    fun workflowCatalogMatchesExpectedSurfaceOrder() {
        val surfaces =
            ReferenceWorkflowCatalog.descriptors().map { descriptor -> descriptor.surface }

        assertEquals(
            listOf(
                ReferenceSurface.MAIN_GUIDED,
                ReferenceSurface.SOLO_EXPLORATION,
                ReferenceSurface.ADVANCED_CONTROLS,
                ReferenceSurface.TECHNICAL_TIMELINE,
                ReferenceSurface.LAB,
                ReferenceSurface.RECENT_HISTORY,
            ),
            surfaces,
        )
    }
}
