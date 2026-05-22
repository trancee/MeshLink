package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import ch.trancee.meshlink.transport.TransportMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosIncomingL2capHintSelectionTest {
    @Test
    fun returnsMappedHintWhenPeripheralIdentifierIsAlreadyKnown(): Unit {
        // Arrange
        val mappedHint = "abcdef123456"

        // Act
        val selectedHint =
            selectIncomingL2capHintPeerId(
                IncomingL2capHintSelectionRequest(
                    peripheralIdentifier = "known-id",
                    peerHintByIdentifier = mapOf("known-id" to mappedHint),
                    discoveredPeers = emptyList(),
                    ignoredHints =
                        IncomingL2capSelectionIgnoredHints(
                            activeHintIds = emptySet(),
                            pendingHintIds = emptySet(),
                        ),
                    localPeer =
                        IncomingL2capSelectionLocalPeer(
                            localKeyHash = byteArrayOf(0x7F, 0x10),
                            platformFamily = BleDiscoveryPlatformFamily.UNKNOWN,
                        ),
                )
            )

        // Assert
        assertEquals(mappedHint, selectedHint)
    }

    @Test
    fun returnsOnlyWaitingForInboundCandidateWhenIdentifierIsUnknown(): Unit {
        // Arrange
        val waitingCandidate =
            IncomingL2capHintCandidate(
                hintPeerIdValue = "waiting123456",
                keyHash = byteArrayOf(0x6A, 0x20),
                platformFamily = BleDiscoveryPlatformFamily.UNKNOWN,
                transportMode = TransportMode.L2CAP,
            )

        // Act
        val selectedHint =
            selectIncomingL2capHintPeerId(
                IncomingL2capHintSelectionRequest(
                    peripheralIdentifier = "incoming-id",
                    peerHintByIdentifier = emptyMap(),
                    discoveredPeers = listOf(waitingCandidate),
                    ignoredHints =
                        IncomingL2capSelectionIgnoredHints(
                            activeHintIds = emptySet(),
                            pendingHintIds = emptySet(),
                        ),
                    localPeer =
                        IncomingL2capSelectionLocalPeer(
                            localKeyHash = byteArrayOf(0x7F, 0x10),
                            platformFamily = BleDiscoveryPlatformFamily.UNKNOWN,
                        ),
                )
            )

        // Assert
        assertEquals(waitingCandidate.hintPeerIdValue, selectedHint)
    }

    @Test
    fun returnsNullWhenMultipleWaitingForInboundCandidatesExist(): Unit {
        // Arrange
        val firstWaitingCandidate =
            IncomingL2capHintCandidate(
                hintPeerIdValue = "waiting123456",
                keyHash = byteArrayOf(0x6A, 0x20),
                platformFamily = BleDiscoveryPlatformFamily.UNKNOWN,
                transportMode = TransportMode.L2CAP,
            )
        val secondWaitingCandidate =
            IncomingL2capHintCandidate(
                hintPeerIdValue = "waitingabcdef",
                keyHash = byteArrayOf(0x6B, 0x20),
                platformFamily = BleDiscoveryPlatformFamily.UNKNOWN,
                transportMode = TransportMode.L2CAP,
            )

        // Act
        val selectedHint =
            selectIncomingL2capHintPeerId(
                IncomingL2capHintSelectionRequest(
                    peripheralIdentifier = "incoming-id",
                    peerHintByIdentifier = emptyMap(),
                    discoveredPeers = listOf(firstWaitingCandidate, secondWaitingCandidate),
                    ignoredHints =
                        IncomingL2capSelectionIgnoredHints(
                            activeHintIds = emptySet(),
                            pendingHintIds = emptySet(),
                        ),
                    localPeer =
                        IncomingL2capSelectionLocalPeer(
                            localKeyHash = byteArrayOf(0x7F, 0x10),
                            platformFamily = BleDiscoveryPlatformFamily.UNKNOWN,
                        ),
                )
            )

        // Assert
        assertNull(selectedHint)
    }

    @Test
    fun ignoresCandidatesThatShouldInitiateOutboundConnections(): Unit {
        // Arrange
        val outboundInitiatorCandidate =
            IncomingL2capHintCandidate(
                hintPeerIdValue = "initiator1234",
                keyHash = byteArrayOf(0x80.toByte(), 0x10),
                platformFamily = BleDiscoveryPlatformFamily.UNKNOWN,
                transportMode = TransportMode.L2CAP,
            )

        // Act
        val selectedHint =
            selectIncomingL2capHintPeerId(
                IncomingL2capHintSelectionRequest(
                    peripheralIdentifier = "incoming-id",
                    peerHintByIdentifier = emptyMap(),
                    discoveredPeers = listOf(outboundInitiatorCandidate),
                    ignoredHints =
                        IncomingL2capSelectionIgnoredHints(
                            activeHintIds = emptySet(),
                            pendingHintIds = emptySet(),
                        ),
                    localPeer =
                        IncomingL2capSelectionLocalPeer(
                            localKeyHash = byteArrayOf(0x7F, 0x10),
                            platformFamily = BleDiscoveryPlatformFamily.UNKNOWN,
                        ),
                )
            )

        // Assert
        assertNull(selectedHint)
    }
}
