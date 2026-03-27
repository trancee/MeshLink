// SettingsView.swift
// MeshLink macOS Sample — Configuration and status settings

import SwiftUI

struct SettingsView: View {
    @ObservedObject var viewModel: MeshLinkViewModel

    var body: some View {
        Form {
            Section("Configuration Preset") {
                Picker("Preset", selection: $viewModel.currentPreset) {
                    ForEach(ConfigPreset.allCases) { preset in
                        Text(preset.rawValue).tag(preset)
                    }
                }
                .onChange(of: viewModel.currentPreset) { _, newValue in
                    viewModel.applyPreset(newValue)
                }
            }

            Section("Transport") {
                HStack {
                    Text("MTU")
                    Slider(
                        value: Binding(
                            get: { Double(viewModel.currentMtu) },
                            set: { viewModel.updateMtu(Int($0)) }
                        ),
                        in: 23...517,
                        step: 1
                    )
                    Text("\(viewModel.currentMtu)")
                        .monospacedDigit()
                        .frame(width: 40, alignment: .trailing)
                }
            }

            Section("Current Config") {
                LabeledContent("Max Message Size", value: formatBytes(viewModel.maxMessageSize))
                LabeledContent("Buffer Capacity", value: formatBytes(viewModel.bufferCapacity))
                LabeledContent("MTU", value: "\(viewModel.currentMtu) bytes")
            }

            Section("Mesh Health") {
                LabeledContent("Connected Peers", value: "\(viewModel.connectedPeers)")
                LabeledContent("Reachable Peers", value: "\(viewModel.reachablePeers)")
                LabeledContent("Power Mode", value: viewModel.powerMode)
                LabeledContent("Buffer Usage", value: "\(viewModel.bufferUsagePercent)%")
                LabeledContent("Active Transfers", value: "\(viewModel.activeTransfers)")
            }

            Section {
                Button("Reset to Defaults") {
                    viewModel.resetToDefaults()
                }
            }
        }
        .formStyle(.grouped)
        .padding()
    }

    private func formatBytes(_ bytes: Int) -> String {
        if bytes >= 1_048_576 {
            return String(format: "%.1f MB", Double(bytes) / 1_048_576)
        } else if bytes >= 1024 {
            return String(format: "%.0f KB", Double(bytes) / 1024)
        }
        return "\(bytes) B"
    }
}
