package ch.trancee.meshlink.piolium

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.engine.MAX_SUPPORTED_PAYLOAD_BYTES
import ch.trancee.meshlink.engine.MeshEngineHardRunToken
import ch.trancee.meshlink.engine.MeshEngineInboundTransferSupport
import ch.trancee.meshlink.engine.MeshEngineInboundTransferSupportCallbacks
import ch.trancee.meshlink.transfer.InboundTransferSession
import ch.trancee.meshlink.wire.WireFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Confirm H2: inbound transfer metadata bypasses the documented 64 KiB payload limit. */
class H2InboundTransferSizeLimitBypassConfirm441bfd37Test {
    @Test
    fun test_confirm_inbound_transfer_size_limit_bypass_441bfd37() = runBlocking {
        withTimeout(60_000) {
            // Arrange
            val oversizedBytes = MAX_SUPPORTED_PAYLOAD_BYTES + 1
            val inboundTransfers = mutableMapOf<String, InboundTransferSession>()
            val acks = mutableListOf<WireFrame.TransferAck>()
            val diagnostics = mutableListOf<String>()
            var deliveredPayload: ByteArray? = null
            var deliveredOrigin: PeerId? = null
            var deliveredPriority: DeliveryPriority? = null
            val support =
                MeshEngineInboundTransferSupport(
                    inboundTransfers = inboundTransfers,
                    callbacks =
                        MeshEngineInboundTransferSupportCallbacks(
                            sendEncryptedWireFrame = { _, frame, _, _ ->
                                acks += frame as WireFrame.TransferAck
                                true
                            },
                            deliverInnerEnvelope = { _, originPeerId, payload, priority, _ ->
                                deliveredPayload = payload.copyOf()
                                deliveredOrigin = originPeerId
                                deliveredPriority = priority
                            },
                            routeMetadata = { _, metadata -> metadata },
                            emitDiagnostic = { _, _, stage, _, _, _ -> diagnostics += stage },
                        ),
                )
            val peerId = PeerId("oversized-upstream")
            val startFrame =
                WireFrame.TransferStart(
                    route =
                        WireFrame.TransferStartRoute(
                            transferId = "oversized-transfer",
                            messageId = "oversized-message",
                            originPeerId = PeerId("attacker-origin"),
                            destinationPeerId = PeerId("victim-destination"),
                        ),
                    sizing =
                        WireFrame.TransferStartSizing(
                            totalBytes = oversizedBytes,
                            totalChunks = 1,
                            maxChunkPayloadBytes = oversizedBytes,
                        ),
                )
            val hardRunToken = MeshEngineHardRunToken(epoch = 44)
            val chunk =
                WireFrame.TransferChunk(
                    transferId = startFrame.transferId,
                    chunkIndex = 0,
                    payload = ByteArray(oversizedBytes) { 0x41 },
                )

            // Act
            support.handleTransferStart(
                peerId = peerId,
                frame = startFrame,
                hardRunToken = hardRunToken,
            )
            val handled = support.handleTransferChunk(peerId = peerId, frame = chunk)
            val delivered = deliveredPayload

            // Assert
            assertTrue(handled, "The oversized inbound chunk should still be processed")
            assertNotNull(delivered, "An oversized inbound payload should have been delivered")
            assertEquals(oversizedBytes, delivered.size)
            assertEquals("attacker-origin", deliveredOrigin?.value)
            assertEquals(DeliveryPriority.NORMAL, deliveredPriority)
            assertEquals(2, acks.size)
            assertEquals(0, acks.last().highestContiguousAck)
            assertFalse(
                inboundTransfers.containsKey(startFrame.transferId),
                "The oversized transfer should complete normally",
            )
            assertTrue(diagnostics.contains("transfer.receive.start"))
            assertTrue(diagnostics.contains("transfer.receive.complete"))
            println(
                "CONFIRM H2 outboundLimit=$MAX_SUPPORTED_PAYLOAD_BYTES inboundTotalBytes=${startFrame.totalBytes} deliveredBytes=${delivered.size} ackCount=${acks.size}"
            )
        }
    }
}
