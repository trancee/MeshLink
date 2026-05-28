package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import ch.trancee.meshlink.transport.shouldLocalPeerInitiateL2capConnection
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BleTransportInitiationPolicyTest {
    @Test
    fun localKeyHashInitiatesWhenItSortsBeforeRemoteKeyHash(): Unit {
        // Arrange
        val localKeyHash = byteArrayOf(0x01, 0x10)
        val remoteKeyHash = byteArrayOf(0x02, 0x00)

        // Act
        val shouldInitiate =
            shouldLocalPeerInitiateL2capConnection(
                localKeyHash = localKeyHash,
                localPlatformFamily = BleDiscoveryPlatformFamily.UNKNOWN,
                remoteKeyHash = remoteKeyHash,
                remotePlatformFamily = BleDiscoveryPlatformFamily.UNKNOWN,
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
            shouldLocalPeerInitiateL2capConnection(
                localKeyHash = localKeyHash,
                localPlatformFamily = BleDiscoveryPlatformFamily.UNKNOWN,
                remoteKeyHash = remoteKeyHash,
                remotePlatformFamily = BleDiscoveryPlatformFamily.UNKNOWN,
            )

        // Assert
        assertFalse(
            shouldInitiate,
            "Expected the lexicographically larger key hash to wait for the inbound link",
        )
    }
}
