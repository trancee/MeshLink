import MeshLinkReference
import SwiftUI
import UIKit

private struct ReferenceRootViewRepresentable: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView {
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
            return createReferenceLiveAutomationRootView(
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
            return createReferenceAutomationRootView(
                storageSubdirectory: storageSubdirectory,
                blocked: blocked
            )
        }
        return createReferenceRootView()
    }

    func updateUIView(_ uiView: UIView, context: Context) {
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
