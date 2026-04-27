import SwiftUI

/// Application entry point.
///
/// The app binds a single `MeshEngineBridge` instance to the view hierarchy so the engine
/// lifetime is tied to the application lifecycle rather than to any individual view.
@main
struct SampleApp: App {
    @StateObject private var bridge = MeshEngineBridge()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(bridge)
        }
    }
}
