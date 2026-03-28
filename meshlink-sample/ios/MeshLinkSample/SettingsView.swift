// SettingsView.swift
// MeshLink iOS Sample — Configuration and status settings screen
// Compatible with iOS 15+.

import SwiftUI

struct SettingsView: View {
    @ObservedObject var viewModel: MeshLinkViewModel
    @State private var mtuSliderValue: Double = 185

    var body: some View {
        NavigationView {
            Form {
                configPresetSection
                mtuSection
                configDetailsSection
                meshStatusSection
                resetSection
            }
            .navigationTitle("Settings")
            .onAppear {
                mtuSliderValue = Double(viewModel.currentMtu)
            }
        }
        .navigationViewStyle(.stack)
    }

    // MARK: - Config Preset

    private var configPresetSection: some View {
        Section {
            Picker("Preset", selection: $viewModel.currentPreset) {
                ForEach(ConfigPreset.allCases) { preset in
                    Text(preset.rawValue).tag(preset)
                }
            }
            .pickerStyle(.segmented)
            .onChange(of: viewModel.currentPreset) { newPreset in
                viewModel.applyPreset(newPreset)
                mtuSliderValue = Double(viewModel.currentMtu)
            }
        } header: {
            Label("Configuration Preset", systemImage: "slider.horizontal.3")
        } footer: {
            Text(presetDescription)
        }
    }

    private var presetDescription: String {
        switch viewModel.currentPreset {
        case .chatOptimized:
            return "Tuned for small text payloads with moderate buffering."
        case .fileTransferOptimized:
            return "Larger messages and buffers for file transfer workloads."
        case .powerOptimized:
            return "Reduced buffer sizes and longer intervals to save battery."
        case .sensorOptimized:
            return "Tiny payloads with minimal buffering for sensor data."
        }
    }

    // MARK: - MTU Slider

    private var mtuSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("MTU")
                    Spacer()
                    Text("\(Int(mtuSliderValue)) bytes")
                        .font(.system(.body, design: .monospaced))
                        .foregroundColor(.secondary)
                }

                Slider(value: $mtuSliderValue, in: 23...512, step: 1) {
                    Text("MTU")
                } minimumValueLabel: {
                    Text("23").font(.caption2)
                } maximumValueLabel: {
                    Text("512").font(.caption2)
                }
                .onChange(of: mtuSliderValue) { newValue in
                    viewModel.currentMtu = Int(newValue)
                }
            }
        } header: {
            Label("MTU (Maximum Transmission Unit)", systemImage: "arrow.left.and.right")
        } footer: {
            Text("Larger MTU reduces chunking overhead but requires peer support.")
        }
    }

    // MARK: - Config Details (Read-Only)

    private var configDetailsSection: some View {
        Section {
            settingsRow("Power Mode", value: viewModel.powerMode)
            settingsRow("Max Message Size", value: formatBytes(viewModel.maxMessageSize))
            settingsRow("Buffer Capacity", value: formatBytes(viewModel.bufferCapacity))
        } header: {
            Label("Current Configuration", systemImage: "info.circle")
        }
    }

    // MARK: - Mesh Status

    private var meshStatusSection: some View {
        Section {
            HStack {
                Text("Status")
                Spacer()
                HStack(spacing: 6) {
                    Circle()
                        .fill(viewModel.isRunning ? .green : .red)
                        .frame(width: 8, height: 8)
                    Text(viewModel.isRunning ? "Running" : "Stopped")
                }
            }
            settingsRow("Connected Peers", value: "\(viewModel.connectedPeers)")
            settingsRow("Reachable Peers", value: "\(viewModel.reachablePeers)")
            settingsRow("Buffer Usage", value: "\(viewModel.bufferUsagePercent)%")
        } header: {
            Label("Mesh Status", systemImage: "network")
        }
    }

    // MARK: - Reset

    private var resetSection: some View {
        Section {
            Button(role: .destructive) {
                viewModel.resetToDefaults()
                mtuSliderValue = Double(viewModel.currentMtu)
            } label: {
                Label("Reset to Defaults", systemImage: "arrow.counterclockwise")
                    .frame(maxWidth: .infinity, alignment: .center)
            }
        } footer: {
            Text("Reverts to Chat preset with default MTU (185 bytes).")
        }
    }

    // MARK: - Helpers

    private func settingsRow(_ label: String, value: String) -> some View {
        HStack {
            Text(label)
            Spacer()
            Text(value)
                .foregroundColor(.secondary)
                .font(.system(.body, design: .monospaced))
        }
    }

    private func formatBytes(_ bytes: Int) -> String {
        if bytes >= 1_048_576 {
            return String(format: "%.1f MB", Double(bytes) / 1_048_576)
        } else if bytes >= 1_024 {
            return String(format: "%.1f KB", Double(bytes) / 1_024)
        } else {
            return "\(bytes) B"
        }
    }
}

#Preview {
    SettingsView(viewModel: MeshLinkViewModel())
}
