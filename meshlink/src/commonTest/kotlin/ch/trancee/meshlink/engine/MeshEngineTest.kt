package ch.trancee.meshlink.engine

import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.crypto.Identity
import ch.trancee.meshlink.crypto.TrustStore
import ch.trancee.meshlink.crypto.createCryptoProvider
import ch.trancee.meshlink.crypto.noise.NoiseXXInitiator
import ch.trancee.meshlink.crypto.noise.NoiseXXResponder
import ch.trancee.meshlink.power.PowerTier
import ch.trancee.meshlink.power.StubBatteryMonitor
import ch.trancee.meshlink.storage.InMemorySecureStorage
import ch.trancee.meshlink.transport.VirtualMeshTransport
import ch.trancee.meshlink.wire.Handshake
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MeshEngineTest {

    private val crypto: CryptoProvider = createCryptoProvider()

    // ── NoiseHandshakeManager unit tests ──────────────────────────────────────

    private fun makeHandshakeManager(
        clock: () -> Long = { 1000L },
        config: HandshakeConfig = HandshakeConfig(),
    ): Pair<NoiseHandshakeManager, TrustStore> {
        val storage = InMemorySecureStorage()
        val identity = Identity.loadOrGenerate(crypto, storage)
        val trustStore = TrustStore(storage)
        val mgr =
            NoiseHandshakeManager(
                localIdentity = identity,
                cryptoProvider = crypto,
                trustStore = trustStore,
                config = config,
                clock = clock,
            )
        return Pair(mgr, trustStore)
    }

    // ── Known peer reconnect shortcut ─────────────────────────────────────────

    @Test
    fun `onAdvertisementSeen with pinned peer invokes onHandshakeComplete immediately`() {
        val (mgr, trustStore) = makeHandshakeManager()
        val peerId = ByteArray(12) { it.toByte() }
        trustStore.pinKey(peerId, crypto.generateX25519KeyPair().publicKey)

        var completed: ByteArray? = null
        mgr.onHandshakeComplete = { id -> completed = id }

        mgr.onAdvertisementSeen(peerId, ByteArray(1))

        assertNotNull(completed)
        assertTrue(completed!!.contentEquals(peerId))
    }

    @Test
    fun `onAdvertisementSeen with pinned peer does not start a handshake`() {
        val (mgr, trustStore) = makeHandshakeManager()
        val peerId = ByteArray(12) { it.toByte() }
        trustStore.pinKey(peerId, crypto.generateX25519KeyPair().publicKey)

        var sent: Handshake? = null
        mgr.sendHandshake = { _, msg -> sent = msg }

        mgr.onAdvertisementSeen(peerId, ByteArray(1))

        assertNull(sent)
    }

    // ── Full Noise XX 3-message flow ──────────────────────────────────────────

    @Test
    fun `full Noise XX 3-step handshake completes on both sides`() {
        val storageA = InMemorySecureStorage()
        val storageB = InMemorySecureStorage()
        val identityA = Identity.loadOrGenerate(crypto, storageA)
        val identityB = Identity.loadOrGenerate(crypto, storageB)
        val trustA = TrustStore(storageA)
        val trustB = TrustStore(storageB)
        val peerIdA = identityA.keyHash
        val peerIdB = identityB.keyHash

        val mgrA =
            NoiseHandshakeManager(identityA, crypto, trustA, HandshakeConfig(), clock = { 1000L })
        val mgrB =
            NoiseHandshakeManager(identityB, crypto, trustB, HandshakeConfig(), clock = { 1000L })

        var completedA: ByteArray? = null
        var completedB: ByteArray? = null
        mgrA.onHandshakeComplete = { id -> completedA = id }
        mgrB.onHandshakeComplete = { id -> completedB = id }

        // Wire A → B and B → A for handshake messages
        mgrA.sendHandshake = { _, msg -> mgrB.onInboundHandshake(peerIdA, msg) }
        mgrB.sendHandshake = { _, msg -> mgrA.onInboundHandshake(peerIdB, msg) }

        // A sees B's advertisement and initiates
        mgrA.onAdvertisementSeen(peerIdB, ByteArray(1))

        // Both sides should have completed the handshake
        assertNotNull(completedA)
        assertTrue(completedA!!.contentEquals(peerIdB))
        assertNotNull(completedB)
        assertTrue(completedB!!.contentEquals(peerIdA))

        // Keys should be pinned on both sides
        assertNotNull(trustA.getPinnedKey(peerIdB))
        assertNotNull(trustB.getPinnedKey(peerIdA))
    }

    // ── Rate limiter ──────────────────────────────────────────────────────────

    @Test
    fun `rate limiter suppresses second initiation within window`() {
        var now = 1000L
        val (mgr, _) = makeHandshakeManager(
            clock = { now },
            config = HandshakeConfig(rateLimitWindowMs = 2000L),
        )
        val peerId = ByteArray(12) { 0x10 }
        var sendCount = 0
        mgr.sendHandshake = { _, _ -> sendCount++ }

        mgr.onAdvertisementSeen(peerId, ByteArray(1)) // sends step 1
        assertEquals(1, sendCount)

        now = 1500L // still inside window
        mgr.onAdvertisementSeen(peerId, ByteArray(1)) // suppressed
        assertEquals(1, sendCount)

        now = 3001L // outside window
        mgr.onAdvertisementSeen(peerId, ByteArray(1)) // allowed
        assertEquals(2, sendCount)
    }

    // ── Max concurrent limit ──────────────────────────────────────────────────

    @Test
    fun `onAdvertisementSeen drops new peer when at maxConcurrentHandshakes`() {
        val (mgr, _) = makeHandshakeManager(config = HandshakeConfig(maxConcurrentHandshakes = 1))
        var sendCount = 0
        mgr.sendHandshake = { _, _ -> sendCount++ }

        val peer1 = ByteArray(12) { 0x01 }
        val peer2 = ByteArray(12) { 0x02 }

        mgr.onAdvertisementSeen(peer1, ByteArray(1)) // accepted
        assertEquals(1, sendCount)

        mgr.onAdvertisementSeen(peer2, ByteArray(1)) // dropped: at cap
        assertEquals(1, sendCount)
    }

    @Test
    fun `handleStep1 drops when at maxConcurrentHandshakes`() {
        val (mgr, _) = makeHandshakeManager(config = HandshakeConfig(maxConcurrentHandshakes = 1))
        var sendCount = 0
        mgr.sendHandshake = { _, _ -> sendCount++ }

        val peer1 = ByteArray(12) { 0x01 }
        val peer2 = ByteArray(12) { 0x02 }

        // Fill the slot with an inbound step1 from peer1
        val remoteInitiator = NoiseXXInitiator(crypto, crypto.generateX25519KeyPair())
        val msg1 = remoteInitiator.writeMessage1()
        mgr.onInboundHandshake(peer1, Handshake(step = 1u, noiseMessage = msg1))
        assertEquals(1, sendCount) // step 2 sent

        // Another inbound step1 should be dropped
        val msg1b = NoiseXXInitiator(crypto, crypto.generateX25519KeyPair()).writeMessage1()
        mgr.onInboundHandshake(peer2, Handshake(step = 1u, noiseMessage = msg1b))
        assertEquals(1, sendCount) // still 1
    }

    // ── Unknown step number no-op ─────────────────────────────────────────────

    @Test
    fun `onInboundHandshake with unknown step is a no-op`() {
        val (mgr, _) = makeHandshakeManager()
        var sendCount = 0
        var completedCount = 0
        mgr.sendHandshake = { _, _ -> sendCount++ }
        mgr.onHandshakeComplete = { _ -> completedCount++ }

        mgr.onInboundHandshake(ByteArray(12), Handshake(step = 99u, noiseMessage = ByteArray(0)))

        assertEquals(0, sendCount)
        assertEquals(0, completedCount)
    }

    // ── Wrong state for step ──────────────────────────────────────────────────

    @Test
    fun `step 2 with no matching Initiating state is a no-op`() {
        val (mgr, _) = makeHandshakeManager()
        var completedCount = 0
        mgr.onHandshakeComplete = { _ -> completedCount++ }

        val tempInit = NoiseXXInitiator(crypto, crypto.generateX25519KeyPair())
        val tempResp = NoiseXXResponder(crypto, crypto.generateX25519KeyPair())
        tempResp.readMessage1(tempInit.writeMessage1())
        val msg2 = tempResp.writeMessage2()

        mgr.onInboundHandshake(ByteArray(12), Handshake(step = 2u, noiseMessage = msg2))
        assertEquals(0, completedCount)
    }

    @Test
    fun `step 3 with no matching Responding state is a no-op`() {
        val (mgr, _) = makeHandshakeManager()
        var completedCount = 0
        mgr.onHandshakeComplete = { _ -> completedCount++ }

        val initiator = NoiseXXInitiator(crypto, crypto.generateX25519KeyPair())
        val responder = NoiseXXResponder(crypto, crypto.generateX25519KeyPair())
        responder.readMessage1(initiator.writeMessage1())
        initiator.readMessage2(responder.writeMessage2())
        val msg3 = initiator.writeMessage3()

        mgr.onInboundHandshake(ByteArray(12), Handshake(step = 3u, noiseMessage = msg3))
        assertEquals(0, completedCount)
    }

    // ── Exception / malformed message handling ────────────────────────────────

    @Test
    fun `handleStep1 with malformed noiseMessage cleans up state`() {
        val (mgr, _) = makeHandshakeManager()
        var sendCount = 0
        mgr.sendHandshake = { _, _ -> sendCount++ }

        // Pass empty bytes that will cause NoiseXXResponder.readMessage1 to throw
        mgr.onInboundHandshake(
            ByteArray(12) { 0xAA.toByte() },
            Handshake(step = 1u, noiseMessage = ByteArray(0)),
        )
        // No step-2 send should happen
        assertEquals(0, sendCount)
    }

    @Test
    fun `handleStep2 with malformed message cleans up initiating state`() {
        val (mgr, _) = makeHandshakeManager()
        val peerId = ByteArray(12) { 0x22.toByte() }

        var step1Sent = false
        mgr.sendHandshake = { _, msg -> if (msg.step == 1u.toUByte()) step1Sent = true }
        mgr.onAdvertisementSeen(peerId, ByteArray(1))
        assertTrue(step1Sent)

        // Send garbage step 2 — should throw inside readMessage2 and remove state
        var completedCount = 0
        mgr.onHandshakeComplete = { _ -> completedCount++ }
        mgr.onInboundHandshake(peerId, Handshake(step = 2u, noiseMessage = ByteArray(5)))
        assertEquals(0, completedCount)
    }

    @Test
    fun `handleStep3 with malformed message cleans up responding state`() {
        val (mgr, _) = makeHandshakeManager()
        val peerIdRemote = ByteArray(12) { 0x33.toByte() }

        val remoteInitiator = NoiseXXInitiator(crypto, crypto.generateX25519KeyPair())
        val msg1 = remoteInitiator.writeMessage1()
        var step2Sent = false
        mgr.sendHandshake = { _, msg -> if (msg.step == 2u.toUByte()) step2Sent = true }
        mgr.onInboundHandshake(peerIdRemote, Handshake(step = 1u, noiseMessage = msg1))
        assertTrue(step2Sent)

        var completedCount = 0
        mgr.onHandshakeComplete = { _ -> completedCount++ }
        mgr.onInboundHandshake(peerIdRemote, Handshake(step = 3u, noiseMessage = ByteArray(5)))
        assertEquals(0, completedCount)
    }

    // ── PowerTierCodec integration ────────────────────────────────────────────

    @Test
    fun `PowerTierCodec encodes all tiers to distinct single-byte values`() {
        val encoded = PowerTier.entries.map { PowerTierCodec.encode(it) }
        assertEquals(3, encoded.distinct().size)
        for (e in encoded) assertEquals(1, e.size)
    }

    // ── MeshEngine create factory ─────────────────────────────────────────────

    @Test
    fun `MeshEngine create returns instance`() = runTest {
        val storage = InMemorySecureStorage()
        val identity = Identity.loadOrGenerate(crypto, storage)
        val transport = VirtualMeshTransport(identity.keyHash, testScheduler)
        val engine =
            MeshEngine.create(
                identity = identity,
                cryptoProvider = crypto,
                transport = transport,
                storage = storage,
                batteryMonitor = StubBatteryMonitor(level = 0.9f),
                scope = backgroundScope,
                clock = { testScheduler.currentTime },
            )
        assertNotNull(engine)
        engine.stop()
    }

    // ── MeshEngine SharedFlow surfaces exposed ────────────────────────────────

    @Test
    fun `MeshEngine exposes all required SharedFlow surfaces`() = runTest {
        val storage = InMemorySecureStorage()
        val identity = Identity.loadOrGenerate(crypto, storage)
        val transport = VirtualMeshTransport(identity.keyHash, testScheduler)
        val engine =
            MeshEngine.create(
                identity = identity,
                cryptoProvider = crypto,
                transport = transport,
                storage = storage,
                batteryMonitor = StubBatteryMonitor(level = 0.9f),
                scope = backgroundScope,
                clock = { testScheduler.currentTime },
            )
        assertNotNull(engine.messages)
        assertNotNull(engine.deliveryConfirmations)
        assertNotNull(engine.transferFailures)
        assertNotNull(engine.transferProgress)
        assertNotNull(engine.peerEvents)
        assertNotNull(engine.tierChanges)
        engine.stop()
    }

    // ── MeshEngine start sets advertisement service data ──────────────────────

    @Test
    fun `start sets initial advertisement service data on transport`() = runTest {
        val storage = InMemorySecureStorage()
        val identity = Identity.loadOrGenerate(crypto, storage)
        val transport = VirtualMeshTransport(identity.keyHash, testScheduler)
        val engine =
            MeshEngine.create(
                identity = identity,
                cryptoProvider = crypto,
                transport = transport,
                storage = storage,
                batteryMonitor = StubBatteryMonitor(level = 0.9f),
                scope = backgroundScope,
                clock = { testScheduler.currentTime },
            )
        engine.start()

        // After start, transport's advertisementServiceData should be a valid tier encoding
        val decoded = PowerTierCodec.decode(transport.advertisementServiceData)
        assertNotNull(decoded)
        engine.stop()
    }

    // ── MeshEngine stop completes cleanly ─────────────────────────────────────

    @Test
    fun `stop completes without error`() = runTest {
        val storage = InMemorySecureStorage()
        val identity = Identity.loadOrGenerate(crypto, storage)
        val transport = VirtualMeshTransport(identity.keyHash, testScheduler)
        val engine =
            MeshEngine.create(
                identity = identity,
                cryptoProvider = crypto,
                transport = transport,
                storage = storage,
                batteryMonitor = StubBatteryMonitor(level = 0.9f),
                scope = backgroundScope,
                clock = { testScheduler.currentTime },
            )
        engine.start()
        engine.stop() // must not throw
    }

    // ── MeshEngine.currentTier ────────────────────────────────────────────────

    @Test
    fun `currentTier returns a valid PowerTier`() = runTest {
        val storage = InMemorySecureStorage()
        val identity = Identity.loadOrGenerate(crypto, storage)
        val transport = VirtualMeshTransport(identity.keyHash, testScheduler)
        val engine =
            MeshEngine.create(
                identity = identity,
                cryptoProvider = crypto,
                transport = transport,
                storage = storage,
                batteryMonitor = StubBatteryMonitor(level = 0.9f),
                scope = backgroundScope,
                clock = { testScheduler.currentTime },
            )
        assertTrue(PowerTier.entries.contains(engine.currentTier))
        engine.stop()
    }
}
