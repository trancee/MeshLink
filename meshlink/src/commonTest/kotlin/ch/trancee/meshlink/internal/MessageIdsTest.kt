package ch.trancee.meshlink.internal

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageIdsTest {
    private val ids = MessageIds()

    @Test
    fun anIdIsRememberedUntilItsTimeOnly() {
        // Act
        ids.put(messageId(1), untilMillis = 1_000)

        // Assert
        assertTrue(ids.has(messageId(1), nowMillis = 999))
        assertFalse(ids.has(messageId(1), nowMillis = 1_000))
        assertFalse(ids.has(messageId(2), nowMillis = 0))
    }

    @Test
    fun theOldestIdIsForgottenBeyondCapacityAndPuttingAgainMakesItNewest() {
        // Arrange
        repeat(MessageIds.CAPACITY) { ids.put(id(it), untilMillis = 1) }
        ids.put(id(0), untilMillis = 1)

        // Act
        ids.put(id(MessageIds.CAPACITY), untilMillis = 1)

        // Assert
        assertTrue(ids.has(id(0), nowMillis = 0))
        assertFalse(ids.has(id(1), nowMillis = 0))
        assertTrue(ids.has(id(2), nowMillis = 0))
        assertTrue(ids.has(id(MessageIds.CAPACITY), nowMillis = 0))
    }

    private fun id(n: Int) = ByteArray(12) { (n shr (8 * (it % 4))).toByte() }
}
