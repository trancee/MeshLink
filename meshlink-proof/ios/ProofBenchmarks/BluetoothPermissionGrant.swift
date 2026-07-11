import XCTest

/// Runs the proof app once and automatically grants the system Bluetooth permission prompt via an
/// XCUITest interruption monitor, since a physical iOS app cannot programmatically accept its own
/// permission dialog (a deliberate Apple platform restriction -- see
/// docs/how-to/unblock-meshlink-permissions.md's "Unblock the iPhone Bluetooth prompt" section).
///
/// Permission grants persist across app reinstalls on a given device (they are keyed to the
/// bundle identifier and only reset by "Reset Location & Privacy" or a full device erase), so this
/// only needs to run once per physical device, not once per rebuild/reinstall. After it passes
/// once on a device, ordinary `xcrun devicectl device process launch`/`ios-deploy` runs on that
/// same device no longer show the prompt.
///
/// **Locale-independent by design.** `NSBluetoothAlwaysUsageDescription` prompts render with
/// exactly two buttons -- a dismiss/deny action first and the affirmative "OK" action last -- and
/// this button *order* is fixed by iOS itself for this system alert category regardless of the
/// device's language (only the button *text* is translated, e.g. German still shows "OK" as the
/// affirmative label for this particular alert, but even if a future iOS version translates it
/// differently, the position does not change). Tapping the last button therefore works on any
/// device locale without hardcoding translated strings for every language. A small set of common
/// English/German labels is still checked first purely as a documentation aid / early-exit
/// optimization; the position-based fallback is what makes this actually locale-independent.
///
/// Usage (once per fresh device/fresh permission state, in any device language):
///
/// ```bash
/// xcodebuild test \
///   -project meshlink-proof/ios/ProofApp.xcodeproj \
///   -scheme ProofBenchmarks \
///   -destination "id=<device-udid>" \
///   -only-testing:ProofBenchmarks/BluetoothPermissionGrant
/// ```
final class BluetoothPermissionGrant: XCTestCase {
    func testGrantsTheBluetoothPermissionPromptIfShown() {
        // Arrange: register the interruption monitor before launch so it is armed the moment the
        // system prompt appears.
        addUIInterruptionMonitor(withDescription: "Bluetooth permission prompt") { alert in
            Self.tapAffirmativeButton(in: alert)
        }

        // Act: launch the app, then perform a trivial interaction so XCTest actually evaluates the
        // interruption monitor registered above (monitors are only checked when a UI query/
        // interaction happens after the interruption -- launch() alone does not trigger this).
        let application = BenchmarkTestSupport.launchProofApp(
            appId: "demo.meshlink.bluetooth-permission-grant",
            disableAutoSend: true
        )
        application.tap()

        // Assert: the app is usable afterward regardless of whether a prompt actually appeared
        // this run (already-granted devices see no prompt at all, and this test stays a safe,
        // idempotent no-op on them). "proof.state" existing confirms ContentView rendered and the
        // permission flow (if any) did not leave the app stuck behind the system alert.
        let stateLabel = application.staticTexts["proof.state"]
        XCTAssertTrue(
            stateLabel.waitForExistence(timeout: 15),
            "Expected the proof app's state label to render, meaning the Bluetooth permission " +
                "prompt (if shown) was dismissed rather than left blocking the app"
        )
    }

    /// Common affirmative labels observed across English and German for this specific
    /// `NSBluetoothAlwaysUsageDescription` system alert, checked first as a cheap early exit.
    /// Never includes a negative/deny label ("Don't Allow" / "Nicht erlauben") -- if none of these
    /// match, [tapLastButton] below is what actually makes this locale-independent, not this list.
    private static let knownAffirmativeLabels = ["OK", "Allow", "Allow While Using App"]

    private static func tapAffirmativeButton(in alert: XCUIElement) -> Bool {
        for label in knownAffirmativeLabels {
            let button = alert.buttons[label]
            if button.exists {
                button.tap()
                return true
            }
        }
        return tapLastButton(in: alert)
    }

    /// iOS places the affirmative/default action last (right-most) for this system alert category
    /// regardless of device language -- see this file's doc comment. Falls back to this
    /// position-based tap when [knownAffirmativeLabels] doesn't match the device's localized text.
    private static func tapLastButton(in alert: XCUIElement) -> Bool {
        let buttonCount = alert.buttons.count
        guard buttonCount > 0 else {
            return false
        }
        alert.buttons.element(boundBy: buttonCount - 1).tap()
        return true
    }
}
