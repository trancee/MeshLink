package ch.trancee.meshlink.engine

import ch.trancee.meshlink.config.RegulatoryRegion
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.engine.internal.chunkTransferPayload
import ch.trancee.meshlink.engine.internal.isWithinInboundTransferSizeLimits
import ch.trancee.meshlink.engine.internal.powerPolicyMetadata
import ch.trancee.meshlink.engine.internal.preferredTransportModeForEncryptedFrame
import ch.trancee.meshlink.engine.internal.routeRemovalLabel
import ch.trancee.meshlink.engine.internal.routeRemovalStage
import ch.trancee.meshlink.engine.transfer.MAX_SUPPORTED_PAYLOAD_BYTES
import ch.trancee.meshlink.power.PowerPolicy
import ch.trancee.meshlink.power.PowerPolicyProfile
import ch.trancee.meshlink.power.PowerTier
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MeshEngineInternalModelsTest {
    @Test
    fun `chunkTransferPayload splits payload into fixed-size chunks`() {
        // Arrange
        val payload = "abcdefgh".encodeToByteArray()

        // Act
        val chunks = chunkTransferPayload(payload = payload, chunkSize = 3)

        // Assert
        assertEquals(3, chunks.size)
        assertContentEquals("abc".encodeToByteArray(), chunks[0])
        assertContentEquals("def".encodeToByteArray(), chunks[1])
        assertContentEquals("gh".encodeToByteArray(), chunks[2])
    }

    @Test
    fun `chunkTransferPayload rejects non-positive chunk sizes`() {
        // Arrange / Act / Assert
        assertFailsWith<IllegalStateException> { chunkTransferPayload(byteArrayOf(0x01), 0) }
    }

    @Test
    fun `isWithinInboundTransferSizeLimits accepts a consistent well-sized frame`() {
        // Arrange
        val frame = transferStartFrame(totalBytes = 1_000, maxChunkPayloadBytes = 392)

        // Act
        val withinLimits = frame.isWithinInboundTransferSizeLimits()

        // Assert
        assertEquals(3, frame.totalChunks)
        assertEquals(true, withinLimits)
    }

    @Test
    fun `isWithinInboundTransferSizeLimits rejects totalBytes above the supported payload limit`() {
        // Arrange
        val oversizedBytes = MAX_SUPPORTED_PAYLOAD_BYTES + 1
        val frame =
            transferStartFrame(
                totalBytes = oversizedBytes,
                maxChunkPayloadBytes = oversizedBytes,
                totalChunksOverride = 1,
            )

        // Act
        val withinLimits = frame.isWithinInboundTransferSizeLimits()

        // Assert
        assertEquals(false, withinLimits)
    }

    @Test
    fun `isWithinInboundTransferSizeLimits rejects totalChunks inflated beyond totalBytes`() {
        // Arrange
        val frame =
            transferStartFrame(totalBytes = 1, maxChunkPayloadBytes = 1, totalChunksOverride = 100)

        // Act
        val withinLimits = frame.isWithinInboundTransferSizeLimits()

        // Assert
        assertEquals(false, withinLimits)
    }

    @Test
    fun `isWithinInboundTransferSizeLimits rejects non-positive sizing fields`() {
        // Arrange / Act / Assert
        assertEquals(
            false,
            transferStartFrame(totalBytes = 0, maxChunkPayloadBytes = 392)
                .isWithinInboundTransferSizeLimits(),
        )
        assertEquals(
            false,
            transferStartFrame(totalBytes = 100, maxChunkPayloadBytes = 0, totalChunksOverride = 1)
                .isWithinInboundTransferSizeLimits(),
        )
        assertEquals(
            false,
            transferStartFrame(totalBytes = 100, maxChunkPayloadBytes = 50, totalChunksOverride = 0)
                .isWithinInboundTransferSizeLimits(),
        )
    }

    @Test
    fun `preferredTransportModeForEncryptedFrame prefers gatt for transfer acknowledgements`() {
        // Arrange
        val transferAck =
            WireFrame.TransferAck(
                transferId = "transfer-1",
                highestContiguousAck = 0,
                selectiveRanges = byteArrayOf(),
            )
        val message =
            WireFrame.Message(
                messageId = "message-1",
                originPeerId = ch.trancee.meshlink.api.PeerId("origin"),
                destinationPeerId = ch.trancee.meshlink.api.PeerId("destination"),
                priority = ch.trancee.meshlink.api.DeliveryPriority.NORMAL,
                ttlMillis = 1234,
                encryptedPayload = byteArrayOf(0x01),
            )

        // Act
        val ackMode = preferredTransportModeForEncryptedFrame(transferAck)
        val messageMode = preferredTransportModeForEncryptedFrame(message)

        // Assert
        assertEquals(TransportMode.GATT, ackMode)
        assertNull(messageMode)
    }

    @Test
    fun `route removal helpers distinguish expired from retracted routes`() {
        // Arrange / Act
        val expiredStage = routeRemovalStage("transport.peerLost", DiagnosticCode.ROUTE_EXPIRED)
        val expiredLabel = routeRemovalLabel(DiagnosticCode.ROUTE_EXPIRED)
        val retractedStage = routeRemovalStage("lifecycle.stop", DiagnosticCode.ROUTE_RETRACTED)
        val retractedLabel = routeRemovalLabel(DiagnosticCode.ROUTE_RETRACTED)

        // Assert
        assertEquals("transport.peerLost.routeExpired", expiredStage)
        assertEquals("expired", expiredLabel)
        assertEquals("lifecycle.stop.routeRetracted", retractedStage)
        assertEquals("retracted", retractedLabel)
    }

    @Test
    fun `powerPolicyMetadata includes clamp warnings only when present`() {
        // Arrange
        val policyWithWarnings =
            PowerPolicy(
                tier = PowerTier.PERFORMANCE,
                profile =
                    PowerPolicyProfile(
                        advertisementIntervalMillis = 300,
                        connectionIntervalMillis = 100,
                        scanDutyCyclePercent = 70,
                        maxConnections = 7,
                        chunkBudgetBytes = 4096,
                    ),
                region = RegulatoryRegion.EU,
                clampWarnings = listOf("scanDutyCycle clamped", "maxConnections clamped"),
            )
        val policyWithoutWarnings =
            PowerPolicy(
                tier = PowerTier.PERFORMANCE,
                profile =
                    PowerPolicyProfile(
                        advertisementIntervalMillis = 300,
                        connectionIntervalMillis = 100,
                        scanDutyCyclePercent = 70,
                        maxConnections = 7,
                        chunkBudgetBytes = 4096,
                    ),
                region = RegulatoryRegion.EU,
                clampWarnings = emptyList(),
            )

        // Act
        val withWarnings = powerPolicyMetadata(policyWithWarnings, level = 0.5f, isCharging = true)
        val withoutWarnings =
            powerPolicyMetadata(policyWithoutWarnings, level = 0.5f, isCharging = true)

        // Assert
        assertEquals(
            "scanDutyCycle clamped | maxConnections clamped",
            withWarnings["clampWarnings"],
        )
        assertNull(withoutWarnings["clampWarnings"])
        assertEquals("PERFORMANCE", withWarnings["tier"])
        assertEquals("EU", withWarnings["region"])
    }
}

private fun transferStartFrame(
    totalBytes: Int,
    maxChunkPayloadBytes: Int,
    totalChunksOverride: Int? = null,
): WireFrame.TransferStart {
    val totalChunks =
        totalChunksOverride
            ?: if (maxChunkPayloadBytes > 0) {
                (totalBytes + maxChunkPayloadBytes - 1) / maxChunkPayloadBytes
            } else {
                0
            }
    return WireFrame.TransferStart(
        route =
            WireFrame.TransferStartRoute(
                transferId = "transfer-1",
                messageId = "message-1",
                originPeerId = ch.trancee.meshlink.api.PeerId("origin"),
                destinationPeerId = ch.trancee.meshlink.api.PeerId("destination"),
            ),
        sizing =
            WireFrame.TransferStartSizing(
                totalBytes = totalBytes,
                totalChunks = totalChunks,
                maxChunkPayloadBytes = maxChunkPayloadBytes,
            ),
    )
}
