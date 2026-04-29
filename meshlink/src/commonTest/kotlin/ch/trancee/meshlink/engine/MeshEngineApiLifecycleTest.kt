package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DiagnosticPayload
import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.api.MeshLinkConfig
import ch.trancee.meshlink.api.PeerState
import ch.trancee.meshlink.api.meshLinkConfig
import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.crypto.TrustStore
import ch.trancee.meshlink.crypto.createCryptoProvider
import ch.trancee.meshlink.power.StubBatteryMonitor
import ch.trancee.meshlink.storage.InMemorySecureStorage
import ch.trancee.meshlink.transport.VirtualMeshTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Tests for MeshEngine's peer lifecycle, cleanup, and health internal API methods (S06/T02):
 * peerDetail, allPeerDetails, forgetPeer, factoryReset, shedMemoryPressure, and health snapshot.
 *
 * Uses the MeshLink.create() factory to get a full engine stack with DiagnosticSink enabled. RULE
 * (MEM184): Never call advanceUntilIdle() while the engine is active — PowerManager's
 * while(true){delay()} loops forever. Always stop the engine FIRST.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshEngineApiLifecycleTest {

    private val crypto: CryptoProvider = createCryptoProvider()

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun TestScope.makeMesh(
        storage: InMemorySecureStorage = InMemorySecureStorage(),
        config: MeshLinkConfig =
            meshLinkConfig("ch.trancee.test") { diagnostics { enabled = true } },
    ): MeshLink {
        val battery = StubBatteryMonitor()
        val transport = VirtualMeshTransport(ByteArray(12) { it.toByte() }, testScheduler)
        return MeshLink.create(
            config = config,
            cryptoProvider = crypto,
            transport = transport,
            storage = storage,
            batteryMonitor = battery,
            parentScope = this,
            clock = { testScheduler.currentTime },
        )
    }

    // ── peerDetail ────────────────────────────────────────────────────────

    @Test
    fun `peerDetail returns null for unknown peer`() = runTest {
        val mesh = makeMesh()
        mesh.start()
        val result = mesh.peerDetail(ByteArray(12) { 0xAA.toByte() })
        assertNull(result)
        mesh.stop()
    }

    @Test
    fun `peerDetail returns assembled data for connected peer`() = runTest {
        val storage = InMemorySecureStorage()
        val mesh = makeMesh(storage)
        mesh.start()

        val peerId = ByteArray(12) { (0x10 + it).toByte() }
        // Pin a key for this peer so TrustStore has data.
        val trustStore = TrustStore(storage)
        val key = crypto.generateX25519KeyPair().publicKey
        trustStore.pinKey(peerId, key)

        // Inject peer into presence tracker.
        mesh.injectTestPeer(peerId)

        val detail = mesh.peerDetail(peerId)
        assertNotNull(detail)
        assertTrue(detail.id.contentEquals(peerId))
        assertTrue(detail.staticPublicKey.contentEquals(key))
        assertEquals(PeerState.CONNECTED, detail.state)
        // Fingerprint is uppercase colon-separated hex.
        val expectedFingerprint =
            peerId.joinToString(":") {
                it.toInt().and(0xFF).toString(16).padStart(2, '0').uppercase()
            }
        assertEquals(expectedFingerprint, detail.fingerprint)
        mesh.stop()
    }

    @Test
    fun `peerDetail returns empty key when peer not pinned in TrustStore`() = runTest {
        val mesh = makeMesh()
        mesh.start()

        val peerId = ByteArray(12) { (0x20 + it).toByte() }
        mesh.injectTestPeer(peerId)

        val detail = mesh.peerDetail(peerId)
        assertNotNull(detail)
        // staticPublicKey should be a 32-byte zeroed array (fallback).
        assertEquals(32, detail.staticPublicKey.size)
        assertTrue(detail.staticPublicKey.all { it == 0.toByte() })
        mesh.stop()
    }

    // ── allPeerDetails ────────────────────────────────────────────────────

    @Test
    fun `allPeerDetails returns empty list when no peers connected`() = runTest {
        val mesh = makeMesh()
        mesh.start()
        val result = mesh.allPeerDetails()
        assertTrue(result.isEmpty())
        mesh.stop()
    }

    @Test
    fun `allPeerDetails returns list of connected peers`() = runTest {
        val mesh = makeMesh()
        mesh.start()

        val peer1 = ByteArray(12) { (0x30 + it).toByte() }
        val peer2 = ByteArray(12) { (0x40 + it).toByte() }
        mesh.injectTestPeer(peer1)
        mesh.injectTestPeer(peer2)

        val result = mesh.allPeerDetails()
        assertEquals(2, result.size)
        mesh.stop()
    }

    // ── forgetPeer ────────────────────────────────────────────────────────

    @Test
    fun `forgetPeer from non-RUNNING throws IllegalStateException`() = runTest {
        val mesh = makeMesh()
        // Not started — UNINITIALIZED state.
        assertFailsWith<IllegalStateException> { mesh.forgetPeer(ByteArray(12)) }
        mesh.stopEngineForTest()
    }

    @Test
    fun `forgetPeer for unknown peer is idempotent with LOG diagnostic`() = runTest {
        val mesh = makeMesh()
        mesh.start()

        val unknownPeer = ByteArray(12) { 0xFF.toByte() }
        mesh.forgetPeer(unknownPeer)

        // Verify diagnostic was emitted.
        val lastDiag = mesh.lastDiagnosticEvent
        assertNotNull(lastDiag)
        val payload = lastDiag.payload
        assertTrue(payload is DiagnosticPayload.TextMessage)
        assertTrue(payload.message.contains("forget_peer_unknown"))
        mesh.stop()
    }

    @Test
    fun `forgetPeer clears trust and presence for known peer`() = runTest {
        val storage = InMemorySecureStorage()
        val mesh = makeMesh(storage)
        mesh.start()

        val peerId = ByteArray(12) { (0x50 + it).toByte() }
        val trustStore = TrustStore(storage)
        val key = crypto.generateX25519KeyPair().publicKey
        trustStore.pinKey(peerId, key)
        mesh.injectTestPeer(peerId)

        // Verify peer is visible before forget.
        assertNotNull(mesh.peerDetail(peerId))
        assertNotNull(trustStore.getPinnedKey(peerId))

        mesh.forgetPeer(peerId)

        // After forget: TrustStore pin removed (GDPR).
        assertNull(trustStore.getPinnedKey(peerId))
        // Peer should be in DISCONNECTED state (onPeerDisconnected was called).
        // The detail may still exist in presence tracker but as DISCONNECTED.
        val detailAfter = mesh.peerDetail(peerId)
        if (detailAfter != null) {
            assertEquals(PeerState.DISCONNECTED, detailAfter.state)
        }

        // Verify diagnostic was emitted.
        val lastDiag = mesh.lastDiagnosticEvent
        assertNotNull(lastDiag)
        val payload = lastDiag.payload
        assertTrue(payload is DiagnosticPayload.TextMessage)
        assertTrue(payload.message.contains("peer_forgotten"))
        mesh.stop()
    }

    // ── factoryReset ──────────────────────────────────────────────────────

    @Test
    fun `factoryReset from non-STOPPED throws IllegalStateException`() = runTest {
        val mesh = makeMesh()
        mesh.start()
        assertFailsWith<IllegalStateException> { mesh.factoryReset() }
        mesh.stop()
    }

    @Test
    fun `factoryReset clears storage when STOPPED`() = runTest {
        val storage = InMemorySecureStorage()
        val mesh = makeMesh(storage)

        // Put some data in storage.
        storage.put("test_key", byteArrayOf(1, 2, 3))
        assertTrue(storage.contains("test_key"))

        mesh.start()
        mesh.stop()

        // Now in STOPPED state — factoryReset should succeed.
        mesh.factoryReset()
        // Storage should be cleared.
        assertTrue(!storage.contains("test_key"))
    }

    @Test
    fun `factoryReset from UNINITIALIZED succeeds`() = runTest {
        val storage = InMemorySecureStorage()
        val mesh = makeMesh(storage)
        storage.put("secret", byteArrayOf(99))
        // Never started — UNINITIALIZED state.
        mesh.factoryReset()
        assertTrue(!storage.contains("secret"))
        mesh.stopEngineForTest()
    }

    // ── shedMemoryPressure ────────────────────────────────────────────────

    @Test
    fun `shedMemoryPressure from non-RUNNING throws IllegalStateException`() = runTest {
        val mesh = makeMesh()
        // Not started — UNINITIALIZED.
        assertFailsWith<IllegalStateException> { mesh.shedMemoryPressure() }
        mesh.stopEngineForTest()
    }

    @Test
    fun `shedMemoryPressure emits diagnostic`() = runTest {
        val mesh = makeMesh()
        mesh.start()

        mesh.shedMemoryPressure()

        val lastDiag = mesh.lastDiagnosticEvent
        assertNotNull(lastDiag)
        val payload = lastDiag.payload
        assertTrue(payload is DiagnosticPayload.TextMessage)
        assertTrue(payload.message.contains("memory_pressure_shed"))
        mesh.stop()
    }

    // ── Health snapshot ───────────────────────────────────────────────────

    @Test
    fun `meshHealth returns zero buffer values when no messages buffered`() = runTest {
        val mesh = makeMesh()
        mesh.start()

        val health = mesh.meshHealth()
        assertEquals(0L, health.bufferUsageBytes)
        assertEquals(0, health.bufferUtilizationPercent)
        assertEquals(0, health.activeTransfers)
        assertEquals(0, health.relayQueueSize)
        mesh.stop()
    }

    @Test
    fun `meshHealth returns connected peer count from presence tracker`() = runTest {
        val mesh = makeMesh()
        mesh.start()

        val peer1 = ByteArray(12) { (0x60 + it).toByte() }
        val peer2 = ByteArray(12) { (0x70 + it).toByte() }
        mesh.injectTestPeer(peer1)
        mesh.injectTestPeer(peer2)

        val health = mesh.meshHealth()
        assertEquals(2, health.connectedPeers)
        assertEquals(2, health.reachablePeers)
        mesh.stop()
    }

    @Test
    fun `meshHealth returns routing table size`() = runTest {
        val mesh = makeMesh()
        mesh.start()

        mesh.injectTestRoute(
            destination = ByteArray(12) { 0x01 },
            nextHop = ByteArray(12) { 0x02 },
            metric = 1.5,
            expiresAt = Long.MAX_VALUE,
        )

        val health = mesh.meshHealth()
        assertEquals(1, health.routingTableSize)
        mesh.stop()
    }

    // ── peerDetail non-RUNNING returns null ───────────────────────────────

    @Test
    fun `peerDetail from non-RUNNING returns null`() = runTest {
        val mesh = makeMesh()
        // Not started — UNINITIALIZED.
        val result = mesh.peerDetail(ByteArray(12))
        assertNull(result)
        mesh.stopEngineForTest()
    }

    @Test
    fun `allPeerDetails from non-RUNNING returns empty list`() = runTest {
        val mesh = makeMesh()
        // Not started — UNINITIALIZED.
        val result = mesh.allPeerDetails()
        assertTrue(result.isEmpty())
        mesh.stopEngineForTest()
    }
}
