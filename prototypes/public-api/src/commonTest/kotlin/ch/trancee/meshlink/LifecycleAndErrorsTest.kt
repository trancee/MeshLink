package ch.trancee.meshlink

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class LifecycleAndErrorsTest {
    private val config = meshLinkConfig(Preset.OfflineChat, SecurityProfile.Classical) { meshId = "lifecycle" }

    @Test
    fun start_withBluetoothOff_isBlockedUntilBluetoothIsSwitchedOn() = meshTest { fake ->
        val phone = fake.addHost("phone")
        phone.setBluetoothOn(false)
        val node = MeshLink.create(config, phone)
        node.start()
        val blocked = node.state.value

        phone.setBluetoothOn(true)

        assertEquals(MeshState.Blocked(setOf(Requirement.BluetoothOff)), blocked)
        assertEquals(MeshState.Running, node.state.value)
    }

    @Test
    fun start_withPermissionDenied_reportsTheMissingPermissionKind() = meshTest { fake ->
        val phone = fake.addHost("phone")
        phone.setPermissionGranted(PermissionKind.NearbyDevices, granted = false)
        val node = MeshLink.create(config, phone)

        node.start()

        assertEquals(MeshState.Blocked(setOf(Requirement.PermissionDenied(PermissionKind.NearbyDevices))), node.state.value)
    }

    @Test
    fun send_whileStopped_isRejectedWithNotRunning() = meshTest { fake ->
        val node = MeshLink.create(config, fake.addHost("phone"))

        val result = node.send(fake.addHost("other").nodeId, byteArrayOf(1), SendOptions.Default)

        assertEquals(RejectionReason.NotRunning, assertIs<SendResult.Rejected>(result).reason)
    }

    @Test
    fun build_meshKeyWithoutSecuredProfile_throwsInvalidConfig() {
        val error = assertFailsWith<MeshLinkException.InvalidConfig> {
            meshLinkConfig(Preset.OfflineChat, SecurityProfile.None) {
                meshId = "open"
                meshKey = ByteArray(MeshLinkConfig.MESH_KEY_BYTES)
            }
        }

        assertEquals("meshKey", error.setting)
    }

    @Test
    fun build_customPresetOutOfBounds_throwsInvalidConfig() {
        val tooBig = Preset.Custom("Huge", Preset.FieldTeams, presetOverrides { inboxCapacity = 1_000_000 })

        val error = assertFailsWith<MeshLinkException.InvalidConfig> {
            MeshLinkConfig.Builder(tooBig, SecurityProfile.Classical).meshId("x").build()
        }

        assertEquals("inboxCapacity", error.setting)
    }

    @Test
    fun create_whileAnotherInstanceIsOpen_throwsInstanceAlreadyOpen() = meshTest { fake ->
        val phone = fake.addHost("phone")
        MeshLink.create(config, phone)

        assertFailsWith<MeshLinkException.InstanceAlreadyOpen> { MeshLink.create(config, phone) }
    }

    @Test
    fun start_afterClose_throwsClosed() = meshTest { fake ->
        val node = MeshLink.create(config, fake.addHost("phone"))
        node.close()

        assertFailsWith<MeshLinkException.Closed> { node.start() }
    }

    @Test
    fun start_withSecurityProfileNone_emitsWarningDiagnostic() = meshTest { fake ->
        val open = meshLinkConfig(Preset.OfflineChat, SecurityProfile.None) { meshId = "open" }
        val node = MeshLink.create(open, fake.addHost("phone"))

        node.start()

        val event = node.diagnostics.first()
        assertEquals(DiagnosticCode.SecurityProfileNone, event.code)
        assertEquals(Severity.Warning, event.severity)
    }

    @Test
    fun directPeerInAnotherMesh_isNotListedAndEmitsMismatchDiagnostic() = meshTest { fake ->
        val (mine, theirs) = listOf("mine", "theirs").map(fake::addHost)
        fake.link(mine, theirs, rssiDbm = -60)
        val node = MeshLink.create(config, mine)
        MeshLink.create(meshLinkConfig(Preset.OfflineChat, SecurityProfile.PostQuantum) { meshId = "lifecycle" }, theirs).start()

        node.start()

        val event = node.diagnostics.first()
        assertEquals(DiagnosticCode.PeerRejectedMeshMismatch, event.code)
        assertEquals("securityProfile", event.fields["mismatch"])
        assertEquals(emptyList(), node.peers.value)
    }

    @Test
    fun inbox_senderOverItsFairShare_isDroppedThenRetriedWhileOthersGetIn() = meshTest { fake ->
        val (bobPhone, alicePhone, carolPhone) = listOf("bob", "alice", "carol").map(fake::addHost)
        fake.link(bobPhone, alicePhone, rssiDbm = -60)
        fake.link(bobPhone, carolPhone, rssiDbm = -60)
        val small = Preset.Custom("SmallInbox", Preset.OfflineChat, presetOverrides { inboxCapacity = 8 })
        val smallConfig = meshLinkConfig(small, SecurityProfile.Classical) { meshId = "lifecycle" }
        val (bob, alice, carol) = listOf(bobPhone, alicePhone, carolPhone).map { MeshLink.create(smallConfig, it).apply { start() } }
        val fromAlice = (1..3).map { alice.send(bob.localNodeId, byteArrayOf(it.toByte()), SendOptions(deliveryReceipt = true)) }
        carol.send(bob.localNodeId, byteArrayOf(9), SendOptions.Default)

        val dropped = bob.diagnostics.first()
        val senders = bob.messages.take(4).toList().map { if (it.sender == alice.localNodeId) "alice" else "carol" }

        assertEquals(DiagnosticCode.InboxSenderShareExceeded, dropped.code)
        assertEquals(listOf("alice", "alice", "carol", "alice"), senders)
        fromAlice.forEach { (it as SendResult.Accepted).message.status.first { status -> status == DeliveryStatus.Delivered } }
    }
}
