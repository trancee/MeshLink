// ContentView.swift
// MeshLink macOS Sample — Main SwiftUI interface
//
// Three-tab layout: Chat log, Mesh Visualizer, and Settings.
// Mirrors the iOS sample with macOS-appropriate UI patterns.

import SwiftUI

struct ContentView: View {
    @StateObject private var viewModel = MeshLinkViewModel()

    var body: some View {
        TabView {
            ChatView(viewModel: viewModel)
                .tabItem { Label("Chat", systemImage: "message") }

            MeshVisualizerView(viewModel: viewModel)
                .tabItem { Label("Mesh", systemImage: "network") }

            SettingsView(viewModel: viewModel)
                .tabItem { Label("Settings", systemImage: "gear") }
        }
        .padding()
    }
}

// MARK: - Chat View

struct ChatView: View {
    @ObservedObject var viewModel: MeshLinkViewModel

    @State private var recipientId = ""
    @State private var messageText = ""

    var body: some View {
        VStack(spacing: 12) {
            // Status bar
            HStack {
                Circle()
                    .fill(viewModel.isRunning ? Color.green : Color.red)
                    .frame(width: 10, height: 10)
                Text(viewModel.isRunning ? "Running" : "Stopped")
                    .font(.headline)

                Spacer()

                Text("Peers: \(viewModel.peerCount)")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            }
            .padding(.horizontal)

            // Controls
            HStack {
                Button(viewModel.isRunning ? "Stop" : "Start") {
                    if viewModel.isRunning {
                        viewModel.stopMesh()
                    } else {
                        viewModel.startMesh()
                    }
                }
                .controlSize(.large)

                Spacer()

                HStack(spacing: 4) {
                    TextField("Recipient ID (hex)", text: $recipientId)
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 200)

                    TextField("Message", text: $messageText)
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 200)

                    Button("Send") {
                        viewModel.sendMessage(to: recipientId, message: messageText)
                        messageText = ""
                    }
                    .disabled(recipientId.isEmpty || messageText.isEmpty || !viewModel.isRunning)
                }
            }
            .padding(.horizontal)

            // Log
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 2) {
                    ForEach(Array(viewModel.logEntries.enumerated()), id: \.offset) { _, entry in
                        Text(entry)
                            .font(.system(.body, design: .monospaced))
                            .textSelection(.enabled)
                    }
                }
                .padding(.horizontal)
            }
            .frame(maxHeight: .infinity)

            // Footer
            HStack {
                Button("Clear Log") {
                    viewModel.clearLog()
                }
                .controlSize(.small)

                Spacer()

                Text("\(viewModel.logEntries.count) entries")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            .padding(.horizontal)
        }
        .padding(.vertical)
    }
}
