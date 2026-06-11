package ch.trancee.meshlink.reference

import ch.trancee.meshlink.reference.platform.createPlatformServices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest

class ReferenceAppSmokeTest {
    @Test
    fun createsRootViewController() {
        val controller = createReferenceRootViewController()
        assertNotNull(controller)
    }

    @Test
    fun retainsArtifactsThroughInjectedPlatformServices() = runTest {
        val platformServices =
            createPlatformServices(
                documentsDirectory = "/tmp/meshlink-reference-smoke",
                nowProvider = { 1234L },
            )

        platformServices.documentStore.writeText("retained/smoke.json", "{\"state\":\"ok\"}")

        val stored = platformServices.documentStore.readText("retained/smoke.json")

        assertEquals("{\"state\":\"ok\"}", stored)
    }
}
