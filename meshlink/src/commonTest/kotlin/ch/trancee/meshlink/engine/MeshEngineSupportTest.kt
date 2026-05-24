package ch.trancee.meshlink.engine

import ch.trancee.meshlink.config.RegulatoryRegion
import ch.trancee.meshlink.diagnostics.DiagnosticCode
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

class MeshEngineSupportTest {
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
