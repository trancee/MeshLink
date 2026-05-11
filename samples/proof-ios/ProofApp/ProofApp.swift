import SwiftUI

@main
struct ProofApp: App {
    init() {
        MeshLinkProofCryptoBridge.install()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
