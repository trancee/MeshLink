package io.meshlink.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolVersionTest {

    @Test
    fun negotiatesMinAndRejectsMajorGap() {
        val v1_0 = ProtocolVersion(1, 0)
        val v1_2 = ProtocolVersion(1, 2)
        val v2_0 = ProtocolVersion(2, 0)
        val v3_0 = ProtocolVersion(3, 0)

        // Same major: negotiate min
        assertEquals(v1_0, v1_0.negotiate(v1_2))
        assertEquals(v1_0, v1_2.negotiate(v1_0))

        // Adjacent major: negotiate allowed
        assertEquals(v1_2, v1_2.negotiate(v2_0))

        // Major gap > 1: reject
        assertNull(v1_0.negotiate(v3_0))
        assertNull(v3_0.negotiate(v1_0))
    }

    // --- Batch 15 Cycle 4: compareTo ordering ---

    @Test
    fun compareToOrderingIsCorrect() {
        val v1_0 = ProtocolVersion(1, 0)
        val v1_2 = ProtocolVersion(1, 2)
        val v2_0 = ProtocolVersion(2, 0)
        val v2_1 = ProtocolVersion(2, 1)

        // Same version
        assertTrue(v1_0.compareTo(ProtocolVersion(1, 0)) == 0)

        // Minor version ordering
        assertTrue(v1_0 < v1_2, "1.0 < 1.2")
        assertTrue(v1_2 > v1_0, "1.2 > 1.0")

        // Major version ordering
        assertTrue(v1_2 < v2_0, "1.2 < 2.0")
        assertTrue(v2_0 > v1_2, "2.0 > 1.2")

        // Major takes precedence over minor
        assertTrue(v2_0 < v2_1, "2.0 < 2.1")

        // Sortable
        val sorted = listOf(v2_1, v1_0, v2_0, v1_2).sorted()
        assertEquals(listOf(v1_0, v1_2, v2_0, v2_1), sorted)
    }
}
