// ContentView.swift
// MeshLink iOS Sample — Main SwiftUI interface
//
// This view mirrors the Android sample's MainActivity.kt Compose UI,
// providing the same feature set with idiomatic SwiftUI patterns.

import SwiftUI

struct ContentView: View {
    @StateObject private var viewModel = MeshLinkViewModel()
    @State private var recipientId = ""
    @State private var messageText = ""

    var body: some View {
        NavigationStack {
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
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Clear Log") {
                        viewModel.clearLog()
                    }
                    .font(.caption)
                }
            }
        }
    }

    // MARK: - Health Status Card

    private var healthCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Mesh Health", systemImage: "heart.fill")
                .font(.headline)

            Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 4) {
                GridRow {
                    Text("Connected peers:")
                        .foregroundStyle(.secondary)
                    Text("\(viewModel.connectedPeers)")
                        .fontWeight(.medium)
                }
                GridRow {
                    Text("Reachable peers:")
                        .foregroundStyle(.secondary)
                    Text("\(viewModel.reachablePeers)")
                        .fontWeight(.medium)
                }
                GridRow {
                    Text("Power mode:")
                        .foregroundStyle(.secondary)
                    Text(viewModel.powerMode)
                        .fontWeight(.medium)
                }
                GridRow {
                    Text("Buffer usage:")
                        .foregroundStyle(.secondary)
                    Text("\(viewModel.bufferUsagePercent)%")
                        .fontWeight(.medium)
                }
                GridRow {
                    Text("Active transfers:")
                        .foregroundStyle(.secondary)
                    Text("\(viewModel.activeTransfers)")
                        .fontWeight(.medium)
                }
            }
            .font(.subheadline)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12))
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
                    .foregroundStyle(.secondary)
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
