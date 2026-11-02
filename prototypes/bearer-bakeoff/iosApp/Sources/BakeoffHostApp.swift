import ActivityKit
import Bakeoff
import SwiftUI
import UIKit
import notify

final class RunState: ObservableObject {
    @Published var status = "starting"

    init() {
        LiveActivityLab.prepare(arguments: CommandLine.arguments)
        BakeoffIos.shared.start(arguments: CommandLine.arguments) { [weak self] text in
            self?.status = text
        }
        // Discovery prototype: lock and screen-blank state for background cells (Darwin notifications, lab only).
        for name in ["com.apple.springboard.lockstate", "com.apple.springboard.hasBlankedScreen"] {
            var token: Int32 = 0
            notify_register_dispatch(name, &token, DispatchQueue.main) { token in
                var state: UInt64 = 0
                notify_get_state(token, &state)
                BakeoffIos.shared.systemEvent(name: name, state: Int64(bitPattern: state))
            }
            tokens.append(token)
        }
    }

    private var tokens: [Int32] = []
}

/// T4 (lab only): `live=1` lets the discovery code start a Live Activity; any other launch ends leftover activities.
enum LiveActivityLab {
    static func prepare(arguments: [String]) {
        guard #available(iOS 16.2, *) else { return }
        let run = arguments.first { $0.hasPrefix("run=") }.map { String($0.dropFirst(4)) } ?? "adhoc"
        if !arguments.contains("live=1") {
            Task {
                for activity in Activity<LabActivityAttributes>.activities {
                    await activity.end(nil, dismissalPolicy: .immediate)
                }
            }
            return
        }
        BakeoffIos.shared.liveActivityStarter = {
            let enabled = ActivityAuthorizationInfo().areActivitiesEnabled
            do {
                let activity = try Activity.request(
                    attributes: LabActivityAttributes(run: run),
                    content: ActivityContent(state: LabActivityAttributes.ContentState(status: "scanning"), staleDate: nil),
                    pushType: nil
                )
                return "started id=\(activity.id) enabled=\(enabled) state=\(activity.activityState)"
            } catch {
                return "error enabled=\(enabled): \(error)"
            }
        }
    }
}

@main
struct BakeoffHostApp: App {
    @StateObject private var state = RunState()

    var body: some Scene {
        WindowGroup {
            VStack(alignment: .leading, spacing: 16) {
                Text("MeshLink bearer bake-off (PROTOTYPE)").font(.headline)
                Text(state.status).font(.body.monospaced()).accessibilityIdentifier("status")
                Spacer()
            }
            .padding()
            .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        }
    }
}
