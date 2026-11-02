import Bakeoff
import SwiftUI
import UIKit
import notify

final class RunState: ObservableObject {
    @Published var status = "starting"

    init() {
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
