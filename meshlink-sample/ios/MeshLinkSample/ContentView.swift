// ContentView.swift
// MeshLink iOS Sample — Main SwiftUI interface
//
// Uses a TabView to provide Chat, Mesh Visualizer, and Settings screens.
// Compatible with iOS 15+.

import SwiftUI

struct ContentView: View {
    @StateObject private var viewModel = MeshLinkViewModel()

    var body: some View {
        TabView {
            ChatView(viewModel: viewModel)
                .tabItem {
                    Label("Chat", systemImage: "message")
                }

            MeshVisualizerView(viewModel: viewModel)
                .tabItem {
                    Label("Mesh", systemImage: "circle.grid.hex")
                }

            SettingsView(viewModel: viewModel)
                .tabItem {
                    Label("Settings", systemImage: "gear")
                }
        }
    }
}

// MARK: - Chat View

private struct ChatView: View {
    @ObservedObject var viewModel: MeshLinkViewModel
    @State private var recipientId = ""
    @State private var messageText = ""

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 16) {
                    healthCard
                    meshToggleButton
                    messagingSection
                    Divider()
                    logSection
                }
                .padding(.horizontal)
            }
            .navigationTitle("MeshLink Sample")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Clear Log") {
                        viewModel.clearLog()
                    }
                    .font(.caption)
                }
            }
        }
        .navigationViewStyle(.stack)
    }

    // MARK: - Health Status Card

    private var healthCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Mesh Health", systemImage: "heart.fill")
                .font(.headline)

            VStack(alignment: .leading, spacing: 4) {
                healthRow("Connected peers:", value: "\(viewModel.connectedPeers)")
                healthRow("Reachable peers:", value: "\(viewModel.reachablePeers)")
                healthRow("Power mode:", value: viewModel.powerMode)
                healthRow("Buffer usage:", value: "\(viewModel.bufferUsagePercent)%")
                healthRow("Active transfers:", value: "\(viewModel.activeTransfers)")
            }
            .font(.subheadline)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12))
    }

    private func healthRow(_ label: String, value: String) -> some View {
        HStack {
            Text(label)
                .foregroundColor(.secondary)
            Spacer()
            Text(value)
                .fontWeight(.medium)
        }
    }

    // MARK: - Start / Stop Toggle

    private var meshToggleButton: some View {
        Button {
            if viewModel.isRunning {
                viewModel.stopMesh()
            } else {
                viewModel.startMesh()
            }
        } label: {
            Label(
                viewModel.isRunning ? "Stop Mesh" : "Start Mesh",
                systemImage: viewModel.isRunning ? "stop.circle.fill" : "play.circle.fill"
            )
            .frame(maxWidth: .infinity)
            .padding(.vertical, 4)
        }
        .buttonStyle(.borderedProminent)
        .tint(viewModel.isRunning ? .red : .blue)
    }

    // MARK: - Messaging Section

    private var messagingSection: some View {
        VStack(spacing: 8) {
            TextField("Recipient ID (hex)", text: $recipientId)
                .textFieldStyle(.roundedBorder)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .font(.system(.body, design: .monospaced))

            HStack(spacing: 8) {
                TextField("Message", text: $messageText)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit(sendMessage)

                Button(action: sendMessage) {
                    Image(systemName: "paperplane.fill")
                        .padding(.horizontal, 4)
                }
                .buttonStyle(.borderedProminent)
                .disabled(!canSend)
            }
        }
    }

    // MARK: - Event Log

    private var logSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Event Log", systemImage: "list.bullet.rectangle")
                .font(.headline)

            if viewModel.logEntries.isEmpty {
                Text("No events yet. Start the mesh to begin.")
                    .foregroundColor(.secondary)
                    .font(.subheadline)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.vertical, 32)
            } else {
                LazyVStack(alignment: .leading, spacing: 4) {
                    ForEach(Array(viewModel.logEntries.enumerated()), id: \.offset) { _, entry in
                        Text(entry)
                            .font(.system(.caption, design: .monospaced))
                            .textSelection(.enabled)
                    }
                }
            }
        }
    }

    // MARK: - Helpers

    private var canSend: Bool {
        viewModel.isRunning && !recipientId.isEmpty && !messageText.isEmpty
    }

    private func sendMessage() {
        guard canSend else { return }
        viewModel.sendMessage(to: recipientId, message: messageText)
        messageText = ""
    }
}

#Preview {
    ContentView()
}
