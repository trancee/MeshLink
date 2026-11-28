import MeshLink
import XCTest

@MainActor
final class MeshLinkKitConsumerTests: XCTestCase {
    func testFrameworkBundleIsLoaded() throws {
        // Arrange
        let bundleID = "ch.trancee.meshlink.kit"

        // Act
        let bundle = Bundle(identifier: bundleID)

        // Assert
        let loaded = try XCTUnwrap(bundle, "no loaded bundle with ID \(bundleID)")
        XCTAssertTrue(loaded.isLoaded)
    }

    func testNodeOnTheSimulatorIsBlockedAsUnsupported() async throws {
        // Arrange: the simulator has no Bluetooth, so its radio reports Unsupported (ADR 0005).
        let config = try meshLinkConfig(preset: Preset.OfflineChat.shared, securityProfile: SecurityProfileUnsecured.shared) {
            $0.meshId = "Swift consumer"
        }.build()
        let node = MeshLink.create(config: config)

        // Act
        try await (node as any MeshNode).start()
        let state = node.currentState
        node.close()

        // Assert
        XCTAssertEqual(state, .blocked(requirements: [.unsupported]))
    }
}
