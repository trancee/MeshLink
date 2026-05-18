import SwiftUI

@main
struct ProofApp: App {
    init() {
        MeshLinkProofCryptoBridge.install()
        MeshLinkProofTransportBridge.install()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
