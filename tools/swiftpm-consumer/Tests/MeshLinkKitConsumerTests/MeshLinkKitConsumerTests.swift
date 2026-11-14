import MeshLinkKit
import XCTest

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
        let node = MeshLink.companion.create(config: config, platform: MeshPlatform.companion.ios())

        // Act
        try await node.start()
        let state = node.state.value
        node.close()

        // Assert
        let blocked = try XCTUnwrap(state as? MeshStateBlocked, "expected Blocked, got \(String(describing: state))")
        XCTAssertEqual(blocked.requirements, [RequirementUnsupported.shared] as Set<AnyHashable>)
    }
}
