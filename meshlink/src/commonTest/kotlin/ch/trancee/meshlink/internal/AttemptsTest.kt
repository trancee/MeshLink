package ch.trancee.meshlink.internal

import kotlin.test.Test
import kotlin.test.assertEquals

class AttemptsTest {
    private val attempts = Attempts()

    @Test
    fun eachIdCountsItsOwnAttemptsFromZero() {
        // Act
        val counted = listOf(attempts.next(id(1)), attempts.next(id(1)), attempts.next(id(2)), attempts.next(id(1)))

        // Assert
        assertEquals(listOf(0, 1, 0, 2), counted)
    }

    @Test
    fun theLeastRecentlyCountedIdIsForgottenBeyondCapacity() {
        // Arrange
        repeat(MessageIds.CAPACITY) { attempts.next(id(it)) }
        attempts.next(id(0))

        // Act
        attempts.next(id(MessageIds.CAPACITY))

        // Assert
        assertEquals(listOf(1, 2, 0), listOf(attempts.next(id(2)), attempts.next(id(0)), attempts.next(id(1))))
    }

    private fun id(n: Int) = ByteArray(12) { (n shr (8 * (it % 4))).toByte() }
}
