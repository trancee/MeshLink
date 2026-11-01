package ch.trancee.meshlink.samples

import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.SendResult
import ch.trancee.meshlink.meshTest
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Executes every `@sample` so the KDoc examples are known to run (ADR 0006, O5). */
class SamplesTest {
    @Test
    fun samples_runAgainstTheFakeMesh() = meshTest { fake ->
        val (alicePhone, bobPhone) = listOf("alice", "bob").map(fake::addHost)
        fake.link(alicePhone, bobPhone, rssiDbm = -40)
        val alice = createAndStart(alicePhone)
        val bob = createAndStart(bobPhone)
        alice.peers.first { it.isNotEmpty() }

        val delivery = async { sendAndTrack(alice, bob.localNodeId) }
        val text = receiveAndMarkRead(bob)

        assertEquals("Hello", text)
        assertEquals("delivered", delivery.await())
        assertEquals("running", observeState(alice))
        assertEquals(listOf(bob.localNodeId), listNearbyPeers(alice))
        assertIs<SendResult.Accepted>(broadcastAlert(alice))
        assertTrue(nodeIdRoundTrip(alice.localNodeId))
        assertEquals("Arena", customPreset().name)
        assertTrue(configureWithDsl().isGated)
        assertTrue(configureWithBuilder().debugDiagnostics)
        val open = MeshLink.create(
            ch.trancee.meshlink.meshLinkConfig(ch.trancee.meshlink.Preset.OfflineChat, ch.trancee.meshlink.SecurityProfile.None) { meshId = "o" },
            fake.addHost("open"),
        ).apply { start() }
        assertTrue(watchDiagnostics(open).startsWith("1001 SecurityProfileNone Warning"))
    }
}
