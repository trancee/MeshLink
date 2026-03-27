// MeshLinkViewModel.swift
// MeshLink macOS Sample — ViewModel managing MeshLink lifecycle and state
//
// Mirrors the iOS sample's MeshLinkViewModel with identical functionality.
// The macOS sample uses the same MeshLink XCFramework via SPM.

import Foundation
import MeshLink

// MARK: - Peer Info

struct PeerInfo: Identifiable, Equatable {
    let id: String
    var rssi: Int
    var lastSeen: Date

    var shortId: String { String(id.prefix(4)) }

    var signalQuality: Double {
        let clamped = min(max(Double(rssi), -100), -30)
        return (clamped + 100) / 70
    }
}

// MARK: - Config Preset

enum ConfigPreset: String, CaseIterable, Identifiable {
    case chatOptimized = "Chat"
    case fileTransferOptimized = "File Transfer"
    case powerOptimized = "Power Saver"
    case sensorOptimized = "Sensor"

    var id: String { rawValue }

    func makeConfig() -> MeshLinkConfig {
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

@MainActor
final class MeshLinkViewModel: ObservableObject {

    @Published var isRunning = false
    @Published var logEntries: [String] = []
    @Published var peerCount = 0
    @Published var connectedPeers = 0
    @Published var reachablePeers = 0
    @Published var powerMode = "BALANCED"
    @Published var bufferUsagePercent = 0
    @Published var activeTransfers = 0
    @Published var discoveredPeers: [PeerInfo] = []
    @Published var currentPreset: ConfigPreset = .chatOptimized
    @Published var currentMtu: Int = 185
    @Published private(set) var maxMessageSize: Int = 10_000
    @Published private(set) var bufferCapacity: Int = 524_288

    private let transport: BleTransport
    private var meshLink: MeshLink

    init() {
        self.transport = DemoTransport()
        let config = MeshLinkConfig.companion.chatOptimized()
        self.meshLink = MeshLink(transport: transport, config: config)
        self.currentMtu = Int(config.mtu)
        self.maxMessageSize = Int(config.maxMessageSize)
        self.bufferCapacity = Int(config.bufferCapacity)
        startCollectingFlows()
    }

    // MARK: - Configuration

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

    func updateMtu(_ mtu: Int) {
        let wasRunning = isRunning
        if wasRunning { stopMesh() }

        currentMtu = mtu
        let config = currentPreset.makeConfig()
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

    func resetToDefaults() {
        applyPreset(.chatOptimized)
        updateMtu(185)
        log("⚙️ Reset to defaults")
    }

    // MARK: - Mesh Lifecycle

    func startMesh() {
        let result = meshLink.start()
        if result.isSuccess() {
            isRunning = true
            log("🟢 Mesh started")
        } else {
            log("❌ Start failed: \(result.exceptionOrNull()?.message ?? "unknown")")
        }
    }

    func stopMesh() {
        meshLink.stop()
        isRunning = false
        log("🔴 Mesh stopped")
    }

    // MARK: - Messaging

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
            log("❌ Send failed: \(result.exceptionOrNull()?.message ?? "unknown")")
        }
    }

    // MARK: - Flow Collection

    private func startCollectingFlows() {
        collectFlow(meshLink.messages) { [weak self] (message: Message) in
            let senderHex = kotlinByteArrayToHex(message.senderId)
            let text = kotlinByteArrayToString(message.payload)
            self?.log("📨 From \(senderHex): \(text)")
        }
        collectFlow(meshLink.peers) { [weak self] (event: PeerEvent) in
            if let discovered = event as? PeerEvent.Discovered {
                let peerHex = kotlinByteArrayToHex(discovered.peerId)
                self?.log("🔵 Peer discovered: \(peerHex)")
                self?.peerCount += 1
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
        collectFlow(meshLink.meshHealthFlow) { [weak self] (snapshot: MeshHealthSnapshot) in
            self?.connectedPeers = Int(snapshot.connectedPeers)
            self?.reachablePeers = Int(snapshot.reachablePeers)
            self?.powerMode = snapshot.powerMode
            self?.bufferUsagePercent = Int(snapshot.bufferUtilizationPercent)
            self?.activeTransfers = Int(snapshot.activeTransfers)
        }
        collectFlow(meshLink.deliveryConfirmations) { [weak self] (uuid: KotlinUuid) in
            self?.log("✅ Delivered: \(uuid)")
        }
        collectFlow(meshLink.transferFailures) { [weak self] (failure: TransferFailure) in
            self?.log("❌ Transfer failed: \(failure.messageId)")
        }
    }

    // MARK: - Logging

    private func log(_ entry: String) { logEntries.append(entry) }
    func clearLog() { logEntries.removeAll() }
}

// MARK: - Kotlin Flow → Swift Bridge

private class SwiftFlowCollector<T: AnyObject>: FlowCollector {
    let callback: (T) -> Void
    init(callback: @escaping (T) -> Void) { self.callback = callback }

    func emit(value: Any?) async throws {
        if let typedValue = value as? T {
            await MainActor.run { callback(typedValue) }
        }
    }
}

private func collectFlow<T: AnyObject>(_ flow: Kotlinx_coroutines_coreFlow, callback: @escaping (T) -> Void) {
    Task.detached {
        let collector = SwiftFlowCollector<T>(callback: callback)
        do {
            try await flow.collect(collector: collector)
        } catch {
            print("Flow collection ended: \(error.localizedDescription)")
        }
    }
}

// MARK: - Byte Array Helpers

private func hexToKotlinByteArray(_ hex: String) -> KotlinByteArray? {
    let clean = hex.replacingOccurrences(of: " ", with: "")
    guard clean.count % 2 == 0 else { return nil }
    var bytes: [UInt8] = []
    var index = clean.startIndex
    while index < clean.endIndex {
        let next = clean.index(index, offsetBy: 2)
        guard let byte = UInt8(clean[index..<next], radix: 16) else { return nil }
        bytes.append(byte)
        index = next
    }
    let kb = KotlinByteArray(size: Int32(bytes.count))
    for (i, b) in bytes.enumerated() { kb.set(index: Int32(i), value: Int8(bitPattern: b)) }
    return kb
}

private func stringToKotlinByteArray(_ string: String) -> KotlinByteArray {
    let data = Array(string.utf8)
    let kb = KotlinByteArray(size: Int32(data.count))
    for (i, b) in data.enumerated() { kb.set(index: Int32(i), value: Int8(bitPattern: b)) }
    return kb
}

private func kotlinByteArrayToHex(_ bytes: KotlinByteArray) -> String {
    (0..<bytes.size).map { String(format: "%02x", UInt8(bitPattern: bytes.get(index: $0))) }.joined()
}

private func kotlinByteArrayToString(_ bytes: KotlinByteArray) -> String {
    let data = (0..<bytes.size).map { UInt8(bitPattern: bytes.get(index: $0)) }
    return String(bytes: data, encoding: .utf8) ?? "<invalid UTF-8>"
}

// MARK: - Demo Transport

private class DemoTransport: BleTransport {
    var localPeerId: KotlinByteArray {
        let bytes = KotlinByteArray(size: 16)
        for i: Int32 in 0..<16 { bytes.set(index: i, value: i) }
        return bytes
    }

    func startAdvertisingAndScanning() async throws {}
    func stopAll() async throws {}

    var advertisementEvents: Kotlinx_coroutines_coreFlow {
        MutableSharedFlow<AdvertisementEvent>(replay: 0, extraBufferCapacity: 0, onBufferOverflow: .suspend)
    }
    var peerLostEvents: Kotlinx_coroutines_coreFlow {
        MutableSharedFlow<PeerLostEvent>(replay: 0, extraBufferCapacity: 0, onBufferOverflow: .suspend)
    }
    func sendToPeer(peerId: KotlinByteArray, data: KotlinByteArray) async throws {}
    var incomingData: Kotlinx_coroutines_coreFlow {
        MutableSharedFlow<IncomingData>(replay: 0, extraBufferCapacity: 0, onBufferOverflow: .suspend)
    }
}
