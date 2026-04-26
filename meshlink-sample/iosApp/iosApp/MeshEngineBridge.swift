import Foundation
import MeshLink          // Generated from :meshlink XCFramework
import Combine

/// Observable bridge between the Kotlin `MeshNode` facade and SwiftUI views.
///
/// **Lifecycle:** `start()` is called when the bridge is created; `stop()` is called when the
/// application moves to the background or when the user explicitly presses Stop.  The engine
/// is restarted when the user presses Start again.
///
/// **Threading:** All `@Published` mutations happen on the MainActor.  Kotlin flow collection
/// is performed on a background thread via `Task { }` and dispatched back with
/// `DispatchQueue.main.async`.
@MainActor
final class MeshEngineBridge: ObservableObject {

    // ── State ──────────────────────────────────────────────────────────────────

    @Published var isRunning: Bool = false
    @Published var logLines: [String] = []

    // ── Engine ─────────────────────────────────────────────────────────────────

    /// Kotlin-native facade created once per bridge instance.
    ///
    /// `appId` must match the Android `BleTransportConfig.appId` in `SampleMeshService.kt` so
    /// both sides recognise each other's BLE advertisements.
    private let node = MeshNode(
        appId: "ch.trancee.meshlink.sample",
        restorationIdentifier: "ch.trancee.meshlink.sample.ble",
        config: MeshEngineConfig(),
        scope: MainScope()          // MainScope() is provided by the KMP coroutines helper
    )

    private var flowTasks: [Task<Void, Never>] = []

    // ── Public API ─────────────────────────────────────────────────────────────

    func startEngine() {
        guard !isRunning else { return }
        log("▶ Starting MeshNode…")

        // Subscribe to all three flows before starting the engine.
        subscribeFlows()

        Task {
            do {
                try await node.start()
                await MainActor.run { self.isRunning = true }
                self.log("✅ MeshNode started")
            } catch {
                self.log("❌ start() failed: \(error)")
            }
        }
    }

    func stopEngine() {
        guard isRunning else { return }
        log("■ Stopping MeshNode…")

        Task {
            do {
                try await node.stop()
            } catch {
                self.log("⚠️ stop() threw: \(error)")
            }
            await MainActor.run { self.isRunning = false }
            self.log("✅ MeshNode stopped")
            self.cancelFlowTasks()
        }
    }

    /// Sends a 512-byte test payload to `recipient` (32-byte key-hash, hex-encoded for display).
    func sendTestPayload(to recipient: KotlinByteArray) {
        let payload = KotlinByteArray(size: 512)
        for i in 0..<512 { payload.set(index: i, value: Int8(bitPattern: UInt8(i & 0xFF))) }

        Task {
            do {
                let result = try await node.send(
                    recipient: recipient,
                    payload: payload,
                    priority: Priority.normal
                )
                self.log("📤 send → \(result)")
            } catch {
                self.log("❌ send failed: \(error)")
            }
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private func subscribeFlows() {
        // Peer events
        let peerTask = Task {
            for await event in node.peerEvents {
                let msg: String
                switch event {
                case let connected as PeerEventConnected:
                    let hex = connected.peerKeyHash.hexString
                    self.log("🔵 Peer connected: \(hex.prefix(8))…")
                    // Auto-send 512-byte test payload on first peer connection.
                    self.sendTestPayload(to: connected.peerKeyHash)
                    msg = ""
                case let disconnected as PeerEventDisconnected:
                    let hex = disconnected.peerKeyHash.hexString
                    self.log("🔴 Peer disconnected: \(hex.prefix(8))…")
                    msg = ""
                default:
                    msg = "📡 PeerEvent: \(event)"
                }
                if !msg.isEmpty { self.log(msg) }
            }
        }

        // Inbound messages
        let msgTask = Task {
            for await msg in node.messages {
                let fromHex = msg.sender.hexString
                self.log("📨 Message from \(fromHex.prefix(8))… — \(msg.payload.size) bytes")
            }
        }

        // Delivery confirmations
        let ackTask = Task {
            for await ack in node.deliveryConfirmations {
                let idHex = ack.messageId.hexString
                self.log("✅ Delivery ACK: msgId=\(idHex.prefix(8))…")
            }
        }

        flowTasks = [peerTask, msgTask, ackTask]
    }

    private func cancelFlowTasks() {
        flowTasks.forEach { $0.cancel() }
        flowTasks = []
    }

    private func log(_ line: String) {
        let ts = DateFormatter.localizedString(
            from: Date(), dateStyle: .none, timeStyle: .medium)
        let entry = "[\(ts)] \(line)"
        print("[MeshLink] \(entry)")        // visible in Xcode console + Console.app
        DispatchQueue.main.async {
            self.logLines.append(entry)
            // Keep last 200 lines to avoid unbounded memory growth.
            if self.logLines.count > 200 {
                self.logLines.removeFirst(self.logLines.count - 200)
            }
        }
    }
}

// ── ByteArray → hex helper ─────────────────────────────────────────────────────

private extension KotlinByteArray {
    /// Returns a lower-case hex string representation.
    var hexString: String {
        (0..<Int(size))
            .map { String(format: "%02x", UInt8(bitPattern: get(index: Int32($0)))) }
            .joined()
    }
}
