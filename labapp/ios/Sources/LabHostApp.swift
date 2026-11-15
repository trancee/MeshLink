import CoreBluetooth
import LabKit
import SwiftUI
import UIKit

/// Runs the shared lab scenario named by the process arguments and shows its latest status line plus
/// the app's Bluetooth authorization, which the grant UI test watches.
final class RunState: ObservableObject {
    @Published var status = "idle"
    @Published var bluetooth = RunState.authorization()
    private let session: LabSession

    init() {
        session = LabIosKt.labSession(arguments: CommandLine.arguments)
        session.start { [weak self] line in
            DispatchQueue.main.async {
                self?.status = line
                self?.bluetooth = RunState.authorization()
            }
        }
    }

    static func authorization() -> String {
        switch CBManager.authorization {
        case .allowedAlways: return "allowed_always"
        case .denied: return "denied"
        case .restricted: return "restricted"
        case .notDetermined: return "not_determined"
        @unknown default: return "unknown"
        }
    }
}

@main
struct LabHostApp: App {
    @StateObject private var state = RunState()

    var body: some Scene {
        WindowGroup {
            VStack(alignment: .leading, spacing: 16) {
                Text("MeshLink lab").font(.headline)
                Text("\(state.status)\nbt_auth=\(state.bluetooth)")
                    .font(.body.monospaced())
                    .accessibilityIdentifier("status")
                Spacer()
            }
            .padding()
            .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
            .onReceive(Timer.publish(every: 1, on: .main, in: .common).autoconnect()) { _ in
                state.bluetooth = RunState.authorization()
            }
        }
    }
}
