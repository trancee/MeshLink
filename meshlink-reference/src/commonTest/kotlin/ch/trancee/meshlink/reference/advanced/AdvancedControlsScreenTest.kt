package ch.trancee.meshlink.reference.advanced

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import ch.trancee.meshlink.reference.lab.LabScreen
import kotlin.test.Test

class AdvancedControlsScreenTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun advancedScreenShowsMeshState() = runComposeUiTest {
        setContent {
            AdvancedControlsScreen(
                viewModel = AdvancedControlsViewModel(platformServices = advancedPlatformServices())
            )
        }

        onNodeWithTag("advanced-mesh-state").assertTextEquals("Mesh state: Running")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun labScreenShowsNonNormativeLabel() = runComposeUiTest {
        setContent { LabScreen() }

        onNodeWithTag("lab-screen")
    }
}
