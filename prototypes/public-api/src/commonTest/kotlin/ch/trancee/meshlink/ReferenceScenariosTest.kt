package ch.trancee.meshlink

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The five reference scenarios, written the way an application would use MeshLink. */
class ReferenceScenariosTest {
    // region offline-chat
    @Test
    fun offlineChat_routedPeer_receivesMessageAndSendsReceipts() = meshTest { fake ->
        val (alicePhone, relayPhone, bobPhone) = listOf("alice", "relay", "bob").map(fake::addHost)
        fake.link(alicePhone, relayPhone, rssiDbm = -60)
        fake.link(relayPhone, bobPhone, rssiDbm = -60)
        val config = meshLinkConfig(Preset.OfflineChat, SecurityProfile.Classical) { meshId = "family-chat" }
        val alice = MeshLink.create(config, alicePhone)
        val bob = MeshLink.create(config, bobPhone)
        MeshLink.create(config, relayPhone).start()
        alice.start()
        bob.start()

        val bobAsPeer = alice.peers.first { peers -> peers.any { it.nodeId == bob.localNodeId } }
            .single { it.nodeId == bob.localNodeId }
        val sent = alice.send(bob.localNodeId, "Hi Bob".encodeToByteArray(), SendOptions(deliveryReceipt = true, readReceipt = true))
        val message = assertIs<SendResult.Accepted>(sent).message
        val received = bob.messages.first()
        message.status.first { it == DeliveryStatus.Delivered }
        received.markRead()
        message.status.first { it == DeliveryStatus.Read }

        assertEquals(Reachability.Routed(hops = 2), bobAsPeer.reachability)
        assertEquals("Hi Bob", received.payload.decodeToString())
        assertEquals(alice.localNodeId, received.sender)
    }
    // endregion

    // region emergency-broadcast
    @Test
    fun emergencyBroadcast_reachesNodesWithinHopLimitOnly() = meshTest { fake ->
        val phones = (1..5).map { fake.addHost("phone$it") }
        phones.zipWithNext { first, second -> fake.link(first, second, rssiDbm = -70) }
        val config = meshLinkConfig(Preset.EmergencyBroadcast, SecurityProfile.Classical) { meshId = "valley-alerts" }
        val nodes = phones.map { MeshLink.create(config, it).apply { start() } }
        val (origin, oneHop, twoHops, threeHops, fourHops) = nodes

        val sent = origin.broadcast("Flood warning".encodeToByteArray(), hopLimit = 3, options = BroadcastOptions.Default)
        val beyondLimit = async { withTimeoutOrNull(10.seconds) { fourHops.messages.first() } }
        val hopCounts = listOf(oneHop, twoHops, threeHops).map { it.messages.first().hops }

        assertIs<SendResult.Accepted>(sent)
        assertEquals(listOf(1, 2, 3), hopCounts)
        assertNull(beyondLimit.await())
    }
    // endregion

    // region proximity-discovery
    @Test
    fun proximityDiscovery_bucketsDirectPeersAndLeavesRoutedUnknown() = meshTest { fake ->
        val me = fake.addHost("me")
        val (close, sameRoom, distant, routed) = listOf("close", "same-room", "distant", "routed").map(fake::addHost)
        fake.link(me, close, rssiDbm = -45)
        fake.link(me, sameRoom, rssiDbm = -65)
        fake.link(me, distant, rssiDbm = -85)
        fake.link(close, routed, rssiDbm = -50)
        val config = meshLinkConfig(Preset.ProximityDiscovery, SecurityProfile.Classical) { meshId = "meetup" }
        val node = MeshLink.create(config, me)
        listOf(close, sameRoom, distant, routed).forEach { MeshLink.create(config, it).start() }
        node.start()

        fake.reportRssi(me, distant, rssiDbm = -50)
        val buckets = node.peers.value.associate { it.nodeId to it.proximity.bucket }

        assertEquals(
            mapOf(
                close.nodeId to ProximityBucket.Immediate,
                sameRoom.nodeId to ProximityBucket.Near,
                distant.nodeId to ProximityBucket.Near,
                routed.nodeId to ProximityBucket.Unknown,
            ),
            buckets,
        )
    }
    // endregion

    // region local-multiplayer
    @Test
    fun localMultiplayer_customPresetDeliversMovesBetweenFourPlayersQuickly() = meshTest { fake ->
        val phones = (1..4).map { fake.addHost("player$it") }
        phones.forEachIndexed { index, phone -> phones.drop(index + 1).forEach { fake.link(phone, it, rssiDbm = -50) } }
        val arena = Preset.Custom("Arena", Preset.LocalMultiplayer, presetOverrides { maxPayloadBytes = 64 })
        val config = meshLinkConfig(arena, SecurityProfile.Classical) { meshId = "arena-42" }
        val players = phones.map { MeshLink.create(config, it).apply { start() } }
        val startedAt = testScheduler.currentTime

        players.forEach { player ->
            players.filter { it !== player }.forEach { other ->
                player.send(other.localNodeId, byteArrayOf(1, 2), SendOptions(directOnly = true))
            }
        }
        val movesPerPlayer = players.map { player -> (1..3).map { player.messages.first() }.size }
        val elapsedMillis = testScheduler.currentTime - startedAt

        assertEquals(listOf(3, 3, 3, 3), movesPerPlayer)
        assertTrue(elapsedMillis <= 10, "moves took $elapsedMillis ms")
        assertTrue("maxPayloadBytes: 64 (override)" in config.describe())
    }
    // endregion

    // region field-teams
    @Test
    fun fieldTeams_heldMessageIsDeliveredWhenPeerReturns() = meshTest { fake ->
        val (basePhone, scoutPhone) = listOf("base", "scout").map(fake::addHost)
        fake.link(basePhone, scoutPhone, rssiDbm = -80)
        val config = meshLinkConfig(Preset.FieldTeams, SecurityProfile.PostQuantum) { meshId = "search-team" }
        val base = MeshLink.create(config, basePhone).apply { start() }
        val scout = MeshLink.create(config, scoutPhone).apply { start() }
        scoutPhone.setPresent(false)

        val message = (base.send(scout.localNodeId, "Regroup at hut".encodeToByteArray(), SendOptions(deliveryReceipt = true)) as SendResult.Accepted).message
        message.status.first { it == DeliveryStatus.Held }
        delay(30.seconds)
        scoutPhone.setPresent(true)
        val received = scout.messages.first()

        assertEquals(DeliveryStatus.Delivered, message.status.first { it == DeliveryStatus.Delivered })
        assertEquals("Regroup at hut", received.payload.decodeToString())
    }

    @Test
    fun fieldTeams_heldMessageExpiresWhenLifetimePasses() = meshTest { fake ->
        val (basePhone, scoutPhone) = listOf("base", "scout").map(fake::addHost)
        val config = meshLinkConfig(Preset.FieldTeams, SecurityProfile.PostQuantum) { meshId = "search-team" }
        val base = MeshLink.create(config, basePhone).apply { start() }
        val scout = MeshLink.create(config, scoutPhone)

        val result = base.send(scout.localNodeId, "Regroup at hut".encodeToByteArray(), SendOptions(messageLifetimeSeconds = 60))
        val status = (result as SendResult.Accepted).message.status
        status.first { it == DeliveryStatus.Held }
        delay(61.seconds)

        assertEquals(DeliveryStatus.Expired, status.value)
    }
    // endregion
}
