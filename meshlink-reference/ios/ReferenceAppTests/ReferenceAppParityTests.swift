import XCTest
@testable import ReferenceApp

final class ReferenceAppParityTests: XCTestCase {
    func testBundleIdentifierIsStable() {
        XCTAssertEqual(Bundle.main.bundleIdentifier, "ch.trancee.meshlink.reference.ios")
    }
}
