package ch.trancee.meshlink.engine

import ch.trancee.meshlink.crypto.Identity
import ch.trancee.meshlink.crypto.IosCryptoProvider
import ch.trancee.meshlink.messaging.Delivered
import ch.trancee.meshlink.messaging.InboundMessage
import ch.trancee.meshlink.messaging.SendResult
import ch.trancee.meshlink.power.FixedBatteryMonitor
import ch.trancee.meshlink.power.PowerTier
import ch.trancee.meshlink.routing.PeerEvent
import ch.trancee.meshlink.storage.IosSecureStorage
import ch.trancee.meshlink.transfer.Priority
import ch.trancee.meshlink.transport.BleTransportConfig
import ch.trancee.meshlink.transport.IosBleTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import platform.Foundation.NSDate

/**
 * Public Kotlin/Native facade that Swift/Obj-C callers use to operate a MeshLink node on iOS.
 *
 * All internal dependencies ([IosBleTransport], [IosCryptoProvider], [IosSecureStorage],
 * [Identity]) are created internally so Swift code never needs to instantiate internal types.
 * [MeshEngine.create] is also internal; this class lives inside the `:meshlink` module and
 * therefore has full access to the internal API.
 *
 * ## Typical Swift usage
 *
 * ```swift
 * let scope = ... // a CoroutineScope from the KMP coroutines helper
 * let node = MeshNode(
 *     appId: "com.example.myapp",
 *     restorationIdentifier: "com.example.myapp.ble",
 *     config: MeshEngineConfig(),
 *     scope: scope
 * )
 * try await node.start()
 * ```
 *
 * @param appId Application identifier used to derive the 16-bit BLE mesh hash (FNV-1a XOR-fold).
 *   Should match the Android side so both platforms filter the same advertisements.
 * @param restorationIdentifier [CBCentralManager] restoration identifier for iOS background state
 *   preservation. Must be unique per application.
 * @param config Aggregate engine configuration. Defaults produce reasonable behaviour for a
 *   two-device integration test.
 * @param scope [CoroutineScope] that governs this node's lifetime. Cancel to shut down cleanly.
 */
class MeshNode(
    appId: String = "ch.trancee.meshlink",
    restorationIdentifier: String = "ch.trancee.meshlink",
    config: MeshEngineConfig = MeshEngineConfig(),
    scope: CoroutineScope,
) {
    private val cryptoProvider = IosCryptoProvider()
    private val storage = IosSecureStorage()
    private val identity = Identity.loadOrGenerate(cryptoProvider, storage)
    private val powerTierFlow = MutableStateFlow(PowerTier.BALANCED)

    private val transport =
        IosBleTransport(
            config = BleTransportConfig(appId = appId),
            cryptoProvider = cryptoProvider,
            identity = identity,
            scope = scope,
            powerTierFlow = powerTierFlow,
            restorationIdentifier = restorationIdentifier,
        )

    private val engine: MeshEngine =
        MeshEngine.create(
            identity = identity,
            cryptoProvider = cryptoProvider,
            transport = transport,
            storage = storage,
            batteryMonitor = FixedBatteryMonitor(),
            scope = scope,
            clock = { (NSDate().timeIntervalSince1970 * 1_000.0).toLong() },
            config = config,
        )

    // ── Public flows (exported to Swift) ─────────────────────────────────────

    /** Inbound messages delivered to this node. Each emission is a complete application payload. */
    val messages: SharedFlow<InboundMessage> = engine.messages

    /** Delivery confirmations for unicast messages sent by this node. */
    val deliveryConfirmations: SharedFlow<Delivered> = engine.deliveryConfirmations

    /** Peer join/leave events observed by the local BLE scanner. */
    val peerEvents: Flow<PeerEvent> = engine.peerEvents

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Starts BLE scanning/advertising and launches all mesh engine coroutines. Must be called from
     * a coroutine context compatible with the [scope] passed at construction.
     */
    suspend fun start(): Unit = engine.start()

    /**
     * Stops the mesh engine, cancels all engine coroutines, and halts BLE activity. Safe to call
     * multiple times; subsequent calls are no-ops.
     */
    suspend fun stop(): Unit = engine.stop()

    // ── Messaging ─────────────────────────────────────────────────────────────

    /**
     * Sends [payload] to [recipient] (unicast).
     *
     * @param recipient 32-byte key-hash identifying the target peer (from [PeerEvent.peerKeyHash]).
     * @param payload Application bytes. Maximum size is defined by
     *   [MeshEngineConfig.maxPayloadSize].
     * @param priority Delivery priority (default [Priority.NORMAL]).
     * @return [SendResult.Sent] if accepted for immediate transmission, [SendResult.Queued] if
     *   deferred due to congestion or peer absence.
     */
    suspend fun send(
        recipient: ByteArray,
        payload: ByteArray,
        priority: Priority = Priority.NORMAL,
    ): SendResult = engine.send(recipient, payload, priority)
}
