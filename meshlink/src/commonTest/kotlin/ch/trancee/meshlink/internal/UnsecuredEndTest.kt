package ch.trancee.meshlink.internal

import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** An `unsecured` node's end of its messages (spec "Containers"). */
class UnsecuredEndTest {
    @Test
    fun anUnsecuredEndCarriesOnlyTheUnsecuredContainer() =
        runTest {
            // Arrange
            val end = UnsecuredEnd(SodiumCryptoProvider, SchedulerClock(testScheduler), Inbox(), {}) {}

            // Act
            val carried = listOf(Container.UNSECURED, Container.IK_INITIATOR, Container.TRANSPORT).map(end::carries)

            // Assert
            assertEquals(listOf(true, false, false), carried)
        }
}
