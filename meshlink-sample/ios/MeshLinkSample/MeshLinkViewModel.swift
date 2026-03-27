// MeshLinkViewModel.swift
// MeshLink iOS Sample — ViewModel managing MeshLink lifecycle and state
//
// This file mirrors the Android sample's MeshLinkViewModel.kt, providing
// equivalent functionality with idiomatic Swift patterns.
//
// ## Kotlin/Native Swift Interop Notes
//
// The MeshLink XCFramework exports Kotlin classes to Swift under the
// `MeshLink` module name. Key mappings:
//
// | Kotlin                              | Swift                                           |
// |-------------------------------------|-------------------------------------------------|
// | `MeshLink(transport, config)`       | `MeshLink.MeshLink(transport:config:...)`   |
// | `MeshLinkConfig.chatOptimized()`    | `MeshLink.MeshLinkConfig.companion.chatOptimized()` |
// | `BleTransport` (interface)          | `MeshLink.BleTransport` (protocol)          |
// | `PeerEvent.Discovered`              | `MeshLink.PeerEventDiscovered`              |
// | `PeerEvent.Lost`                    | `MeshLink.PeerEventLost`                    |
// | `Flow<T>.collect { ... }`           | Requires SKIE or manual `FlowCollector` wrapper |
// | `Result<Unit>`                      | `MeshLink.KotlinResult` (or SKIE mapping)   |
// | `ByteArray`                         | `KotlinByteArray`                               |

import Foundation
import MeshLink

// MARK: - Peer Info

/// Lightweight representation of a discovered mesh peer for the visualizer.
struct PeerInfo: Identifiable, Equatable {
    /// Hex-encoded peer ID (full length).
    let id: String
    /// Last observed RSSI value (dBm). More negative = weaker signal.
    var rssi: Int
    /// Timestamp of the most recent advertisement or keepalive.
    var lastSeen: Date

    /// First 4 hex characters of the peer ID, used as a short label.
    var shortId: String {
        String(id.prefix(4))
    }

    /// Normalized signal quality in `0.0 ... 1.0` (1 = excellent, 0 = very weak).
    ///
    /// Maps RSSI from `[-100, -30]` dBm to `[0, 1]`.
    var signalQuality: Double {
        let clamped = min(max(Double(rssi), -100), -30)
        return (clamped + 100) / 70
    }
}

// MARK: - Config Preset

/// Available MeshLinkConfig preset names.
enum ConfigPreset: String, CaseIterable, Identifiable {
    case chatOptimized = "Chat"
    case fileTransferOptimized = "File Transfer"
    case powerOptimized = "Power Saver"
    case sensorOptimized = "Sensor"

    var id: String { rawValue }

    /// Create the corresponding `MeshLinkConfig`.
    func makeConfig(mtuOverride: Int? = nil) -> MeshLinkConfig {
        // Each preset is a companion factory method on MeshLinkConfig.
        // An optional MTU override can be applied via the builder lambda.
        switch self {
        case .chatOptimized:
            return MeshLinkConfig.companion.chatOptimized()
        case .fileTransferOptimized:
            return MeshLinkConfig.companion.fileTransferOptimized()
        case .powerOptimized:
            return MeshLinkConfig.companion.powerOptimized()
        case .sensorOptimized:
            return MeshLinkConfig.companion.sensorOptimized()
        }
    }
}

// MARK: - ViewModel

/// ObservableObject that wraps MeshLink for SwiftUI consumption.
///
/// Uses `@Published` properties so SwiftUI views automatically re-render
/// when mesh state changes. Compatible with iOS 15+.
///
/// > Note: Kotlin/Native `Flow` collection requires a bridging layer.
/// > If you use the [SKIE](https://skie.touchlab.co) Gradle plugin, Kotlin
/// > `Flow` maps directly to Swift `AsyncSequence`. Without SKIE, you need
/// > a `FlowCollector` wrapper — see `collectFlow(_:)` below.
@MainActor
final class MeshLinkViewModel: ObservableObject {

    // MARK: Published State

    @Published var isRunning = false
    @Published var logEntries: [String] = []
    @Published var peerCount = 0
    @Published var connectedPeers = 0
    @Published var reachablePeers = 0
    @Published var powerMode = "BALANCED"
    @Published var bufferUsagePercent = 0
    @Published var activeTransfers = 0

    /// All currently known peers, updated on discovery/loss events.
    @Published var discoveredPeers: [PeerInfo] = []

    /// Active configuration preset.
    @Published var currentPreset: ConfigPreset = .chatOptimized

    /// Current MTU value from the active config.
    @Published var currentMtu: Int = 185

    /// Max message size from the active config (bytes).
    @Published private(set) var maxMessageSize: Int = 10_000

    /// Buffer capacity from the active config (bytes).
    @Published private(set) var bufferCapacity: Int = 524_288

    // MARK: Private

    private let transport: BleTransport
    private var meshLink: MeshLink

    // MARK: Init

    init() {
        // Use a no-op transport for demonstration.
        // In production, supply your IosBleTransport backed by CoreBluetooth.
        self.transport = DemoTransport()

        // Create a chat-optimized configuration (same as the Android sample).
        let config = MeshLinkConfig.companion.chatOptimized()

        self.meshLink = MeshLink(transport: transport, config: config)
        self.currentMtu = Int(config.mtu)
        self.maxMessageSize = Int(config.maxMessageSize)
        self.bufferCapacity = Int(config.bufferCapacity)

        startCollectingFlows()
    }

    // MARK: - Configuration

    /// Apply a configuration preset, restarting the mesh if it was running.
    func applyPreset(_ preset: ConfigPreset) {
        let wasRunning = isRunning
        if wasRunning { stopMesh() }

        currentPreset = preset
        let config = preset.makeConfig()
        meshLink = MeshLink(transport: transport, config: config)
        currentMtu = Int(config.mtu)
        maxMessageSize = Int(config.maxMessageSize)
        bufferCapacity = Int(config.bufferCapacity)

        startCollectingFlows()
        log("⚙️ Applied preset: \(preset.rawValue)")

        if wasRunning { startMesh() }
    }

    /// Update the MTU value, restarting the mesh if it was running.
    ///
    /// > Note: MeshLinkConfig is immutable once created, so changing the MTU
    /// > requires creating a new MeshLink instance with an updated config.
    func updateMtu(_ mtu: Int) {
        let wasRunning = isRunning
        if wasRunning { stopMesh() }

        currentMtu = mtu
        let config = currentPreset.makeConfig()
        // Reconstruct with the desired MTU using the config copy constructor.
        let updatedConfig = config.doCopy(
            maxMessageSize: config.maxMessageSize,
            bufferCapacity: config.bufferCapacity,
            mtu: Int32(mtu),
            rateLimitMaxSends: config.rateLimitMaxSends,
            rateLimitWindowMs: config.rateLimitWindowMs,
            circuitBreakerMaxFailures: config.circuitBreakerMaxFailures,
            circuitBreakerWindowMs: config.circuitBreakerWindowMs,
            circuitBreakerCooldownMs: config.circuitBreakerCooldownMs,
            diagnosticBufferCapacity: config.diagnosticBufferCapacity,
            dedupCapacity: config.dedupCapacity,
            protocolVersion: config.protocolVersion,
            appId: config.appId,
            inboundRateLimitPerSenderPerMinute: config.inboundRateLimitPerSenderPerMinute,
            gossipIntervalMs: config.gossipIntervalMs,
            pendingMessageTtlMs: config.pendingMessageTtlMs,
            pendingMessageCapacity: config.pendingMessageCapacity,
            broadcastRateLimitPerMinute: config.broadcastRateLimitPerMinute,
            relayQueueCapacity: config.relayQueueCapacity,
            maxHops: config.maxHops,
            ackWindowMin: config.ackWindowMin,
            ackWindowMax: config.ackWindowMax,
            powerModeThresholds: config.powerModeThresholds,
            l2capEnabled: config.l2capEnabled,
            l2capRetryAttempts: config.l2capRetryAttempts,
            chunkInactivityTimeoutMs: config.chunkInactivityTimeoutMs,
            bufferTtlMs: config.bufferTtlMs,
            triggeredUpdateThreshold: config.triggeredUpdateThreshold,
            triggeredUpdateBatchMs: config.triggeredUpdateBatchMs,
            keepaliveIntervalMs: config.keepaliveIntervalMs,
            tombstoneWindowMs: config.tombstoneWindowMs,
            handshakeRateLimitPerSec: config.handshakeRateLimitPerSec,
            nackRateLimitPerSec: config.nackRateLimitPerSec,
            neighborAggregateLimitPerMin: config.neighborAggregateLimitPerMin,
            senderNeighborLimitPerMin: config.senderNeighborLimitPerMin
        )

        meshLink = MeshLink(transport: transport, config: updatedConfig)
        startCollectingFlows()
        log("⚙️ MTU updated to \(mtu)")

        if wasRunning { startMesh() }
    }

    /// Reset configuration back to defaults (chat-optimized, default MTU).
    func resetToDefaults() {
        applyPreset(.chatOptimized)
        updateMtu(185)
        log("⚙️ Reset to defaults")
    }

    // MARK: - Mesh Lifecycle

    /// Start the mesh network — begins BLE advertising and scanning.
    func startMesh() {
        let result = meshLink.start()
        if result.isSuccess() {
            isRunning = true
            log("🟢 Mesh started")
        } else {
            log("❌ Start failed: \(result.exceptionOrNull()?.message ?? "unknown error")")
        }
    }

    /// Stop the mesh network — tears down all BLE connections.
    func stopMesh() {
        meshLink.stop()
        isRunning = false
        log("🔴 Mesh stopped")
    }

    // MARK: - Messaging

    /// Send a unicast message to a specific peer.
    ///
    /// - Parameters:
    ///   - recipientHex: The recipient peer ID as a hex string (e.g. "0a1b2c3d...")
    ///   - message: The text message to send
    func sendMessage(to recipientHex: String, message: String) {
        guard let recipientBytes = hexToKotlinByteArray(recipientHex) else {
            log("❌ Invalid recipient hex: \(recipientHex)")
            return
        }

        let payloadBytes = stringToKotlinByteArray(message)
        let result = meshLink.send(recipient: recipientBytes, payload: payloadBytes)

        if result.isSuccess() {
            log("📤 Sent to \(recipientHex): \(message)")
        } else {
            log("❌ Send failed: \(result.exceptionOrNull()?.message ?? "unknown error")")
        }
    }

    // MARK: - Flow Collection

    /// Collect Kotlin `Flow` emissions using a bridging pattern.
    ///
    /// Kotlin/Native exports `Flow<T>` as a protocol. Without SKIE, you
    /// create a `FlowCollector` conformance that receives each emitted value.
    /// With SKIE, you can use `for await value in flow { }` instead.
    private func startCollectingFlows() {
        // Collect incoming messages
        collectFlow(meshLink.messages) { [weak self] (message: Message) in
            let senderHex = kotlinByteArrayToHex(message.senderId)
            let text = kotlinByteArrayToString(message.payload)
            self?.log("📨 Message from \(senderHex): \(text)")
        }

        // Collect peer discovery / loss events
        collectFlow(meshLink.peers) { [weak self] (event: PeerEvent) in
            if let discovered = event as? PeerEvent.Discovered {
                let peerHex = kotlinByteArrayToHex(discovered.peerId)
                self?.log("🔵 Peer discovered: \(peerHex)")
                self?.peerCount += 1

                // Add to discovered peers for the visualizer.
                // Use a simulated RSSI since BleTransport doesn't expose it.
                let info = PeerInfo(id: peerHex, rssi: Int.random(in: -85 ... -35), lastSeen: Date())
                if let idx = self?.discoveredPeers.firstIndex(where: { $0.id == peerHex }) {
                    self?.discoveredPeers[idx].rssi = info.rssi
                    self?.discoveredPeers[idx].lastSeen = info.lastSeen
                } else {
                    self?.discoveredPeers.append(info)
                }
            } else if let lost = event as? PeerEvent.Lost {
                let peerHex = kotlinByteArrayToHex(lost.peerId)
                self?.log("🔴 Peer lost: \(peerHex)")
                self?.peerCount = max(0, (self?.peerCount ?? 1) - 1)

                self?.discoveredPeers.removeAll { $0.id == peerHex }
            }
        }

        // Collect mesh health snapshots
        collectFlow(meshLink.meshHealthFlow) { [weak self] (snapshot: MeshHealthSnapshot) in
            self?.connectedPeers = Int(snapshot.connectedPeers)
            self?.reachablePeers = Int(snapshot.reachablePeers)
            self?.powerMode = snapshot.powerMode
            self?.bufferUsagePercent = Int(snapshot.bufferUtilizationPercent)
            self?.activeTransfers = Int(snapshot.activeTransfers)
            self?.log("💓 Health: peers=\(snapshot.connectedPeers), mode=\(snapshot.powerMode)")
        }

        // Collect delivery confirmations
        collectFlow(meshLink.deliveryConfirmations) { [weak self] (uuid: KotlinUuid) in
            self?.log("✅ Delivered: \(uuid)")
        }

        // Collect transfer failures
        collectFlow(meshLink.transferFailures) { [weak self] (failure: TransferFailure) in
            self?.log("❌ Transfer failed: \(failure.messageId)")
        }
    }

    // MARK: - Logging

    private func log(_ entry: String) {
        logEntries.append(entry)
    }

    func clearLog() {
        logEntries.removeAll()
    }
}

// MARK: - Kotlin Flow → Swift Bridge

/// Generic flow collector that forwards emissions to a Swift closure.
///
/// Kotlin/Native exports `kotlinx.coroutines.flow.FlowCollector` as a
/// protocol. This class conforms to it and dispatches values to the
/// main actor for UI updates.
///
/// **With SKIE:** You don't need this — use `asyncSequence()` directly:
/// ```swift
/// for await message in meshLink.messages {
///     // handle message
/// }
/// ```
///
/// **Without SKIE:** Use this collector pattern:
/// ```swift
/// collectFlow(meshLink.messages) { message in
///     print("Got: \(message)")
/// }
/// ```
private class SwiftFlowCollector<T: AnyObject>: FlowCollector {
    let callback: (T) -> Void

    init(callback: @escaping (T) -> Void) {
        self.callback = callback
    }

    func emit(value: Any?) async throws {
        if let typedValue = value as? T {
            await MainActor.run {
                callback(typedValue)
            }
        }
    }
}

/// Launch a Kotlin Flow collection in a detached Swift Task.
///
/// The flow runs on Kotlin's coroutine dispatcher and calls back
/// on the main actor via `SwiftFlowCollector`.
private func collectFlow<T: AnyObject>(_ flow: Kotlinx_coroutines_coreFlow, callback: @escaping (T) -> Void) {
    Task.detached {
        let collector = SwiftFlowCollector<T>(callback: callback)
        do {
            try await flow.collect(collector: collector)
        } catch {
            // Flow completed or was cancelled — expected during shutdown.
            print("Flow collection ended: \(error.localizedDescription)")
        }
    }
}

// MARK: - Byte Array Helpers

/// Convert a hex string to a Kotlin `ByteArray` (`KotlinByteArray`).
private func hexToKotlinByteArray(_ hex: String) -> KotlinByteArray? {
    let cleanHex = hex.replacingOccurrences(of: " ", with: "")
    guard cleanHex.count % 2 == 0 else { return nil }

    var bytes: [UInt8] = []
    var index = cleanHex.startIndex
    while index < cleanHex.endIndex {
        let nextIndex = cleanHex.index(index, offsetBy: 2)
        guard let byte = UInt8(cleanHex[index..<nextIndex], radix: 16) else { return nil }
        bytes.append(byte)
        index = nextIndex
    }

    let kotlinBytes = KotlinByteArray(size: Int32(bytes.count))
    for (i, byte) in bytes.enumerated() {
        kotlinBytes.set(index: Int32(i), value: Int8(bitPattern: byte))
    }
    return kotlinBytes
}

/// Convert a Swift `String` to a Kotlin `ByteArray` (UTF-8 encoded).
private func stringToKotlinByteArray(_ string: String) -> KotlinByteArray {
    let data = Array(string.utf8)
    let kotlinBytes = KotlinByteArray(size: Int32(data.count))
    for (i, byte) in data.enumerated() {
        kotlinBytes.set(index: Int32(i), value: Int8(bitPattern: byte))
    }
    return kotlinBytes
}

/// Convert a Kotlin `ByteArray` to a hex string.
private func kotlinByteArrayToHex(_ bytes: KotlinByteArray) -> String {
    (0..<bytes.size).map { i in
        String(format: "%02x", UInt8(bitPattern: bytes.get(index: i)))
    }.joined()
}

/// Convert a Kotlin `ByteArray` to a Swift `String` (UTF-8).
private func kotlinByteArrayToString(_ bytes: KotlinByteArray) -> String {
    let data = (0..<bytes.size).map { i in
        UInt8(bitPattern: bytes.get(index: i))
    }
    return String(bytes: data, encoding: .utf8) ?? "<invalid UTF-8>"
}

// MARK: - Demo Transport

/// No-op BLE transport for demonstration purposes.
///
/// In a real app, implement `BleTransport` using CoreBluetooth:
/// - `CBCentralManager` for scanning / connecting
/// - `CBPeripheralManager` for advertising / GATT server
///
/// See the MeshLink integration guide for details.
private class DemoTransport: BleTransport {

    var localPeerId: KotlinByteArray {
        let bytes = KotlinByteArray(size: 16)
        for i: Int32 in 0..<16 {
            bytes.set(index: i, value: i)
        }
        return bytes
    }

    func startAdvertisingAndScanning() async throws {
        // No-op: In production, start CBCentralManager scanning
        // and CBPeripheralManager advertising here.
    }

    func stopAll() async throws {
        // No-op: In production, stop all CoreBluetooth activity.
    }

    var advertisementEvents: Kotlinx_coroutines_coreFlow {
        // Return an empty flow. In production, emit AdvertisementEvent
        // instances when peripheral advertisements are received.
        MutableSharedFlow<AdvertisementEvent>(
            replay: 0,
            extraBufferCapacity: 0,
            onBufferOverflow: .suspend
        )
    }

    var peerLostEvents: Kotlinx_coroutines_coreFlow {
        MutableSharedFlow<PeerLostEvent>(
            replay: 0,
            extraBufferCapacity: 0,
            onBufferOverflow: .suspend
        )
    }

    func sendToPeer(peerId: KotlinByteArray, data: KotlinByteArray) async throws {
        // No-op: In production, write data to the peer's GATT characteristic
        // or L2CAP channel.
    }

    var incomingData: Kotlinx_coroutines_coreFlow {
        MutableSharedFlow<IncomingData>(
            replay: 0,
            extraBufferCapacity: 0,
            onBufferOverflow: .suspend
        )
    }
}
