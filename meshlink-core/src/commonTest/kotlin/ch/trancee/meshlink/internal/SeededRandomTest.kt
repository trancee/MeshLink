package ch.trancee.meshlink.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SeededRandomTest {
    @Test
    fun sameSeedYieldsTheSameSequence() {
        assertEquals(draw(seed = 7), draw(seed = 7))
    }

    @Test
    fun anotherSeedYieldsAnotherSequence() {
        assertNotEquals(draw(seed = 7), draw(seed = 8))
    }

    @Test
    fun valuesStayInsideTheRange() {
        assertTrue(draw(seed = 7).all { it in -3 until 4 })
    }

    private fun draw(seed: Long): List<Int> {
        val random = SeededRandom(seed)
        return List(100) { random.nextInt(-3, 4) }
    }
}
