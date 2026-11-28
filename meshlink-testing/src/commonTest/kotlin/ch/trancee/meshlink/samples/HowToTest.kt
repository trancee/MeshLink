package ch.trancee.meshlink.samples

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshLinkConfig
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.MeshState
import ch.trancee.meshlink.PermissionKind
import ch.trancee.meshlink.Power
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.Requirement
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.SendResult
import ch.trancee.meshlink.internal.SeededRandom
import ch.trancee.meshlink.internal.TestClock
import ch.trancee.meshlink.internal.VirtualAir
import ch.trancee.meshlink.internal.VirtualPlatform
import ch.trancee.meshlink.meshLinkConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Runs each sample of Samples.kt as its how-to describes it, on VirtualAir.
@OptIn(InternalMeshLinkApi::class, ExperimentalCoroutinesApi::class)
class HowToTest {
    @Test
    fun choosePresetTakesTheBuiltInPresetsValues() {
        // Act
        val config = choosePreset()

        // Assert
        assertEquals(Preset.FieldTeams, config.preset)
        assertEquals(SecurityProfile.PostQuantum, config.securityProfile)
        assertEquals(3_600, config.messageLifetimeSeconds)
        assertTrue(config.readReceipts.send)
    }

    @Test
    fun customPresetOverridesItsBaseAndTheBlockOverridesThePreset() {
        // Act
        val config = customizePreset()

        // Assert
        assertEquals(CUSTOM_DESCRIPTION, config.describe())
        assertFalse(config.readReceipts.send)
        assertEquals(Power.Responsive, config.power)
    }

    @Test
    fun blockedNodeNamesWhatToAskAndRunsOnceThePermissionIsGranted() =
        runTest {
            // Arrange
            val air = air()
            val platform = air.platform(StandardTestDispatcher(testScheduler))
            platform.radio.report(setOf(Requirement.PermissionDenied(PermissionKind.BluetoothScan)))
            val node = MeshLink.create(chat(), MeshPlatform.of(platform))
            node.start()
            val blocked = prompts(node)
            val running = async { awaitRunning(node) }
            runCurrent()
            val returnedWhileBlocked = running.isCompleted

            // Act
            platform.radio.report(emptySet())
            runCurrent()

            // Assert
            assertEquals(listOf("Allow BluetoothScan for this app in Settings"), blocked)
            assertFalse(returnedWhileBlocked)
            assertTrue(running.isCompleted)
            assertEquals(emptyList(), prompts(node))
            node.close()
        }

    @Test
    fun messageThatAsksForAReadReceiptIsReadOnceBobMarksIt() =
        runTest {
            // Arrange
            val (alice, bob) = linkedPair()
            val inbox = async { readNextMessage(bob) }

            // Act
            val status = sendWithReadReceipt(alice, alice.onlyPeer())

            // Assert
            assertEquals("Are you safe?", inbox.await())
            assertEquals(DeliveryStatus.Read, status)
            alice.close()
            bob.close()
        }

    @Test
    fun fullQueueWaitsForRoomOnceAndThenAcceptsTheMessage() =
        runTest {
            // Arrange
            val (alice, bob) = linkedPair()
            val to = alice.onlyPeer()
            bob.stop()
            advanceTimeBy(SETTLE_MILLIS)
            repeat(FULL_QUEUE_COUNT) { alice.send(to, ByteArray(PAYLOAD_BYTES)) }

            // Act
            val sent = async { sendWhenThereIsRoom(alice, to, ByteArray(PAYLOAD_BYTES)) }
            runCurrent()
            val waiting = sent.isActive
            advanceTimeBy(EXPIRY_MILLIS)

            // Assert
            assertTrue(waiting)
            assertIs<SendResult.Accepted>(sent.await())
            alice.close()
            bob.close()
        }

    @Test
    fun broadcastIsSentAndBobReceivesIt() =
        runTest {
            // Arrange
            val (alice, bob) = linkedPair()
            val inbox = async { nextBroadcast(bob) }

            // Act
            val status = broadcastAlert(alice)

            // Assert
            assertEquals(DeliveryStatus.Sent, status)
            assertEquals("Water at the school", inbox.await())
            alice.close()
            bob.close()
        }

    @Test
    fun exportedReportHoldsTheEventsButNotTheMeshId() =
        runTest {
            // Arrange
            val (alice, bob) = linkedPair()

            // Act
            val report = exportDiagnostics(alice)

            // Assert
            assertTrue(report.contains("\"name\": \"LinkOpened\""), report)
            assertFalse(report.contains(MESH_ID), report)
            alice.close()
            bob.close()
        }

    private fun TestScope.air(): VirtualAir = VirtualAir(backgroundScope, TestClock(testScheduler), SeededRandom(1))

    private fun chat(): MeshLinkConfig {
        val builder = meshLinkConfig(Preset.OfflineChat, SecurityProfile.Classical) { meshId = MESH_ID }
        return builder.build()
    }

    private suspend fun TestScope.linkedPair(): Pair<MeshLink, MeshLink> {
        val air = air()
        val platforms: List<VirtualPlatform> = List(2) { air.platform(StandardTestDispatcher(testScheduler)) }
        val (alice, bob) = platforms.map { MeshLink.create(chat(), MeshPlatform.of(it)) }
        alice.start()
        bob.start()
        advanceTimeBy(SETTLE_MILLIS)
        return alice to bob
    }

    // Bob's NodeId, as Alice's only peer.
    private fun MeshLink.onlyPeer() = peers.value.single().nodeId

    private companion object {
        const val MESH_ID = "How-to mesh"
        const val SETTLE_MILLIS = 60_000L
        const val PAYLOAD_BYTES = 4_096

        // 4,096-byte payloads until the waiting bytes reach 1 MiB: the 256th reaches it.
        const val FULL_QUEUE_COUNT = 256

        // OfflineChat's 30-minute lifetime, the 30-second skew allowance and a second to spare.
        const val EXPIRY_MILLIS = (1_800L + 30L + 1L) * 1_000L

        // What docs/how-to/choose-and-customize-a-preset.md shows `describe()` printing.
        val CUSTOM_DESCRIPTION =
            """
            preset = Custom("Quiet chat", base = OfflineChat) (override)
            securityProfile = Classical (override)
            meshId = "Quiet chat" (override)
            meshKey = none (default)
            l2capUpgrade = true (default)
            diagnostics.debug = false (default)
            diagnostics.platformLog = false (default)
            density = Automatic (preset)
            power = Responsive (override)
            routedUnicast = true (preset)
            broadcastOrigination = true (preset)
            broadcastPriority = false (preset)
            holding = true (preset)
            messageLifetimeSeconds = 3600 (preset)
            readReceipts.requestByDefault = true (preset)
            readReceipts.send = false (preset)
            limits.maxUnicastPayload = 131072 (preset)
            limits.maxBroadcastPayload = 1024 (preset)
            """.trimIndent()
    }
}
