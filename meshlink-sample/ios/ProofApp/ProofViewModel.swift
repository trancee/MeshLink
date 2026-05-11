import Foundation
import MeshLink
import SwiftUI

@MainActor
final class ProofViewModel: ObservableObject {
    @Published private(set) var stateText: String = "Uninitialized"
    @Published private(set) var peers: [PeerId] = []
    @Published private(set) var logs: [String] = []

    private let api: MeshLinkApi
    private let logFileUrl: URL
    private var autoSendPeers: Set<String> = []
    private lazy var stateCollector: FlowCollector = FlowCollector { [weak self] value in
        self?.stateText = String(describing: value ?? "Unknown")
    }
    private lazy var peerCollector: FlowCollector = FlowCollector { [weak self] value in
        self?.handlePeerEvent(value)
    }
    private lazy var diagnosticCollector: FlowCollector = FlowCollector { [weak self] value in
        self?.handleDiagnosticEvent(value)
    }
    private lazy var messageCollector: FlowCollector = FlowCollector { [weak self] value in
        self?.handleInboundMessage(value)
    }

    init() {
        logFileUrl =
            FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("proof.log")
        try? FileManager.default.removeItem(at: logFileUrl)
        let config = MeshLinkConfigKt.meshLinkConfig { builder in
            builder.appId = "demo.meshlink"
            builder.regulatoryRegion = RegulatoryRegion.default_
            builder.powerMode = PowerMode.Automatic.shared
        }
        api = MeshLink.shared.createIos(config: config)

        bindFlows()
        appendLog("MeshLink proof app ready on iPhone")
        start()
    }

    func start() {
        api.start { [weak self] result, error in
            Task { @MainActor in
                if let result {
                    self?.appendLog("mesh.start() -> \(result)")
                }
                if let error {
                    self?.appendLog("mesh.start() failed: \(error.localizedDescription)")
                }
            }
        }
    }

    func stop() {
        api.stop { [weak self] result, error in
            Task { @MainActor in
                if let result {
                    self?.appendLog("mesh.stop() -> \(result)")
                }
                if let error {
                    self?.appendLog("mesh.stop() failed: \(error.localizedDescription)")
                }
            }
        }
    }

    func sendHello() {
        guard let peer = peers.first else {
            appendLog("No discovered peer is available yet")
            return
        }
        sendHello(to: peer)
    }

    private func sendHello(to peer: PeerId) {
        let payload = "hello mesh from iPhone".toKotlinByteArray()
        api.send(peerId: peer, payload: payload, priority: DeliveryPriority.normal) { [weak self] result, error in
            Task { @MainActor in
                if let result {
                    self?.appendLog("mesh.send(\(peer.value.suffix(6))) -> \(result)")
                }
                if let error {
                    self?.appendLog("mesh.send() failed: \(error.localizedDescription)")
                }
            }
        }
    }

    private func bindFlows() {
        stateText = String(describing: api.state.value ?? "Unknown")
        api.state.collect(collector: stateCollector) { [weak self] error in
            Task { @MainActor in
                if let error {
                    self?.appendLog("state flow ended: \(error.localizedDescription)")
                }
            }
        }
        api.peerEvents.collect(collector: peerCollector) { [weak self] error in
            Task { @MainActor in
                if let error {
                    self?.appendLog("peer flow ended: \(error.localizedDescription)")
                }
            }
        }
        api.diagnosticEvents.collect(collector: diagnosticCollector) { [weak self] error in
            Task { @MainActor in
                if let error {
                    self?.appendLog("diagnostic flow ended: \(error.localizedDescription)")
                }
            }
        }
        api.messages.collect(collector: messageCollector) { [weak self] error in
            Task { @MainActor in
                if let error {
                    self?.appendLog("message flow ended: \(error.localizedDescription)")
                }
            }
        }
    }

    private func handlePeerEvent(_ value: Any?) {
        if let found = value as? PeerEvent.Found {
            peers = insertOrReplace(found.peerId, into: peers)
            appendLog("Peer found: \(found.peerId.value)")
            scheduleAutoHello(for: found.peerId)
        } else if let lost = value as? PeerEvent.Lost {
            peers.removeAll { peer in peer.value == lost.peerId.value }
            autoSendPeers.remove(lost.peerId.value)
            appendLog("Peer lost: \(lost.peerId.value)")
        } else if let changed = value as? PeerEvent.StateChanged {
            appendLog("Peer state changed: \(changed.peerId.value) -> \(changed.state)")
        }
    }

    private func handleDiagnosticEvent(_ value: Any?) {
        guard let diagnostic = value as? DiagnosticEvent else {
            return
        }
        appendLog("DIAG \(diagnostic.code) stage=\(diagnostic.stage) reason=\(String(describing: diagnostic.reason))")
    }

    private func handleInboundMessage(_ value: Any?) {
        guard let message = value as? InboundMessage else {
            return
        }
        appendLog("MSG from \(message.originPeerId.value) text=\(message.payload.toDataString())")
    }

    private func scheduleAutoHello(for peerId: PeerId) {
        guard autoSendPeers.insert(peerId.value).inserted else {
            return
        }
        Task {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            await MainActor.run {
                sendHello(to: peerId)
            }
        }
    }

    private func appendLog(_ message: String) {
        logs.append(message)
        if logs.count > 128 {
            logs.removeFirst(logs.count - 128)
        }
        let persisted = logs.joined(separator: "\n") + "\n"
        try? persisted.write(to: logFileUrl, atomically: true, encoding: .utf8)
    }

    private func insertOrReplace(_ peerId: PeerId, into existing: [PeerId]) -> [PeerId] {
        var copy = existing.filter { current in current.value != peerId.value }
        copy.append(peerId)
        return copy
    }
}

private final class FlowCollector: NSObject, Kotlinx_coroutines_coreFlowCollector {
    private let onValue: @MainActor (Any?) -> Void

    init(onValue: @escaping @MainActor (Any?) -> Void) {
        self.onValue = onValue
    }

    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        Task { @MainActor in
            onValue(value)
            completionHandler(nil)
        }
    }
}

private extension String {
    func toKotlinByteArray() -> KotlinByteArray {
        Data(utf8).toKotlinByteArray()
    }
}

private extension Data {
    func toKotlinByteArray() -> KotlinByteArray {
        let kotlinBytes = KotlinByteArray(size: Int32(count))
        for (index, byte) in enumerated() {
            kotlinBytes.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return kotlinBytes
    }
}

private extension KotlinByteArray {
    func toDataString() -> String {
        var bytes = [UInt8](repeating: 0, count: Int(size))
        for index in 0..<Int(size) {
            bytes[index] = UInt8(bitPattern: get(index: Int32(index)))
        }
        return String(decoding: bytes, as: UTF8.self)
    }
}
