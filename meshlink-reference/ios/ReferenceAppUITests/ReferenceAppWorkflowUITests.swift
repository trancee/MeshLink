import XCTest

final class ReferenceAppWorkflowUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testGuidedWorkflowShowsLiveProofAndSoloFallback() {
        // Arrange
        let launched = ReferenceAppUITestSupport.launchReferenceApp()
        let app = launched.application
        ReferenceAppUITestSupport.waitForStaticText(in: app, labeled: "Guided first exchange")
        let sendHelloButton = app.buttons["Send Hello"]

        // Act
        ReferenceAppUITestSupport.tapButton(in: app, labeled: "Start MeshLink")
        ReferenceAppUITestSupport.waitForEnabled(
            sendHelloButton,
            timeout: 10,
            message: "Expected Send Hello to become enabled after the scripted peer appears"
        )
        ReferenceAppUITestSupport.waitForStaticTextContaining(in: app, text: "Peer: 654321")
        ReferenceAppUITestSupport.tapButton(in: app, labeled: "Send Hello")
        ReferenceAppUITestSupport.tapButton(in: app, identifier: "guided-open-solo")
        ReferenceAppUITestSupport.tapButton(in: app, labeled: "Continue without export")

        // Assert
        ReferenceAppUITestSupport.waitForStaticText(in: app, labeled: "Solo exploration")
        ReferenceAppUITestSupport.waitForStaticText(in: app, labeled: "Non-authoritative")
    }

    func testAdvancedLifecycleTrustResetAndLabFlows() {
        // Arrange
        let launched = ReferenceAppUITestSupport.launchReferenceApp()
        let app = launched.application
        ReferenceAppUITestSupport.tapButton(in: app, labeled: "Start MeshLink")
        ReferenceAppUITestSupport.tapButton(in: app, labeled: "Controls")
        ReferenceAppUITestSupport.waitForStaticText(in: app, labeled: "Advanced controls")
        ReferenceAppUITestSupport.waitForStaticTextContaining(in: app, text: "Power mode: Automatic")
        ReferenceAppUITestSupport.waitForStaticTextContaining(in: app, text: "Mesh state: Running")

        // Act
        ReferenceAppUITestSupport.tapButton(in: app, identifier: "advanced-pause")
        ReferenceAppUITestSupport.tapButton(in: app, identifier: "advanced-resume")
        ReferenceAppUITestSupport.tapButton(in: app, identifier: "advanced-pause")
        ReferenceAppUITestSupport.tapButton(in: app, identifier: "advanced-resume")
        ReferenceAppUITestSupport.tapButton(in: app, identifier: "advanced-send-large-transfer")
        ReferenceAppUITestSupport.tapButton(in: app, identifier: "advanced-forget-peer")
        ReferenceAppUITestSupport.waitForStaticTextContaining(in: app, text: "Trust: Forgotten")
        app.swipeDown()
        app.swipeDown()
        ReferenceAppUITestSupport.tapButton(in: app, labeled: "Lab")
        ReferenceAppUITestSupport.tapButton(in: app, labeled: "Continue without export")

        // Assert
        ReferenceAppUITestSupport.waitForStaticTextContaining(
            in: app,
            text: "Everything here is explicitly separated"
        )
    }

    func testTimelineHistoryAndRedactedExport() {
        // Arrange
        let launched = ReferenceAppUITestSupport.launchReferenceApp()
        let app = launched.application
        ReferenceAppUITestSupport.tapButton(in: app, labeled: "Start MeshLink")
        let sendHelloButton = app.buttons["Send Hello"]
        ReferenceAppUITestSupport.waitForEnabled(
            sendHelloButton,
            timeout: 10,
            message: "Expected Send Hello to become enabled before export validation"
        )
        ReferenceAppUITestSupport.tapButton(in: app, labeled: "Send Hello")
        ReferenceAppUITestSupport.tapButton(in: app, labeled: "Evidence")
        ReferenceAppUITestSupport.waitForStaticText(in: app, labeled: "Technical timeline")

        // Act
        ReferenceAppUITestSupport.tapButton(in: app, labeled: "End session")
        ReferenceAppUITestSupport.tapButton(in: app, labeled: "End without full export")
        ReferenceAppUITestSupport.tapButton(in: app, labeled: "Export session")
        ReferenceAppUITestSupport.tapButton(in: app, labeled: "Redacted export")
        let relativeExportPath = ReferenceAppUITestSupport.lastExportRelativePath(in: app)
        ReferenceAppUITestSupport.tapButton(in: app, labeled: "Recent history")
        ReferenceAppUITestSupport.waitForStaticText(in: app, labeled: "Recent history")

        // Assert
        XCTAssertTrue(relativeExportPath.hasPrefix("reference/exports/"))
        XCTAssertTrue(relativeExportPath.hasSuffix(".json"))
        XCTAssertTrue(app.buttons["Clear all"].waitForExistence(timeout: 10))
    }

    func testBlockedStartupShowsRecoveryGuidance() {
        // Arrange
        let launched = ReferenceAppUITestSupport.launchReferenceApp(blocked: true)
        let app = launched.application
        ReferenceAppUITestSupport.waitForStaticText(in: app, labeled: "Guided first exchange")
        let startButton = app.buttons["Start MeshLink"]

        // Act
        XCTAssertTrue(startButton.waitForExistence(timeout: 10), "Expected Start MeshLink to exist")

        // Assert
        XCTAssertFalse(startButton.isEnabled)
        ReferenceAppUITestSupport.waitForStaticText(in: app, labeled: "Startup blocked")
        ReferenceAppUITestSupport.waitForStaticTextContaining(in: app, text: "Resolve startup blockers")
        ReferenceAppUITestSupport.waitForStaticTextContaining(in: app, text: "Enable Bluetooth")
        ReferenceAppUITestSupport.waitForStaticTextContaining(in: app, text: "Grant the required local Bluetooth permissions")
    }
}
