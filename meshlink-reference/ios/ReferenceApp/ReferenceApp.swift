import MeshLinkReference
import SwiftUI
import UIKit

private struct ReferenceRootViewRepresentable: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        let environment = ProcessInfo.processInfo.environment
        let storageSubdirectory =
            environment["MESHLINK_REFERENCE_AUTOMATION_STORAGE_SUBDIRECTORY"] ?? "default"
        let appId = environment["MESHLINK_REFERENCE_APP_ID"] ?? "demo.meshlink.reference.ios"
        let targetPeerId = environment["MESHLINK_REFERENCE_AUTOMATION_TARGET_PEER_ID"]
        let scenario = environment["MESHLINK_REFERENCE_AUTOMATION_SCENARIO"]
        // Normalize mode/role the same way Android's MainActivity does, so the shared
        // automation logging markers (e.g. "REFERENCE_AUTOMATION started mode=LIVE_PROOF
        // role=SENDER") match across platforms for the headless proof-harness scripts.
        let automationMode = environment["MESHLINK_REFERENCE_AUTOMATION_MODE"]
            .map { $0.uppercased().replacingOccurrences(of: "-", with: "_") }
        let role = environment["MESHLINK_REFERENCE_AUTOMATION_ROLE"]
            .map { $0.uppercased().replacingOccurrences(of: "-", with: "_") }
        let autoStartMesh = role == "SENDER" || role == "PASSIVE"
        let disableAutoSend = environment["MESHLINK_REFERENCE_DISABLE_AUTO_SEND"] == "true"
        let autoSendHello = role == "SENDER" && !disableAutoSend

        return ReferenceViewController(
            appId: appId,
            targetPeerId: targetPeerId,
            storageSubdirectory: storageSubdirectory,
            automationMode: automationMode,
            automationRole: role,
            automationScenario: scenario,
            autoStartMesh: autoStartMesh,
            autoSendHello: autoSendHello
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
    }
}

@main
struct ReferenceAppApp: App {
    init() {
        MeshLinkReferenceCryptoBridge.install()
        MeshLinkReferenceTransportBridge.install()
    }

    var body: some Scene {
        WindowGroup {
            ReferenceRootViewRepresentable()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .ignoresSafeArea()
        }
    }
}
