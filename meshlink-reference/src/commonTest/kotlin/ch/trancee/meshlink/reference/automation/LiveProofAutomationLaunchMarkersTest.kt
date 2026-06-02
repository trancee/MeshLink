package ch.trancee.meshlink.reference.automation

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveProofAutomationLaunchMarkersTest {
    @Test
    fun startupMarkerIncludesLiveProofContext() {
        val marker =
            ReferenceAutomationConfig(
                    mode = ReferenceAutomationMode.LIVE_PROOF,
                    role = ReferenceAutomationRole.SENDER,
                    appId = "demo.meshlink.reference.live.test",
                    storageSubdirectory = "run-001",
                    scenario = ReferenceAutomationScenario.DIRECT_GUIDED,
                )
                .startupMarker()

        assertEquals(
            "REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=SENDER scenario=direct-guided appId=demo.meshlink.reference.live.test storage=run-001",
            marker,
        )
    }
}
