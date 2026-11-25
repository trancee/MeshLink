package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.SecurityProfile
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Small and large messages across a seeded 5-node line in every profile (spec "Size classes"): C, in
 * the middle, sends to E, two hops away; A and B lie behind C, away from any route to E.
 */
class LargeMessageLineTest {
    @Test
    fun inTheMediumClassAnUnsecuredLineCarriesEverySizeAndLargeOnesOnlyOnAFoundRoute() =
        runTest(timeout = 10.minutes) { crosses(SecurityProfile.Unsecured, medium = true) }

    @Test
    fun inTheMediumClassAClassicalLineCarriesEverySizeAndLargeOnesOnlyOnAFoundRoute() =
        runTest(timeout = 10.minutes) { crosses(SecurityProfile.Classical, medium = true) }

    @Test
    fun inTheMediumClassAPostQuantumLineCarriesEverySizeAndLargeOnesOnlyOnAFoundRoute() =
        runTest(timeout = 10.minutes) { crosses(SecurityProfile.PostQuantum, medium = true) }

    @Test
    fun inTheLowClassAnUnsecuredLineCarriesEverySizeAndNeverFloodsALargeOne() =
        runTest(timeout = 10.minutes) { crosses(SecurityProfile.Unsecured, medium = false) }

    @Test
    fun inTheLowClassAClassicalLineCarriesEverySizeAndNeverFloodsALargeOne() =
        runTest(timeout = 10.minutes) { crosses(SecurityProfile.Classical, medium = false) }

    @Test
    fun inTheLowClassAPostQuantumLineCarriesEverySizeAndNeverFloodsALargeOne() =
        runTest(timeout = 10.minutes) { crosses(SecurityProfile.PostQuantum, medium = false) }

    // C sends each payload in turn to E; each send's writes from C to B show whether it flooded.
    private suspend fun TestScope.crosses(
        profile: SecurityProfile,
        medium: Boolean,
    ) {
        val line = SecuredLine(SEED)
        try {
            // Arrange
            line.start(profile)
            if (medium) line.medium(C)
            val e = line.id(E)

            // Act
            val behind =
                PAYLOADS.map { size ->
                    line.send(e, text(size), from = C, settleMillis = SEND_MILLIS)
                    line.written(C, B, line.sentAtMillis)
                }

            // Assert: each arrives once, intact; only the small one floods behind C.
            val arrived = line.inboxes[E].map { it.payload.decodeToString() }
            assertEquals(PAYLOADS.map(::text), arrived)
            assertEquals(PAYLOADS.associate { text(it) to DeliveryStatus.Delivered }, line.latest)
            assertTrue(behind[0] > SMALL, "the small message floods behind C: $behind")
            assertTrue(behind.drop(1).all { it < SMALL_LARGE }, "no large message floods behind C: $behind")
        } finally {
            line.close()
        }
    }

    // A payload of [size] ASCII bytes, distinct for each size.
    private fun text(size: Int) = (size % ALPHABET).let { ('a' + it).toString().repeat(size) }

    private companion object {
        const val SEED = 50L
        const val B = 1
        const val C = 2
        const val E = 4
        const val SMALL = 4_096
        const val SMALL_LARGE = 4_097
        const val LARGEST = 131_072
        val PAYLOADS = listOf(SMALL, SMALL_LARGE, LARGEST)
        const val ALPHABET = 26

        /** Long enough for every copy of the largest message to cross two hops. */
        const val SEND_MILLIS = 30_000L
    }
}
