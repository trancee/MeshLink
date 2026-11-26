package ch.trancee.meshlink.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SuspensionsTest {
    @Test
    fun aCheckThirtySecondsLateOrMoreIsASuspensionOfThatLength() =
        runTest {
            // Arrange
            var slept = 0L
            val seen = mutableListOf<Long>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                suspensions { testScheduler.currentTime + slept }.collect { seen += it }
            }

            // Act
            advanceTimeBy(CHECK_MILLIS + 1)
            slept += JUST_UNDER_MILLIS
            advanceTimeBy(CHECK_MILLIS)
            slept += SUSPENDED_MILLIS
            advanceTimeBy(CHECK_MILLIS)

            // Assert
            assertEquals(listOf(SUSPENDED_MILLIS), seen)
        }

    private companion object {
        const val CHECK_MILLIS = 10_000L
        const val SUSPENDED_MILLIS = 30_000L
        const val JUST_UNDER_MILLIS = 29_999L
    }
}
