// MeshLinkSampleApp.swift
// MeshLink macOS Sample — SwiftUI entry point
//
// Demonstrates integrating the MeshLink Kotlin Multiplatform
// BLE mesh messaging library into a macOS app using Swift Package Manager.

import SwiftUI

@main
struct MeshLinkSampleApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .frame(minWidth: 800, minHeight: 600)
        }
    }
}
