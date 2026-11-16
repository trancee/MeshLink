package ch.trancee.meshlink.internal

import kotlin.test.Test
import kotlin.test.assertTrue

class SystemRandomTest {
    @Test
    fun valuesStayInsideTheRangeAndVary() {
        val draws = List(100) { SystemRandom.nextInt(-3, 4) }

        assertTrue(draws.all { it in -3 until 4 })
        assertTrue(draws.toSet().size > 1)
    }
}
