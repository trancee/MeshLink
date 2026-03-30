// MeshLinkViewModel.swift
// MeshLink macOS Sample — ViewModel managing MeshLink lifecycle and state
//
// Uses `MeshLinkFactory` to create a MeshLink instance backed by
// `IosBleTransport` (CoreBluetooth). Identical API to the iOS sample.

import Foundation
import MeshLink

// MARK: - Peer Info

struct PeerInfo: Identifiable, Equatable {
    let id: String
    var rssi: Int
    var lastSeen: Date
    var firstSeen: Date

    var shortId: String { String(id.prefix(4)) }

    var signalQuality: Double {
        let clamped = min(max(Double(rssi), -100), -30)
        return (clamped + 100) / 70
    }
}

// MARK: - Diagnostic Entry

struct DiagnosticEntry: Identifiable {
    let id: String
    let code: String
    let severity: String
    let timestamp: String
    let payload: String?
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
            return MeshLinkConfig.companion.chatOptimized { _ in }
        case .fileTransferOptimized:
            return MeshLinkConfig.companion.fileTransferOptimized { _ in }
        case .powerOptimized:
            return MeshLinkConfig.companion.powerOptimized { _ in }
        case .sensorOptimized:
            return MeshLinkConfig.companion.sensorOptimized { _ in }
        }
    }
}

// MARK: - ViewModel

@MainActor
final class MeshLinkViewModel: ObservableObject {

    private var diagnosticCounter = 0

    @Published var isRunning = false
    @Published var logEntries: [String] = []
    @Published var peerCount = 0
    @Published var connectedPeers = 0
    @Published var reachablePeers = 0
    @Published var powerMode = "BALANCED"
    @Published var bufferUsagePercent = 0
    @Published var activeTransfers = 0
    @Published var discoveredPeers: [PeerInfo] = []
    @Published var selectedPeerId: String?
    @Published var currentPreset: ConfigPreset = .chatOptimized
    @Published var currentMtu: Int = 185
    @Published private(set) var maxMessageSize: Int = 10_000
    @Published private(set) var bufferCapacity: Int = 524_288
    @Published var diagnosticEntries: [DiagnosticEntry] = []

    private var meshLink: MeshLink

    init() {
        // Enable BLE debug logging — output appears in Xcode console
        IosBleTransport.companion.debugLogging = true

        let config = ConfigPreset.chatOptimized.makeConfig()
        self.meshLink = MeshLinkFactory.shared.create(config: config)
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
        meshLink = MeshLinkFactory.shared.create(config: config)
        currentMtu = Int(config.mtu)
        maxMessageSize = Int(config.maxMessageSize)
        bufferCapacity = Int(config.bufferCapacity)
        startCollectingFlows()
        log("⚙️ Applied preset: \(preset.rawValue)")

        if wasRunning { startMesh() }
    }

    func resetToDefaults() {
        applyPreset(.chatOptimized)
        log("⚙️ Reset to defaults")
    }

    // MARK: - Peer Selection

    func selectPeer(_ peerId: String?) {
        selectedPeerId = peerId
    }

    func peerDetail(_ peerIdHex: String) -> PeerDetail? {
        return meshLink.peerDetail(peerIdHex: peerIdHex)
    }

    // MARK: - Mesh Lifecycle

    func startMesh() {
        let result = meshLink.start()
        guard result != nil else {
            log("❌ Start failed — check Bluetooth is enabled and permissions are granted")
            return
        }
        isRunning = true
        log("🟢 Mesh started — scanning for peers via BLE")
    }

    func stopMesh() {
        meshLink.stop()
        isRunning = false
        discoveredPeers.removeAll()
        peerCount = 0
        connectedPeers = 0
        reachablePeers = 0
        activeTransfers = 0
        bufferUsagePercent = 0
        powerMode = "BALANCED"
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
        if result != nil {
            log("📤 Sent to \(String(recipientHex.prefix(8)))…: \(message)")
        } else {
            log("❌ Send failed")
        }
    }

    func broadcastMessage(_ message: String) {
        let payloadBytes = stringToKotlinByteArray(message)
        let _ = meshLink.broadcast(payload: payloadBytes, maxHops: 3)
        log("📡 Broadcast: \(message)")
    }

    // MARK: - Flow Collection

    private func startCollectingFlows() {
        collectFlow(meshLink.messages) { [weak self] (message: Message) in
            let senderHex = kotlinByteArrayToHex(message.senderId)
            let text = kotlinByteArrayToString(message.payload)
            self?.log("📨 From \(String(senderHex.prefix(8)))…: \(text)")
        }

        collectFlow(meshLink.peers) { [weak self] (event: PeerEvent) in
            if let found = event as? PeerEvent.Found {
                let peerHex = kotlinByteArrayToHex(found.peerId)
                self?.log("🔵 Peer: \(String(peerHex.prefix(8)))…")
                self?.peerCount += 1
                let now = Date()
                let existing = self?.discoveredPeers.first { $0.id == peerHex }
                let info = PeerInfo(
                    id: peerHex,
                    rssi: Int.random(in: -85 ... -35),
                    lastSeen: now,
                    firstSeen: existing?.firstSeen ?? now
                )
                if let idx = self?.discoveredPeers.firstIndex(where: { $0.id == peerHex }) {
                    self?.discoveredPeers[idx].rssi = info.rssi
                    self?.discoveredPeers[idx].lastSeen = info.lastSeen
                } else {
                    self?.discoveredPeers.append(info)
                }
            } else if let lost = event as? PeerEvent.Lost {
                let peerHex = kotlinByteArrayToHex(lost.peerId)
                self?.log("🔴 Lost: \(String(peerHex.prefix(8)))…")
                self?.peerCount = max(0, (self?.peerCount ?? 1) - 1)
                self?.discoveredPeers.removeAll { $0.id == peerHex }
            }
        }

        collectFlow(meshLink.transferFailures) { [weak self] (failure: TransferFailure) in
            self?.log("❌ Transfer failed: \(failure.messageId)")
        }

        collectFlow(meshLink.diagnosticEvents) { [weak self] (event: DiagnosticEvent) in
            guard let self else { return }
            self.diagnosticCounter += 1
            let entry = DiagnosticEntry(
                id: "\(event.monotonicMillis)-\(event.code.name)-\(self.diagnosticCounter)",
                code: event.code.name,
                severity: event.severity.name,
                timestamp: Self.formatTimestamp(event.monotonicMillis),
                payload: event.payload
            )
            if self.diagnosticEntries.count > 500 {
                self.diagnosticEntries.removeFirst()
            }
            self.diagnosticEntries.append(entry)
        }
    }

    // MARK: - Health

    func refreshHealth() {
        let snapshot = meshLink.meshHealth()
        connectedPeers = Int(snapshot.connectedPeers)
        reachablePeers = Int(snapshot.reachablePeers)
        powerMode = snapshot.powerMode
        bufferUsagePercent = Int(snapshot.bufferUtilizationPercent)
        activeTransfers = Int(snapshot.activeTransfers)
    }

    // MARK: - Logging

    private func log(_ entry: String) { logEntries.append(entry) }
    func clearLog() { logEntries.removeAll() }
    func clearDiagnostics() { diagnosticEntries.removeAll() }

    private static func formatTimestamp(_ monotonicMillis: Int64) -> String {
        let totalSeconds = monotonicMillis / 1000
        let minutes = (totalSeconds / 60) % 60
        let seconds = totalSeconds % 60
        let millis = monotonicMillis % 1000
        return String(format: "%02d:%02d.%03d", minutes, seconds, millis)
    }
}

// MARK: - Kotlin Flow → Swift Bridge

private class SwiftFlowCollector<T: AnyObject>: Kotlinx_coroutines_coreFlowCollector {
    let callback: (T) -> Void
    init(callback: @escaping (T) -> Void) { self.callback = callback }

    func emit(value: Any?) async throws {
        if let typedValue = value as? T {
            await MainActor.run { callback(typedValue) }
        }
    }
}

private func collectFlow<T: AnyObject>(
    _ flow: Kotlinx_coroutines_coreFlow,
    callback: @escaping (T) -> Void
) {
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
