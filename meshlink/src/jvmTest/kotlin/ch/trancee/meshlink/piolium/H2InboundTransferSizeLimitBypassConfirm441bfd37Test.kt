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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Regression for H2: inbound transfer metadata used to bypass the documented 64 KiB payload limit.
 * [WireFrame.TransferStart.isWithinInboundTransferSizeLimits] now rejects any `TransferStart` whose
 * `totalBytes` exceeds [MAX_SUPPORTED_PAYLOAD_BYTES], or whose `totalChunks` is inconsistent with
 * `totalBytes`/`maxChunkPayloadBytes`, before an [InboundTransferSession] is ever allocated.
 */
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
            assertFalse(
                handled,
                "The oversized TransferStart should have been rejected, so there is no session " +
                    "to accept a chunk into",
            )
            assertNull(delivered, "An oversized inbound payload must never be delivered")
            assertEquals(null, deliveredOrigin)
            assertEquals(null, deliveredPriority)
            assertTrue(acks.isEmpty(), "No ack should be sent for a rejected TransferStart")
            assertFalse(
                inboundTransfers.containsKey(startFrame.transferId),
                "No session should be allocated for an oversized TransferStart",
            )
            assertTrue(
                diagnostics.contains("transfer.receive.start"),
                "A rejection diagnostic should still be emitted for the oversized TransferStart",
            )
            assertFalse(diagnostics.contains("transfer.receive.complete"))
            println(
                "CONFIRM H2 FIXED outboundLimit=$MAX_SUPPORTED_PAYLOAD_BYTES inboundTotalBytes=${startFrame.totalBytes} rejected=true"
            )
        }
    }
}
