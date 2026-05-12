package ch.trancee.meshlink.platform.ios

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosBleTransportInitiationPolicyTest {
    @Test
    fun localKeyHashInitiatesWhenItSortsBeforeRemoteKeyHash(): Unit {
        // Arrange
        val localKeyHash = byteArrayOf(0x01, 0x10)
        val remoteKeyHash = byteArrayOf(0x02, 0x00)

        // Act
        val shouldInitiate =
            shouldInitiateL2capConnection(
                localKeyHash = localKeyHash,
                remoteKeyHash = remoteKeyHash,
            )

        // Assert
        assertTrue(shouldInitiate, "Expected the lexicographically smaller key hash to initiate")
    }

    @Test
    fun localKeyHashWaitsForInboundLinkWhenRemoteKeyHashSortsFirst(): Unit {
        // Arrange
        val localKeyHash = byteArrayOf(0x7F, 0x10)
        val remoteKeyHash = byteArrayOf(0x6A, 0x20)

        // Act
        val shouldInitiate =
            shouldInitiateL2capConnection(
                localKeyHash = localKeyHash,
                remoteKeyHash = remoteKeyHash,
            )

        // Assert
        assertFalse(
            shouldInitiate,
            "Expected the lexicographically larger key hash to wait for the inbound link",
        )
    }
}
