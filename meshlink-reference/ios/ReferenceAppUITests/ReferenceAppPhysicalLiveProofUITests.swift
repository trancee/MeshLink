import XCTest

final class ReferenceAppPhysicalLiveProofUITests: XCTestCase {
    private let liveProofFlag = "MESHLINK_REFERENCE_LIVE_PROOF"

    override func setUpWithError() throws {
        continueAfterFailure = false
        try XCTSkipUnless(
            ProcessInfo.processInfo.environment[liveProofFlag] == "true",
            "Physical live-proof UI test only runs when explicitly opted in"
        )
    }

    func testLiveProofSender() {
        // Arrange
        let environment = ProcessInfo.processInfo.environment
        let app = XCUIApplication()
        app.launchEnvironment = [
            "MESHLINK_REFERENCE_AUTOMATION_MODE": "live-proof",
            "MESHLINK_REFERENCE_AUTOMATION_STORAGE_SUBDIRECTORY": environment["MESHLINK_REFERENCE_AUTOMATION_STORAGE_SUBDIRECTORY"] ?? "live-proof",
            "MESHLINK_REFERENCE_APP_ID": environment["MESHLINK_REFERENCE_APP_ID"] ?? "demo.meshlink.reference.live",
            "MESHLINK_REFERENCE_AUTOMATION_ROLE": "sender",
        ]
        addUIInterruptionMonitor(withDescription: "Bluetooth permission") { alert in
            let preferredButtons = ["Allow", "OK", "Continue"]
            for label in preferredButtons where alert.buttons[label].exists {
                alert.buttons[label].tap()
                return true
            }
            return false
        }

        // Act
        app.launch()
        app.activate()
        acceptSystemAlertIfPresent()

        // Assert
        XCTAssertTrue(
            app.staticTexts["Guided first exchange"].waitForExistence(timeout: 15),
            "Expected the guided first-exchange surface to appear on the physical iPhone"
        )
        let sentPredicate = NSPredicate(format: "label CONTAINS %@", "Guided message sent")
        let sentEntry = app.staticTexts.containing(sentPredicate).firstMatch
        XCTAssertTrue(
            sentEntry.waitForExistence(timeout: 120),
            "Expected the live-proof sender to reach the guided message sent state"
        )
    }

    private func acceptSystemAlertIfPresent() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let preferredButtons = ["Allow", "OK", "Continue"]
        for _ in 0..<5 {
            for label in preferredButtons {
                let button = springboard.buttons[label]
                if button.waitForExistence(timeout: 1) {
                    button.tap()
                    return
                }
            }
        }
    }
}
