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
            return ReferenceViewControllerKt.createReferenceLiveAutomationRootViewController(
                storageSubdirectory: storageSubdirectory,
                appId: appId,
                role: role
            )
        }

        let isAutomationEnabled = environment["MESHLINK_REFERENCE_UI_AUTOMATION"] == "true"
            || automationMode == "scripted"
        if isAutomationEnabled {
            let storageSubdirectory =
                environment["MESHLINK_REFERENCE_AUTOMATION_STORAGE_SUBDIRECTORY"]
                ?? "default"
            let blocked = environment["MESHLINK_REFERENCE_AUTOMATION_BLOCKED"] == "true"
            return ReferenceViewControllerKt.createReferenceAutomationRootViewController(
                storageSubdirectory: storageSubdirectory,
                blocked: blocked
            )
        }
        return ReferenceViewControllerKt.createReferenceRootViewController()
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
                .ignoresSafeArea()
        }
    }
}
