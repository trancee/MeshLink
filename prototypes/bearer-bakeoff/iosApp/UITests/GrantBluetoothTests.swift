import XCTest

/// Launches the lab app in `grant=1` mode (it creates CBCentralManager + CBPeripheralManager, which raises the
/// Bluetooth permission alert once) and accepts the alert. Buttons are matched by EXACT label only, so
/// "Don't Allow" / "Nicht erlauben" can never be tapped. Every alert title and button label seen is logged.
final class GrantBluetoothTests: XCTestCase {
    private let acceptLabels: Set<String> = ["Allow", "OK", "Erlauben"]
    private let rejectLabels: Set<String> = ["Don't Allow", "Don\u{2019}t Allow", "Nicht erlauben"]

    func testGrantBluetooth() throws {
        log("device=\(UIDevice.current.model) ios=\(UIDevice.current.systemVersion) languages=\(Locale.preferredLanguages) locale=\(Locale.current.identifier)")
        let app: XCUIApplication
        if ProcessInfo.processInfo.environment["GRANT_ATTACH"] == "1" {
            // Xcode 16.4's XCUIApplication.launch() fails on iOS 27 ("Failed to get launch progress"), so the host
            // launches the app with grant=1 itself and the runner only attaches to it.
            let runner = Bundle.main.bundleIdentifier ?? ""
            let target = runner.replacingOccurrences(of: ".grant.xctrunner", with: "")
            log("attach mode target=\(target)")
            app = XCUIApplication(bundleIdentifier: target)
            XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20), "app \(target) not in foreground")
        } else {
            app = XCUIApplication()
            app.launchArguments = ["grant=1"]
            app.launch()
        }
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let status = app.staticTexts["status"]
        var tapped = 0
        let deadline = Date().addingTimeInterval(45)
        while Date() < deadline {
            let text = status.exists ? status.label : ""
            if text.contains("bt_auth=allowed_always") {
                log("result=allowed_always alerts_tapped=\(tapped)\(tapped == 0 ? " (no alert shown)" : "")")
                return
            }
            if text.contains("bt_auth=denied") || text.contains("bt_auth=restricted") {
                log("result=\(text)")
                XCTFail("Bluetooth not authorized: \(text) (re-enable in Settings > Privacy > Bluetooth; not automated)")
                return
            }
            for (source, alert) in [("springboard", springboard.alerts.firstMatch), ("app", app.alerts.firstMatch)] where alert.exists {
                let buttons = alert.buttons.allElementsBoundByIndex
                let labels = buttons.map(\.label)
                log("alert source=\(source) title=\"\(alert.label)\" buttons=\(labels)")
                guard let index = labels.firstIndex(where: { acceptLabels.contains($0) && !rejectLabels.contains($0) }) else {
                    XCTFail("No accept label among \(labels) (title \"\(alert.label)\"); nothing tapped")
                    return
                }
                log("tapping \"\(labels[index])\"")
                buttons[index].tap()
                tapped += 1
            }
            Thread.sleep(forTimeInterval: 0.5)
        }
        XCTFail("Timed out; last status \"\(status.exists ? status.label : "<none>")\", alerts tapped \(tapped)")
    }

    private func log(_ message: String) {
        NSLog("BAKEOFF-GRANT %@", message)
        print("BAKEOFF-GRANT \(message)")
    }
}
