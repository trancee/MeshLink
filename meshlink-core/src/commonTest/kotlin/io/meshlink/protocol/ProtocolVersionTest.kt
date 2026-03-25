package io.meshlink.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
