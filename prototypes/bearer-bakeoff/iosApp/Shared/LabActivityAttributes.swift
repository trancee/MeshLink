import ActivityKit

// T4 (lab only): minimal Live Activity used to test Core Bluetooth's background privileges on iOS 26+.
@available(iOS 16.1, *)
struct LabActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var status: String
    }

    var run: String
}
