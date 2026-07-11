package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.setenv
import platform.posix.unsetenv

/**
 * [BleTransportAdapter.shouldInitiateL2cap]'s normal key-hash tie-break behavior is already covered
 * by [BleTransportInitiationPolicyTest] against the pure `shouldLocalPeerInitiateL2capConnection`
 * function directly. This class instead covers the diagnostic-only [FORCE_L2CAP_INITIATOR_ENV]
 * override documented on that constant: it must force local initiation regardless of which side's
 * key hash would normally win the tie-break, and must not affect behavior at all when unset.
 */
@OptIn(ExperimentalForeignApi::class)
class BleTransportForceL2capInitiatorTest {
    @AfterTest
    fun tearDown(): Unit {
        unsetenv(FORCE_L2CAP_INITIATOR_ENV)
    }

    @Test
    fun defaultsToTheNormalKeyHashTieBreakWhenUnset(): Unit {
        // Arrange
        unsetenv(FORCE_L2CAP_INITIATOR_ENV)
        val adapter =
            BleTransportAdapter(
                appId = "test-app",
                advertisementKeyHash = localKeyHashThatNormallyLoses,
            )
        val remoteKeyHashThatWouldNormallyWin = remoteKeyHashThatNormallyWins

        // Act
        val shouldInitiate =
            adapter.shouldInitiateL2cap(
                remoteKeyHash = remoteKeyHashThatWouldNormallyWin,
                remotePlatformFamily = BleDiscoveryPlatformFamily.UNKNOWN,
            )

        // Assert
        assertFalse(
            shouldInitiate,
            "Expected the normal tie-break to still apply when the override is unset",
        )
    }

    @Test
    fun forcesLocalInitiationWhenSetEvenIfTheRemoteKeyHashWouldNormallyWin(): Unit {
        // Arrange
        setenv(FORCE_L2CAP_INITIATOR_ENV, "true", 1)
        val adapter =
            BleTransportAdapter(
                appId = "test-app",
                advertisementKeyHash = localKeyHashThatNormallyLoses,
            )
        val remoteKeyHashThatWouldNormallyWin = remoteKeyHashThatNormallyWins

        // Act
        val shouldInitiate =
            adapter.shouldInitiateL2cap(
                remoteKeyHash = remoteKeyHashThatWouldNormallyWin,
                remotePlatformFamily = BleDiscoveryPlatformFamily.UNKNOWN,
            )

        // Assert
        assertTrue(
            shouldInitiate,
            "Expected the diagnostic override to force local initiation regardless of key hash",
        )
    }

    private companion object {
        // BleDiscoveryContract requires exactly 12-byte key hashes; only the first byte needs to
        // differ to control the lexicographic tie-break exercised by these tests.
        private val localKeyHashThatNormallyLoses =
            byteArrayOf(0x7F, 0x10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        private val remoteKeyHashThatNormallyWins =
            byteArrayOf(0x6A, 0x20, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    }
}
