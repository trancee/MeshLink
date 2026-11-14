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
}
