import XCTest

final class ReferenceAppPhysicalLiveProofUITests: XCTestCase {
    private static let liveProofConfigURL = URL(fileURLWithPath: "/tmp/meshlink_reference_live_proof_xcuitest.json")
    private static let maximumConfigAgeSeconds: TimeInterval = 15 * 60

    private let liveProofFlag = "MESHLINK_REFERENCE_LIVE_PROOF"
    private var liveProofConfiguration: LiveProofConfiguration?

    override func setUpWithError() throws {
        continueAfterFailure = false
        guard let configuration = try loadLiveProofConfiguration() else {
            throw XCTSkip("Physical live-proof UI test only runs when explicitly opted in")
        }
        liveProofConfiguration = configuration
    }

    func testLiveProofSender() {
        // Arrange
        guard let liveProofConfiguration else {
            XCTFail("Missing live-proof configuration for physical sender test")
            return
        }
        let app = XCUIApplication()
        app.launchEnvironment = [
            "MESHLINK_REFERENCE_AUTOMATION_MODE": "live-proof",
            "MESHLINK_REFERENCE_AUTOMATION_STORAGE_SUBDIRECTORY": liveProofConfiguration.storageSubdirectory,
            "MESHLINK_REFERENCE_APP_ID": liveProofConfiguration.appId,
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

    private func loadLiveProofConfiguration() throws -> LiveProofConfiguration? {
        let environment = ProcessInfo.processInfo.environment
        if environment[liveProofFlag] == "true" {
            return LiveProofConfiguration(
                appId: environment["MESHLINK_REFERENCE_APP_ID"] ?? "demo.meshlink.reference.live",
                storageSubdirectory: environment["MESHLINK_REFERENCE_AUTOMATION_STORAGE_SUBDIRECTORY"] ?? "live-proof"
            )
        }
        guard FileManager.default.fileExists(atPath: Self.liveProofConfigURL.path) else {
            return nil
        }
        let data = try Data(contentsOf: Self.liveProofConfigURL)
        let configuration = try JSONDecoder().decode(HostLiveProofConfiguration.self, from: data)
        let ageSeconds = Date().timeIntervalSince1970 - (configuration.createdAtEpochMillis / 1000)
        guard ageSeconds <= Self.maximumConfigAgeSeconds else {
            return nil
        }
        return LiveProofConfiguration(
            appId: configuration.appId,
            storageSubdirectory: configuration.storageSubdirectory
        )
    }
}

private struct LiveProofConfiguration {
    let appId: String
    let storageSubdirectory: String
}

private struct HostLiveProofConfiguration: Decodable {
    let appId: String
    let storageSubdirectory: String
    let createdAtEpochMillis: Double
}
