package ch.trancee.meshlink.platform.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

private const val TEST_MAX_ATTEMPTS = 3

class RetryWhileNullSupportTest {
    @Test
    fun returnsTheFirstNonNullResultWithoutRetryingWhenTheFirstAttemptSucceeds(): Unit =
        runBlocking {
            // Arrange
            var calls = 0
            val retryCalls = mutableListOf<Int>()

            // Act
            val result =
                retryWhileNull(
                    maxAttempts = TEST_MAX_ATTEMPTS,
                    delayMillis = 0,
                    onRetry = { attempt -> retryCalls += attempt },
                ) {
                    calls += 1
                    "ready"
                }

            // Assert
            assertEquals("ready", result)
            assertEquals(1, calls)
            assertEquals(emptyList(), retryCalls)
        }

    @Test
    fun retriesUntilANonNullResultArrivesWithinTheAttemptBudget(): Unit = runBlocking {
        // Arrange
        var calls = 0
        val retryCalls = mutableListOf<Int>()

        // Act: fails twice, succeeds on the third (last-allowed) attempt.
        val result =
            retryWhileNull(
                maxAttempts = TEST_MAX_ATTEMPTS,
                delayMillis = 0,
                onRetry = { attempt -> retryCalls += attempt },
            ) {
                calls += 1
                if (calls < TEST_MAX_ATTEMPTS) null else "ready"
            }

        // Assert
        assertEquals("ready", result)
        assertEquals(TEST_MAX_ATTEMPTS, calls)
        // onRetry is called once per failed-but-not-exhausted attempt: after attempt 1 and after
        // attempt 2, but not after the successful attempt 3.
        assertEquals(listOf(1, 2), retryCalls)
    }

    @Test
    fun givesUpAndReturnsNullAfterExhaustingTheAttemptBudget(): Unit = runBlocking {
        // Arrange
        var calls = 0
        val retryCalls = mutableListOf<Int>()

        // Act: never succeeds.
        val result =
            retryWhileNull(
                maxAttempts = TEST_MAX_ATTEMPTS,
                delayMillis = 0,
                onRetry = { attempt -> retryCalls += attempt },
            ) {
                calls += 1
                null
            }

        // Assert: exactly maxAttempts calls are made, and onRetry fires for every attempt except
        // the last (a failed final attempt gives up rather than scheduling another retry log).
        assertNull(result)
        assertEquals(TEST_MAX_ATTEMPTS, calls)
        assertEquals(listOf(1, 2), retryCalls)
    }

    @Test
    fun singleAttemptBudgetNeverRetries(): Unit = runBlocking {
        // Arrange
        var calls = 0

        // Act
        val result =
            retryWhileNull(maxAttempts = 1, delayMillis = 0) {
                calls += 1
                null
            }

        // Assert
        assertNull(result)
        assertEquals(1, calls)
    }
}
