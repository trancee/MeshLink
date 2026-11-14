package ch.trancee.meshlink.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class GattExchangeTest {
    private val exchange = GattExchange()
    private val owner = Any()

    @Test
    fun anAnswerWithNoRequestOutstandingDoesNotAnswerTheNextRequest() =
        runTest {
            // Arrange
            exchange.answer(GattAnswer(STALE))
            val reply = async { exchange.request { GattExchange.STARTED } }
            runCurrent()

            // Act
            exchange.answer(GattAnswer(FRESH))

            // Assert
            assertEquals(FRESH, reply.await()?.status)
        }

    @Test
    fun abandoningWithNoRequestOutstandingDoesNotFailTheNextRequest() =
        runTest {
            // Arrange
            exchange.abandon(owner)
            val reply = async { exchange.request(owner) { GattExchange.STARTED } }
            runCurrent()

            // Act
            exchange.answer(GattAnswer(FRESH))

            // Assert
            assertEquals(FRESH, reply.await()?.status)
        }

    @Test
    fun onlyTheOwnerOfTheOutstandingRequestAbandonsIt() =
        runTest {
            // Arrange
            val reply = async { exchange.request(owner) { GattExchange.STARTED } }
            runCurrent()

            // Act
            exchange.abandon(Any())
            runCurrent()
            val afterStranger = reply.isCompleted
            exchange.abandon(owner)

            // Assert
            assertFalse(afterStranger)
            assertNull(reply.await())
        }

    private companion object {
        const val STALE = 7
        const val FRESH = 3
    }
}
