import Foundation
import MeshLink          // Generated from :meshlink XCFramework
import Combine

/// Observable bridge between the Kotlin `MeshLink` public API and SwiftUI views.
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

    /// Public MeshLink API created via the iOS factory extension.
    ///
    /// Uses the `meshLinkConfig` DSL builder which avoids the module/class name
    /// collision (`MeshLink` module vs `MeshLink` class) and provides defaults
    /// for all config sub-objects.
    ///
    /// `appId` must match the Android `BleTransportConfig.appId` in
    /// `SampleMeshService.kt` so both sides recognise each other's BLE
    /// advertisements.  The `restorationIdentifier` must match the app's bundle
    /// ID for proper CoreBluetooth state restoration.
    private let mesh: MeshLinkApi = {
        let config = meshLinkConfig(appId: "ch.trancee.meshlink.sample") { _ in }
        return MeshLink_.companion.createIos(
            config: config,
            restorationIdentifier: "ch.trancee.meshlink.sample"
        )
    }()

    private var flowTasks: [Task<Void, Never>] = []

    // ── Public API ─────────────────────────────────────────────────────────────

    func startEngine() {
        guard !isRunning else { return }
        log("▶ Starting MeshLink…")

        // Subscribe to all three flows before starting the engine.
        subscribeFlows()

        Task {
            do {
                try await mesh.start()
                await MainActor.run { self.isRunning = true }
                self.log("✅ MeshLink started")
            } catch {
                self.log("❌ start() failed: \(error)")
            }
        }
    }

    func stopEngine() {
        guard isRunning else { return }
        log("■ Stopping MeshLink…")

        Task {
            do {
                try await mesh.stop(timeout: 0)
            } catch {
                self.log("⚠️ stop() threw: \(error)")
            }
            await MainActor.run { self.isRunning = false }
            self.log("✅ MeshLink stopped")
            self.cancelFlowTasks()
        }
    }

    /// Sends a 512-byte test payload to `recipient` (12-byte key hash).
    func sendTestPayload(to recipient: KotlinByteArray) {
        let payload = KotlinByteArray(size: 512)
        for i in 0..<512 { payload.set(index: Int32(i), value: Int8(bitPattern: UInt8(i & 0xFF))) }

        Task {
            do {
                try await mesh.send(
                    recipient: recipient,
                    payload: payload,
                    priority: MessagePriority.normal
                )
                self.log("📤 send dispatched")
            } catch {
                self.log("❌ send failed: \(error)")
            }
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private func subscribeFlows() {
        // Peer events
        let peerTask = Task {
            for await event in mesh.peers {
                switch onEnum(of: event) {
                case .found(let found):
                    let hex = found.id.hexString
                    self.log("🔵 Peer found: \(hex.prefix(8))…")
                    // Auto-send 512-byte test payload on first peer connection.
                    self.sendTestPayload(to: found.id)
                case .lost(let lost):
                    let hex = lost.id.hexString
                    self.log("🔴 Peer lost: \(hex.prefix(8))…")
                }
            }
        }

        // Inbound messages
        let msgTask = Task {
            for await msg in mesh.messages {
                let fromHex = msg.senderId.hexString
                self.log("📨 Message from \(fromHex.prefix(8))… — \(msg.payload.size) bytes")
            }
        }

        // Delivery confirmations
        let ackTask = Task {
            for await ack in mesh.deliveryConfirmations {
                let idHex = ack.bytes.hexString
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
