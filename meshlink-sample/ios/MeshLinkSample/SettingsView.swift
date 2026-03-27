// SettingsView.swift
// MeshLink iOS Sample — Configuration and status settings screen
//
// Provides a native iOS Form for selecting config presets, adjusting MTU,
// and viewing live mesh status. Changes are applied immediately via the
// ViewModel, which recreates the MeshLink instance as needed.

import SwiftUI

// MARK: - Settings View

/// Settings screen for configuring MeshLink and viewing mesh status.
///
/// Uses a standard SwiftUI `Form` with sections for:
/// - Configuration preset selection (segmented picker)
/// - MTU adjustment (slider)
/// - Read-only status displays (power mode, max message size, buffer capacity)
/// - Mesh status (running state, peer count)
/// - Reset to defaults
struct SettingsView: View {
    @ObservedObject var viewModel: MeshLinkViewModel
    @State private var mtuSliderValue: Double = 185

    var body: some View {
        NavigationStack {
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
            .onChange(of: viewModel.currentPreset) { _, newPreset in
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
                        .foregroundStyle(.secondary)
                }

                Slider(value: $mtuSliderValue, in: 23...512, step: 1) {
                    Text("MTU")
                } minimumValueLabel: {
                    Text("23").font(.caption2)
                } maximumValueLabel: {
                    Text("512").font(.caption2)
                }
                .onChange(of: mtuSliderValue) { _, newValue in
                    viewModel.updateMtu(Int(newValue))
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
            LabeledContent("Power Mode") {
                Label(viewModel.powerMode, systemImage: "bolt.fill")
                    .foregroundStyle(powerModeColor)
            }

            LabeledContent("Max Message Size") {
                Text(formatBytes(viewModel.maxMessageSize))
                    .font(.system(.body, design: .monospaced))
            }

            LabeledContent("Buffer Capacity") {
                Text(formatBytes(viewModel.bufferCapacity))
                    .font(.system(.body, design: .monospaced))
            }
        } header: {
            Label("Current Configuration", systemImage: "info.circle")
        }
    }

    private var powerModeColor: Color {
        switch viewModel.powerMode {
        case "LOW_POWER":  return .green
        case "BALANCED":   return .blue
        case "AGGRESSIVE": return .orange
        default:           return .secondary
        }
    }

    // MARK: - Mesh Status

    private var meshStatusSection: some View {
        Section {
            LabeledContent("Status") {
                HStack(spacing: 6) {
                    Circle()
                        .fill(viewModel.isRunning ? .green : .red)
                        .frame(width: 8, height: 8)
                    Text(viewModel.isRunning ? "Running" : "Stopped")
                }
            }

            LabeledContent("Connected Peers") {
                Text("\(viewModel.connectedPeers)")
                    .font(.system(.body, design: .monospaced))
            }

            LabeledContent("Reachable Peers") {
                Text("\(viewModel.reachablePeers)")
                    .font(.system(.body, design: .monospaced))
            }

            LabeledContent("Buffer Usage") {
                Text("\(viewModel.bufferUsagePercent)%")
                    .font(.system(.body, design: .monospaced))
            }
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

    /// Format a byte count as a human-readable string (e.g. "512 KB", "2 MB").
    private func formatBytes(_ bytes: Int) -> String {
        if bytes >= 1_048_576 {
            let mb = Double(bytes) / 1_048_576
            return String(format: "%.1f MB", mb)
        } else if bytes >= 1_024 {
            let kb = Double(bytes) / 1_024
            return String(format: "%.1f KB", kb)
        } else {
            return "\(bytes) B"
        }
    }
}

#Preview {
    SettingsView(viewModel: MeshLinkViewModel())
}
