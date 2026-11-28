package ch.trancee.meshlink.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals

class CompileSdkTest {
    @Test
    fun minorReleaseKeepsItsMinorApiLevel() {
        // Act
        val sdk = CompileSdk.parse("37.2")

        // Assert
        assertEquals(CompileSdk(apiLevel = 37, minorApiLevel = 2), sdk)
    }

    @Test
    fun majorReleaseHasNoMinorApiLevel() {
        // Act
        val sdk = CompileSdk.parse("38")

        // Assert
        assertEquals(CompileSdk(apiLevel = 38, minorApiLevel = null), sdk)
    }
}
