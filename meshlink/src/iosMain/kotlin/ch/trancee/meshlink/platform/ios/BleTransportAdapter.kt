@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.apple.BleTransportBridgeRegistry
import ch.trancee.meshlink.power.PowerPolicy
import ch.trancee.meshlink.transport.BleDiscoveryPayload
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.transport.shouldUseMixedPlatformGattNotifyBearer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBManager
import platform.CoreBluetooth.CBMutableCharacteristic
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBPeripheralManager

internal class BleTransportAdapter(internal val appId: String, advertisementKeyHash: ByteArray) :
    BleTransport {
    internal val mutableEvents = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 32)
    internal val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val localKeyHash: ByteArray = advertisementKeyHash.copyOf()
    internal val telemetryEnabled: Boolean = readEnvironmentFlag(TRANSPORT_TELEMETRY_ENV)
    internal val transportDebugLoggingEnabled: Boolean = readEnvironmentFlag(TRANSPORT_DEBUG_ENV)
    internal val peerBindings = PeerBindings()
    internal val peerRegistry = PeerRegistry(peerBindings)
    internal val activeLinksByHint: MutableMap<String, L2capLink> = linkedMapOf()
    internal val gattNotifyRegistry = BleTransportGattNotifyRegistry()
    internal val pendingConnectionsByHint: MutableMap<String, String> = linkedMapOf()

    internal var currentPowerProfile: PowerProfile = PowerMonitor.defaultProfile()
    internal var currentDiscoveryPayload: BleDiscoveryPayload =
        discoveryPayload(l2capPsm = NO_ADVERTISED_L2CAP_PSM)
    internal var centralManager: CBCentralManager? = null
    internal var peripheralManager: CBPeripheralManager? = null
    internal var gattNotifyServiceInstalled: Boolean = false
    internal var gattNotifyServiceCharacteristic: CBMutableCharacteristic? = null
    internal var started: Boolean = false
    internal var discoverySuspended: Boolean = false

    private val centralDelegate = CentralDelegate(this)
    internal val peripheralClientDelegate: CBPeripheralDelegateProtocol =
        PeripheralClientDelegate(this)
    private val peripheralManagerDelegate = PeripheralManagerDelegate(this)

    override val events: Flow<TransportEvent> = mutableEvents.asSharedFlow()

    override suspend fun start(): Unit {
        BlePermissionContract.ensureBluetoothAuthorized(CBManager.authorization)
        reportLog("start transport authorization=${CBManager.authorization}")
        started = true
        centralManager = CBCentralManager(delegate = centralDelegate, queue = null)
        peripheralManager = CBPeripheralManager(delegate = peripheralManagerDelegate, queue = null)
    }

    override suspend fun pause(): Unit {
        reportLog("pause transport")
        stopTransport(clearPeers = false)
        started = false
    }

    override suspend fun resume(): Unit {
        reportLog("resume transport started=$started")
        if (!started) {
            start()
        }
    }

    override suspend fun stop(): Unit {
        reportLog("stop transport")
        stopTransport(clearPeers = true)
        started = false
    }

    override suspend fun updatePowerPolicy(policy: PowerPolicy): Unit {
        currentPowerProfile = PowerMonitor.profileFor(policy)
        currentDiscoveryPayload = discoveryPayload(currentDiscoveryPayload.l2capPsm)
        if (!started) {
            return
        }
        refreshDiscoveryState()
    }

    override suspend fun setDiscoverySuspended(suspended: Boolean): Unit {
        if (discoverySuspended == suspended) {
            return
        }
        discoverySuspended = suspended
        if (!started) {
            return
        }
        log("discovery suspended=$suspended")
        refreshDiscoveryState()
    }

    override fun maximumPayloadBytesPerDelivery(peerId: PeerId): Int? {
        val gattNotifyBearerEnabled = BleTransportBridgeRegistry.isGattNotifyBearerEnabled()
        val peer = if (gattNotifyBearerEnabled) resolvePeer(peerId) else null
        val supportsGattNotifyBearer =
            peer != null &&
                shouldUseMixedPlatformGattNotifyBearer(
                    localPlatformFamily = currentDiscoveryPayload.platformFamily,
                    remotePlatformFamily = peer.platformFamily,
                )
        return if (supportsGattNotifyBearer) {
            GattNotifyLink.maximumPayloadBytesPerDelivery()
        } else {
            null
        }
    }

    override suspend fun clearQueuedOutboundFrames(peerId: PeerId): Unit {
        val peer = resolvePeer(peerId) ?: return
        val discardedL2capFrames = activeLinkFor(peer)?.discardQueuedFrames() ?: 0
        if (discardedL2capFrames > 0) {
            log(
                "discarded $discardedL2capFrames queued L2CAP frames for ${peer.hintPeerId.logSuffix()}"
            )
        }
        val discardedGattFrames = activeGattNotifyLinkFor(peer)?.discardQueuedFrames() ?: 0
        if (discardedGattFrames > 0) {
            log(
                "discarded $discardedGattFrames queued GATT notify frames for ${peer.hintPeerId.logSuffix()}"
            )
        }
    }

    override suspend fun send(frame: OutboundFrame): TransportSendResult {
        return if (!started) {
            dropSend(
                frame,
                message = "iOS BLE transport is not started",
                detail = "transport not started",
            )
        } else {
            sendWhenStarted(frame)
        }
    }
}
