import MeshLink
import MeshLinkTesting
import XCTest

// The Swift code of docs/tutorials/get-started.md, run on the FakeMesh fake and on the real framework.
// Each `// region` is copied into the tutorial, and ./gradlew checkDocRegions fails when the two differ
// (ADR 0006 decision 5).
@MainActor
final class GetStartedTests: XCTestCase {
    func testAliceSendsBobAMessageOverASecuredLinkAndSeesItDelivered() async throws {
        // Arrange
        let mesh = FakeMesh()
        let config = try configure()
        let alice = mesh.node("Alice", config: config)
        let bob = mesh.node("Bob", config: config)
        mesh.connect(alice, bob)
        try await start(alice)
        try await start(bob)
        let inbox = Task { await receive(on: bob) }

        // Act
        let peer = await firstPeer(of: alice)
        let status = try await send(from: alice, to: try XCTUnwrap(peer))
        let text = await inbox.value
        close(alice)
        bob.close()

        // Assert
        XCTAssertEqual(peer?.secured, true)
        XCTAssertEqual(status, .delivered)
        XCTAssertEqual(text, "Hello from Alice")
        XCTAssertEqual(alice.currentState, .stopped)
    }

    func testAnAppCreatesANodeOnTheIOSRadio() throws {
        // Arrange
        let config = try configure()

        // Act
        let node = create(config)

        // Assert
        XCTAssertEqual(node.currentState, .stopped)
        node.close()
    }

    private func configure() throws -> MeshLinkConfig {
        // region configure
        let config = try meshLinkConfig(preset: Preset.OfflineChat.shared, securityProfile: SecurityProfileClassical.shared) {
            $0.meshId = "Get started"
        }.build()
        // endregion
        return config
    }

    private func create(_ config: MeshLinkConfig) -> any MeshNode {
        // region create-ios
        let node: any MeshNode = MeshLink.create(config: config)
        // endregion
        return node
    }

    private func start(_ node: any MeshNode) async throws {
        // region start
        try await node.start()
        // endregion
    }

    private func firstPeer(of node: any MeshNode) async -> MeshPeer? {
        // region peers
        var peer: MeshPeer?
        for await peers in node.peerUpdates where !peers.isEmpty {
            peer = peers.first
            break
        }
        // endregion
        return peer
    }

    private func send(from node: any MeshNode, to peer: MeshPeer) async throws -> MeshDeliveryStatus? {
        // region send
        guard case .accepted(let message) = try node.send(Data("Hello from Alice".utf8), to: peer.id) else {
            return nil
        }
        for await status in message.statusUpdates where status == .delivered {
            break
        }
        // endregion
        return message.status
    }

    private func receive(on node: any MeshNode) async -> String? {
        // region receive
        var text: String?
        for await message in node.receivedMessages {
            text = String(decoding: message.payload, as: UTF8.self)
            break
        }
        // endregion
        return text
    }

    private func close(_ node: any MeshNode) {
        // region close
        node.close()
        // endregion
    }
}
