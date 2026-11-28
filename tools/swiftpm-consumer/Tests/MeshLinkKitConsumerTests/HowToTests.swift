import MeshLink
import MeshLinkTesting
import XCTest

// The Swift code of the docs/how-to pages, run on the FakeMesh fake and, where a page needs the real
// node, on the real framework. Each `// region` is copied into a page, and ./gradlew checkDocRegions
// fails when the two differ (ADR 0006 decision 5).
@MainActor
final class HowToTests: XCTestCase {
    private let mesh = FakeMesh()

    func testChoosePresetTakesTheBuiltInPresetsValues() throws {
        // Act
        let config = try choosePreset()

        // Assert
        XCTAssertEqual(config.preset, Preset.FieldTeams.shared)
        XCTAssertEqual(config.meshId, "Rescue team")
    }

    func testCustomPresetOverridesItsBaseAndTheBlockOverridesThePreset() throws {
        // Act
        let config = try customizePreset()

        // Assert
        XCTAssertEqual(config.describe(), Self.customDescription)
    }

    func testOnTheSimulatorANodeIsBlockedAndTheAppSaysWhy() async throws {
        // Arrange
        let node: any MeshNode = MeshLink.create(config: try chat())
        try await node.start()

        // Act
        let asks = prompts(node)
        node.close()

        // Assert
        XCTAssertEqual(asks, ["This device can't join the mesh"])
    }

    func testWaitingForRunningSkipsTheStatesBefore() async throws {
        // Arrange
        let node = mesh.node("Alice", config: try chat())
        var returnedWhileStopped = false
        let running = Task {
            await awaitRunning(node)
            if node.currentState == .stopped { returnedWhileStopped = true }
        }
        for _ in 0..<10 { await Task.yield() }

        // Act
        try await node.start()
        await running.value

        // Assert
        XCTAssertFalse(returnedWhileStopped)
    }

    func testMessageThatAsksForAReadReceiptIsReadOnceBobMarksIt() async throws {
        // Arrange
        let (alice, bob) = try await linkedPair()
        let reader = Task { await readNextMessage(bob) }

        // Act
        let status = try await sendWithReadReceipt(alice, to: bob.id)

        // Assert
        XCTAssertEqual(status, .read)
        let text = await reader.value
        XCTAssertEqual(text, "Are you safe?")
    }

    func testFullQueueWaitsForRoomOnceAndThenAcceptsTheMessage() async throws {
        // Arrange
        let alice = mesh.node("Alice", config: try chat())
        let away = mesh.node("Bob", config: try chat())
        try await alice.start()
        let payload = Data(count: 131_072)
        for _ in 0..<8 { _ = try alice.send(payload, to: away.id) }
        guard case .rejected(.queueFull) = try alice.send(payload, to: away.id) else { return XCTFail("Queue not full") }
        let sending = Task { try await sendWhenThereIsRoom(alice, to: away.id, payload: payload) }
        await Task.yield()

        // Act
        mesh.advance(by: 1_831)
        let result = try await sending.value

        // Assert
        guard case .accepted = result else { return XCTFail("\(result)") }
    }

    func testBroadcastIsSentAndBobReceivesIt() async throws {
        // Arrange
        let (alice, bob) = try await linkedPair()
        let listener = Task { await nextBroadcast(bob) }

        // Act
        let status = try await broadcastAlert(alice)

        // Assert
        XCTAssertEqual(status, .sent)
        let text = await listener.value
        XCTAssertEqual(text, "Water at the school")
    }

    func testExportedReportHoldsTheFormatButNotTheMeshId() async throws {
        // Arrange
        let node = MeshLink.create(config: try chat())
        try await (node as any MeshNode).start()

        // Act
        let report = try await exportDiagnostics(node)
        node.close()

        // Assert
        XCTAssertTrue(report.contains("\"format\": 1"), report)
        XCTAssertFalse(report.contains("How-to mesh"), report)
    }

    private func chat() throws -> MeshLinkConfig {
        try meshLinkConfig(preset: Preset.OfflineChat.shared, securityProfile: SecurityProfileClassical.shared) {
            $0.meshId = "How-to mesh"
        }.build()
    }

    private func linkedPair() async throws -> (FakeMeshNode, FakeMeshNode) {
        let alice = mesh.node("Alice", config: try chat())
        let bob = mesh.node("Bob", config: try chat())
        mesh.connect(alice, bob)
        try await alice.start()
        try await bob.start()
        return (alice, bob)
    }

    private func choosePreset() throws -> MeshLinkConfig {
        // region choose-preset
        let config = try meshLinkConfig(preset: Preset.FieldTeams.shared, securityProfile: SecurityProfilePostQuantum.shared) {
            $0.meshId = "Rescue team"
        }.build()
        // endregion
        return config
    }

    private func customizePreset() throws -> MeshLinkConfig {
        // region custom-preset
        let quietChat = Preset.Custom(name: "Quiet chat", base: Preset.OfflineChat.shared) {
            $0.readReceipts.send = false
            $0.messageLifetimeSeconds = 3_600
        }
        let config = try meshLinkConfig(preset: quietChat, securityProfile: SecurityProfileClassical.shared) {
            $0.meshId = "Quiet chat"
            $0.power = .responsive
        }.build()
        // endregion
        return config
    }

    private func prompts(_ node: any MeshNode) -> [String] {
        // region blocked
        var prompts: [String] = []
        if case .blocked(let requirements) = node.currentState {
            prompts = requirements.map { requirement in
                switch requirement {
                case .permissionDenied(let kind): "Allow \(kind) for this app in Settings"
                case .bluetoothOff: "Turn on Bluetooth"
                case .unsupported: "This device can't join the mesh"
                case .radioInUse: "Another part of this app uses the mesh"
                case .identityUnavailable: "Unlock the device and try again"
                }
            }
        }
        // endregion
        return prompts
    }

    private func awaitRunning(_ node: any MeshNode) async {
        // region leave-blocked
        for await state in node.stateUpdates {
            if case .running = state { break }
        }
        // endregion
    }

    private func sendWithReadReceipt(_ node: any MeshNode, to peer: MeshNodeID) async throws -> MeshDeliveryStatus {
        // region send-receipt
        let options = MeshSendOptions(readReceipt: .request)
        guard case .accepted(let message) = try node.send(Data("Are you safe?".utf8), to: peer, options: options) else {
            throw CancellationError()
        }
        var status = message.status
        for await update in message.statusUpdates {
            status = update
            if case .failed = update { break }
            if update == .read || update == .expired { break }
        }
        // endregion
        return status
    }

    private func readNextMessage(_ node: any MeshNode) async -> String? {
        // region mark-read
        var text: String?
        for await message in node.receivedMessages {
            text = String(decoding: message.payload, as: UTF8.self)
            message.markRead()
            break
        }
        // endregion
        return text
    }

    private func sendWhenThereIsRoom(_ node: any MeshNode, to peer: MeshNodeID, payload: Data) async throws -> MeshSendResult {
        // region queue-full
        var result = try node.send(payload, to: peer)
        if case .rejected(.queueFull) = result {
            try await node.awaitSendCapacity()
            result = try node.send(payload, to: peer)
        }
        // endregion
        return result
    }

    private func broadcastAlert(_ node: any MeshNode) async throws -> MeshDeliveryStatus {
        // region broadcast
        guard case .accepted(let message) = try node.broadcast(Data("Water at the school".utf8), hopLimit: 4) else {
            throw CancellationError()
        }
        var status = message.status
        for await update in message.statusUpdates where update != .queued {
            status = update
            break
        }
        // endregion
        return status
    }

    private func nextBroadcast(_ node: any MeshNode) async -> String? {
        // region receive-broadcast
        var text: String?
        for await message in node.receivedMessages where message.isBroadcast {
            text = String(decoding: message.payload, as: UTF8.self)
            break
        }
        // endregion
        return text
    }

    private func exportDiagnostics(_ node: MeshLink) async throws -> String {
        // region export
        let report = try await node.exportDiagnostics()
        // endregion
        return report
    }

    // What docs/how-to/choose-a-preset.md shows `describe()` printing; HowToTest checks the same text in Kotlin.
    private static let customDescription = """
        preset = Custom("Quiet chat", base = OfflineChat) (override)
        securityProfile = Classical (override)
        meshId = "Quiet chat" (override)
        meshKey = none (default)
        l2capUpgrade = true (default)
        diagnostics.debug = false (default)
        diagnostics.platformLog = false (default)
        density = Automatic (preset)
        power = Responsive (override)
        routedUnicast = true (preset)
        broadcastOrigination = true (preset)
        broadcastPriority = false (preset)
        holding = true (preset)
        messageLifetimeSeconds = 3600 (preset)
        readReceipts.requestByDefault = true (preset)
        readReceipts.send = false (preset)
        limits.maxUnicastPayload = 131072 (preset)
        limits.maxBroadcastPayload = 1024 (preset)
        """
}
