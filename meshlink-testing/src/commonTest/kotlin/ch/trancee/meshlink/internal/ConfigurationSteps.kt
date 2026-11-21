package ch.trancee.meshlink.internal

import ch.trancee.meshlink.MeshLinkException
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.meshLinkConfig
import io.github.menjoo.cucumberkmp.core.runner.StepsBuilder
import io.github.menjoo.cucumberkmp.core.runner.then
import io.github.menjoo.cucumberkmp.core.runner.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

internal fun StepsBuilder.configurationSteps() {
    var failure: MeshLinkException.InvalidConfig? = null

    whenever("a configuration with an empty mesh ID is built") {
        try {
            meshLinkConfig(Preset.OfflineChat, SecurityProfile.Unsecured) { meshId = "" }.build()
        } catch (error: MeshLinkException.InvalidConfig) {
            failure = error
        }
    }

    then("the configuration is rejected for meshId") {
        assertEquals("meshId", assertNotNull(failure).setting)
    }
}
