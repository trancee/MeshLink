import ReferenceAppShared
import SwiftUI
import UIKit

private struct ReferenceRootViewControllerRepresentable: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        ReferenceViewControllerKt.createReferenceRootViewController()
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
