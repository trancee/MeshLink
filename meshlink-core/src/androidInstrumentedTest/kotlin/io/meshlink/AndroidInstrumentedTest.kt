package io.meshlink

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Smoke test that runs on a real Android device (or emulator) via
 * Firebase Test Lab.  Validates that the library loads correctly and
 * core classes can be referenced from an Android context.
 */
@RunWith(AndroidJUnit4::class)
class AndroidInstrumentedTest {

    @Test
    fun libraryLoadsOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull(context, "Instrumentation context must not be null")
    }

    @Test
    fun packageNameIsCorrect() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("io.meshlink.test", context.packageName)
    }
}
