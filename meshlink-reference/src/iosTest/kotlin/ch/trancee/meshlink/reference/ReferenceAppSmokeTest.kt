package ch.trancee.meshlink.reference

import kotlin.test.Test
import kotlin.test.assertNotNull

class ReferenceAppSmokeTest {
    @Test
    fun createsRootViewController() {
        val controller = createReferenceRootViewController()
        assertNotNull(controller)
    }
}
