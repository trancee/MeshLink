import ReferenceAppShared
import SwiftUI
import UIKit

private struct ReferenceRootViewControllerRepresentable: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        let environment = ProcessInfo.processInfo.environment
        let isAutomationEnabled = environment["MESHLINK_REFERENCE_UI_AUTOMATION"] == "true"
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
    var body: some Scene {
        WindowGroup {
            ReferenceRootViewControllerRepresentable()
                .ignoresSafeArea()
        }
    }
}
