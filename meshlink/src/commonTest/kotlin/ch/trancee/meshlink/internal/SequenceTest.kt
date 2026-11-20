package ch.trancee.meshlink.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SequenceTest {
    @Test
    fun aSequenceIsNewerWithinHalfTheSpaceAheadAcrossTheWrap() {
        assertTrue(Sequence.newer(2u, 1u))
        assertTrue(Sequence.newer(1u, UInt.MAX_VALUE))
        assertTrue(Sequence.newer(HALF - 1u + 5u, 5u))
        assertFalse(Sequence.newer(5u, 5u))
        assertFalse(Sequence.newer(1u, 2u))
        assertFalse(Sequence.newer(UInt.MAX_VALUE, 1u))
    }

    @Test
    fun sequencesHalfTheSpaceApartAreNeitherNewer() {
        assertFalse(Sequence.newer(HALF + 5u, 5u))
        assertFalse(Sequence.newer(5u, HALF + 5u))
    }

    @Test
    fun theNextSequenceSkipsZero() {
        assertEquals(2u, Sequence.next(1u))
        assertEquals(1u, Sequence.next(UInt.MAX_VALUE))
    }

    private companion object {
        const val HALF = 0x8000_0000u
    }
}
