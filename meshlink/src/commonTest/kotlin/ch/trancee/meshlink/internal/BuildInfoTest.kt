package ch.trancee.meshlink.internal

import kotlin.test.Test
import kotlin.test.assertTrue

class BuildInfoTest {
    @Test
    fun versionIsTheSemVerReleaseVersion() {
        // ADR 0019 decision 1: one SemVer version, from the root meshlink.version property.
        val semVer = Regex("""(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z.-]+)?""")

        assertTrue(semVer.matches(BuildInfo.VERSION), "not SemVer: ${BuildInfo.VERSION}")
    }
}
