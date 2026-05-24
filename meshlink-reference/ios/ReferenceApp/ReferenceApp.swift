import ReferenceAppShared
import SwiftUI
import UIKit

private struct ReferenceRootViewControllerRepresentable: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        let environment = ProcessInfo.processInfo.environment
        let automationMode = environment["MESHLINK_REFERENCE_AUTOMATION_MODE"]
        if automationMode == "live-proof" {
            let storageSubdirectory =
                environment["MESHLINK_REFERENCE_AUTOMATION_STORAGE_SUBDIRECTORY"]
                ?? "default"
            let appId = environment["MESHLINK_REFERENCE_APP_ID"] ?? "demo.meshlink.reference.live"
            let role = environment["MESHLINK_REFERENCE_AUTOMATION_ROLE"] ?? "passive"
            let requiredPeerCount = Int32(environment["MESHLINK_REFERENCE_AUTOMATION_REQUIRED_PEER_COUNT"] ?? "1") ?? 1
            let targetPeerIndex = Int32(environment["MESHLINK_REFERENCE_AUTOMATION_TARGET_PEER_INDEX"] ?? "0") ?? 0
            let targetPeerId = environment["MESHLINK_REFERENCE_AUTOMATION_TARGET_PEER_ID"]
            let scenario = environment["MESHLINK_REFERENCE_AUTOMATION_SCENARIO"] ?? "direct-guided"
            return createReferenceLiveAutomationRootViewController(
                storageSubdirectory: storageSubdirectory,
                appId: appId,
                role: role,
                requiredPeerCount: requiredPeerCount,
                targetPeerIndex: targetPeerIndex,
                targetPeerId: targetPeerId,
                scenario: scenario
            )
        }

        let isAutomationEnabled = environment["MESHLINK_REFERENCE_UI_AUTOMATION"] == "true"
            || automationMode == "scripted"
        if isAutomationEnabled {
            let storageSubdirectory =
                environment["MESHLINK_REFERENCE_AUTOMATION_STORAGE_SUBDIRECTORY"]
                ?? "default"
            let blocked = environment["MESHLINK_REFERENCE_AUTOMATION_BLOCKED"] == "true"
            return createReferenceAutomationRootViewController(
                storageSubdirectory: storageSubdirectory,
                blocked: blocked
            )
        }
        return createReferenceRootViewController()
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
            ReferenceRootViewControllerRepresentable()
        }
    }
}
