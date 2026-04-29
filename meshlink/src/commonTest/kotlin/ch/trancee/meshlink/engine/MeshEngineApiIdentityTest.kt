package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DiagnosticEvent
import ch.trancee.meshlink.api.DiagnosticPayload
import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.api.MeshLinkConfig
import ch.trancee.meshlink.api.meshLinkConfig
import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.crypto.Identity
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Tests for MeshEngine's identity & trust internal API methods (S06/T01): rotateIdentity,
 * peerPublicKey, peerFingerprint, repinKey, acceptKeyChange, rejectKeyChange, pendingKeyChanges.
 *
 * Uses the MeshLink.create() factory to get a full engine stack with DiagnosticSink enabled. RULE
 * (MEM184): Never call advanceUntilIdle() while the engine is active — PowerManager's
 * while(true){delay()} loops forever. Always stop the engine FIRST.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshEngineApiIdentityTest {

    private val crypto: CryptoProvider = createCryptoProvider()

    // ── Helper ────────────────────────────────────────────────────────────

    private fun TestScope.makeMesh(
        config: MeshLinkConfig =
            meshLinkConfig("ch.trancee.test") { diagnostics { enabled = true } }
    ): MeshLink {
        val storage = InMemorySecureStorage()
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

    // ── peerPublicKey ─────────────────────────────────────────────────────

    @Test
    fun `peerPublicKey returns null for unknown peer`() = runTest {
        val mesh = makeMesh()
        mesh.start()
        val result = mesh.peerPublicKey(ByteArray(12) { 0xAA.toByte() })
        assertNull(result)
        mesh.stop()
    }

    @Test
    fun `peerPublicKey returns key for pinned peer`() = runTest {
        // Create with known storage so we can pre-pin a key.
        val storage = InMemorySecureStorage()
        val battery = StubBatteryMonitor()
        val transport = VirtualMeshTransport(ByteArray(12) { it.toByte() }, testScheduler)
        val mesh =
            MeshLink.create(
                config = meshLinkConfig("ch.trancee.test") { diagnostics { enabled = true } },
                cryptoProvider = crypto,
                transport = transport,
                storage = storage,
                batteryMonitor = battery,
                parentScope = this,
                clock = { testScheduler.currentTime },
            )
        // Pin a key directly in storage using the TrustStore convention.
        val peerId = ByteArray(12) { 0xBB.toByte() }
        val key = crypto.generateX25519KeyPair().publicKey
        val trustStore = TrustStore(storage)
        trustStore.pinKey(peerId, key)

        mesh.start()
        val result = mesh.peerPublicKey(peerId)
        assertNotNull(result)
        assertTrue(result.contentEquals(key))
        mesh.stop()
    }

    // ── peerFingerprint ───────────────────────────────────────────────────

    @Test
    fun `peerFingerprint returns null for unknown peer`() = runTest {
        val mesh = makeMesh()
        mesh.start()
        val result = mesh.peerFingerprint(ByteArray(12) { 0xCC.toByte() })
        assertNull(result)
        mesh.stop()
    }

    @Test
    fun `peerFingerprint returns uppercase colon-separated hex for known peer`() = runTest {
        val storage = InMemorySecureStorage()
        val battery = StubBatteryMonitor()
        val transport = VirtualMeshTransport(ByteArray(12) { it.toByte() }, testScheduler)
        val mesh =
            MeshLink.create(
                config = meshLinkConfig("ch.trancee.test") { diagnostics { enabled = true } },
                cryptoProvider = crypto,
                transport = transport,
                storage = storage,
                batteryMonitor = battery,
                parentScope = this,
                clock = { testScheduler.currentTime },
            )
        val peerId =
            byteArrayOf(
                0xA1.toByte(),
                0xB2.toByte(),
                0xC3.toByte(),
                0xD4.toByte(),
                0xE5.toByte(),
                0xF6.toByte(),
                0xA7.toByte(),
                0xB8.toByte(),
                0xC9.toByte(),
                0xD0.toByte(),
                0xE1.toByte(),
                0xF2.toByte(),
            )
        val key = crypto.generateX25519KeyPair().publicKey
        TrustStore(storage).pinKey(peerId, key)

        mesh.start()
        val fp = mesh.peerFingerprint(peerId)
        assertNotNull(fp)
        assertEquals("A1:B2:C3:D4:E5:F6:A7:B8:C9:D0:E1:F2", fp)
        mesh.stop()
    }

    // ── rotateIdentity ────────────────────────────────────────────────────

    @Test
    fun `rotateIdentity from non-RUNNING state throws IllegalStateException`() = runTest {
        val mesh = makeMesh()
        // Not started — should throw
        assertFailsWith<IllegalStateException> { mesh.rotateIdentity() }
        mesh.stopEngineForTest()
    }

    @Test
    fun `rotateIdentity from RUNNING succeeds and emits diagnostic`() = runTest {
        val mesh = makeMesh()
        val events = mutableListOf<DiagnosticEvent>()
        val collectJob = launch { mesh.diagnosticEvents.collect { events += it } }
        mesh.start()
        mesh.rotateIdentity()
        testScheduler.runCurrent()
        // Check that an identity_rotated diagnostic was emitted.
        val rotationEvent = events.find { event ->
            val payload = event.payload
            payload is DiagnosticPayload.TextMessage && payload.message == "identity_rotated"
        }
        assertNotNull(rotationEvent, "Expected identity_rotated diagnostic event")
        mesh.stop()
        collectJob.cancel()
    }

    // ── repinKey ──────────────────────────────────────────────────────────

    @Test
    fun `repinKey from non-RUNNING state throws IllegalStateException`() = runTest {
        val mesh = makeMesh()
        assertFailsWith<IllegalStateException> { mesh.repinKey(ByteArray(12)) }
        mesh.stopEngineForTest()
    }

    @Test
    fun `repinKey with no pending change is a no-op`() = runTest {
        val mesh = makeMesh()
        mesh.start()
        // Should not throw — just a no-op
        mesh.repinKey(ByteArray(12) { 0xDD.toByte() })
        mesh.stop()
    }

    // ── acceptKeyChange / rejectKeyChange ─────────────────────────────────

    @Test
    fun `acceptKeyChange from non-RUNNING state throws IllegalStateException`() = runTest {
        val mesh = makeMesh()
        assertFailsWith<IllegalStateException> { mesh.acceptKeyChange(ByteArray(12)) }
        mesh.stopEngineForTest()
    }

    @Test
    fun `rejectKeyChange from non-RUNNING state throws IllegalStateException`() = runTest {
        val mesh = makeMesh()
        assertFailsWith<IllegalStateException> { mesh.rejectKeyChange(ByteArray(12)) }
        mesh.stopEngineForTest()
    }

    @Test
    fun `acceptKeyChange with no pending change is a no-op`() = runTest {
        val mesh = makeMesh()
        mesh.start()
        mesh.acceptKeyChange(ByteArray(12) { 0xEE.toByte() })
        mesh.stop()
    }

    @Test
    fun `rejectKeyChange with no pending change is a no-op`() = runTest {
        val mesh = makeMesh()
        mesh.start()
        mesh.rejectKeyChange(ByteArray(12) { 0xFF.toByte() })
        mesh.stop()
    }

    // ── pendingKeyChanges ─────────────────────────────────────────────────

    @Test
    fun `pendingKeyChanges returns empty list when no changes pending`() = runTest {
        val mesh = makeMesh()
        mesh.start()
        assertTrue(mesh.pendingKeyChanges().isEmpty())
        mesh.stop()
    }

    // ── PseudonymRotator.updateKeyHash ────────────────────────────────────

    @Test
    fun `PseudonymRotator updateKeyHash changes computed pseudonyms`() {
        val originalKeyHash = ByteArray(12) { 0x01 }
        val newKeyHash = ByteArray(12) { 0x02 }
        val rotator =
            PseudonymRotator(
                keyHash = originalKeyHash,
                cryptoProvider = crypto,
                scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job()),
                clock = { 0L },
                diagnosticSink = ch.trancee.meshlink.api.NoOpDiagnosticSink,
                onRotation = {},
            )
        val pseudonymBefore = rotator.computePseudonym(0)
        rotator.updateKeyHash(newKeyHash)
        val pseudonymAfter = rotator.computePseudonym(0)
        // Pseudonyms should differ because key hash changed.
        assertTrue(
            !pseudonymBefore.contentEquals(pseudonymAfter),
            "Pseudonyms should differ after updateKeyHash",
        )
    }

    // ── NoiseHandshakeManager.invalidateAllSessions ───────────────────────

    @Test
    fun `NoiseHandshakeManager invalidateAllSessions clears active handshakes`() {
        val storage = InMemorySecureStorage()
        val identity = Identity.loadOrGenerate(crypto, storage)
        val trustStore = TrustStore(storage)
        val mgr =
            NoiseHandshakeManager(
                localIdentity = identity,
                cryptoProvider = crypto,
                trustStore = trustStore,
                config = HandshakeConfig(),
                clock = { 1000L },
            )
        mgr.sendHandshake = { _, _ -> } // no-op
        mgr.onHandshakeComplete = {}
        // Trigger an advertisement to create an in-flight handshake.
        val peerId = ByteArray(12) { 0xAA.toByte() }
        mgr.onAdvertisementSeen(peerId, ByteArray(1))
        // Invalidate — should clear everything without throwing.
        mgr.invalidateAllSessions()
        // Subsequent advertisement should initiate a new handshake (not reconnect shortcut).
        var sent = false
        mgr.sendHandshake = { _, _ -> sent = true }
        mgr.onAdvertisementSeen(peerId, ByteArray(1))
        assertTrue(sent, "Expected new handshake initiation after invalidateAllSessions")
    }

    // ── MeshLink integration: wired stubs delegate correctly ──────────────

    @Test
    fun `wired identity methods work through MeshLink after start`() = runTest {
        val mesh = makeMesh()
        mesh.start()

        // peerPublicKey — unknown peer returns null
        assertNull(mesh.peerPublicKey(ByteArray(12)))
        // peerFingerprint — unknown peer returns null
        assertNull(mesh.peerFingerprint(ByteArray(12)))
        // pendingKeyChanges — empty
        assertTrue(mesh.pendingKeyChanges().isEmpty())
        // rotateIdentity — does not throw
        mesh.rotateIdentity()
        // repinKey — no-op for unknown peer
        mesh.repinKey(ByteArray(12))
        // acceptKeyChange — no-op for unknown peer
        mesh.acceptKeyChange(ByteArray(12))
        // rejectKeyChange — no-op for unknown peer
        mesh.rejectKeyChange(ByteArray(12))

        mesh.stop()
    }
}
