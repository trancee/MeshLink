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
        let button = application.buttons.matching(NSPredicate(format: "label == %@", label)).firstMatch
        revealButton(button, in: application, timeout: timeout)
        XCTAssertTrue(button.exists, "Expected button '\(label)' to appear")
        XCTAssertTrue(button.isHittable, "Expected button '\(label)' to become hittable")
        button.tap()
    }

    static func tapButton(
        in application: XCUIApplication,
        identifier: String,
        timeout: TimeInterval = 10
    ) {
        let button = application.buttons[identifier]
        revealButton(button, in: application, timeout: timeout)
        XCTAssertTrue(
            button.exists,
            "Expected button identifier '\(identifier)' to appear"
        )
        XCTAssertTrue(
            button.isHittable,
            "Expected button identifier '\(identifier)' to become hittable"
        )
        button.tap()
    }

    private static func revealButton(
        _ button: XCUIElement,
        in application: XCUIApplication,
        timeout: TimeInterval
    ) {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if button.exists && button.isHittable {
                return
            }
            if !button.exists {
                _ = button.waitForExistence(timeout: 0.5)
            }
            if button.exists && button.isHittable {
                return
            }
            application.swipeUp()
            RunLoop.current.run(until: Date(timeIntervalSinceNow: 0.2))
        }
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
