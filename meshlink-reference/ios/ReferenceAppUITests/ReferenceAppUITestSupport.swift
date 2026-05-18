import XCTest

struct LaunchedReferenceApp {
    let application: XCUIApplication
    let storageSubdirectory: String
}

enum ReferenceAppUITestSupport {
    static let appBundleIdentifier = "ch.trancee.meshlink.reference.ios"

    static func launchReferenceApp(blocked: Bool = false) -> LaunchedReferenceApp {
        let storageSubdirectory = UUID().uuidString
        let application = XCUIApplication()
        application.launchEnvironment = [
            "MESHLINK_REFERENCE_UI_AUTOMATION": "true",
            "MESHLINK_REFERENCE_AUTOMATION_STORAGE_SUBDIRECTORY": storageSubdirectory,
            "MESHLINK_REFERENCE_AUTOMATION_BLOCKED": blocked ? "true" : "false",
        ]
        application.launch()
        return LaunchedReferenceApp(
            application: application,
            storageSubdirectory: storageSubdirectory
        )
    }

    static func waitForEnabled(
        _ element: XCUIElement,
        timeout: TimeInterval,
        message: String
    ) {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if element.exists && element.isEnabled {
                return
            }
            RunLoop.current.run(until: Date(timeIntervalSinceNow: 0.2))
        }
        XCTFail(message)
    }

    static func waitForStaticText(
        in application: XCUIApplication,
        labeled label: String,
        timeout: TimeInterval = 10
    ) {
        let element = application.staticTexts[label]
        XCTAssertTrue(
            element.waitForExistence(timeout: timeout),
            "Expected static text '\(label)' to appear"
        )
    }

    static func waitForStaticTextContaining(
        in application: XCUIApplication,
        text: String,
        timeout: TimeInterval = 10
    ) {
        let predicate = NSPredicate(format: "label CONTAINS %@", text)
        let element = application.staticTexts.containing(predicate).firstMatch
        XCTAssertTrue(
            element.waitForExistence(timeout: timeout),
            "Expected static text containing '\(text)' to appear"
        )
    }

    static func tapButton(
        in application: XCUIApplication,
        labeled label: String,
        timeout: TimeInterval = 10
    ) {
        let button = application.buttons[label]
        if !button.waitForExistence(timeout: 1) {
            for _ in 0..<4 where !button.exists {
                application.swipeUp()
                _ = button.waitForExistence(timeout: 1)
            }
        }
        XCTAssertTrue(button.waitForExistence(timeout: timeout), "Expected button '\(label)' to appear")
        button.tap()
    }

    static func lastExportRelativePath(
        in application: XCUIApplication,
        timeout: TimeInterval = 10
    ) -> String {
        let predicate = NSPredicate(format: "label BEGINSWITH %@", "Last export: ")
        let element = application.staticTexts.containing(predicate).firstMatch
        XCTAssertTrue(element.waitForExistence(timeout: timeout), "Expected last export text to appear")
        return element.label.replacingOccurrences(of: "Last export: ", with: "")
    }
}
