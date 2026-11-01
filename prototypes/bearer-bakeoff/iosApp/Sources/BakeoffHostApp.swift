import Bakeoff
import SwiftUI
import UIKit

final class RunState: ObservableObject {
    @Published var status = "starting"

    init() {
        BakeoffIos.shared.start(arguments: CommandLine.arguments) { [weak self] text in
            self?.status = text
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
                Text(state.status).font(.body.monospaced())
                Spacer()
            }
            .padding()
            .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        }
    }
}
