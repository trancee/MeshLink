// DiagnosticsView.swift
// MeshLink macOS Sample — Scrollable, filterable diagnostic event viewer
//
// Displays DiagnosticEvents from the MeshLink diagnostics Flow with
// severity filtering (INFO, WARN, ERROR). macOS-appropriate layout.

import SwiftUI

struct DiagnosticsView: View {
    @ObservedObject var viewModel: MeshLinkViewModel

    @State private var severityFilter: String? = nil

    private var filteredEvents: [DiagnosticEntry] {
        if let filter = severityFilter {
            return viewModel.diagnosticEntries.filter { $0.severity == filter }
        }
        return viewModel.diagnosticEntries
    }

    var body: some View {
        VStack(spacing: 8) {
            // Header
            HStack {
                Text("Diagnostics")
                    .font(.headline)

                Spacer()

                Text("\(filteredEvents.count) events")
                    .font(.caption)
                    .foregroundColor(.secondary)

                Button("Clear") {
                    viewModel.clearDiagnostics()
                }
                .controlSize(.small)
            }
            .padding(.horizontal)

            // Filter bar
            HStack(spacing: 6) {
                filterButton("All", filter: nil, count: viewModel.diagnosticEntries.count)
                filterButton("INFO", filter: "INFO",
                             count: viewModel.diagnosticEntries.filter { $0.severity == "INFO" }.count)
                filterButton("WARN", filter: "WARN",
                             count: viewModel.diagnosticEntries.filter { $0.severity == "WARN" }.count)
                filterButton("ERROR", filter: "ERROR",
                             count: viewModel.diagnosticEntries.filter { $0.severity == "ERROR" }.count)
                Spacer()
            }
            .padding(.horizontal)

            Divider()

            if filteredEvents.isEmpty {
                Spacer()
                Text(viewModel.diagnosticEntries.isEmpty
                     ? "No diagnostic events yet.\nStart the mesh to begin."
                     : "No events match the selected filter.")
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 2) {
                        ForEach(filteredEvents) { event in
                            DiagnosticEventRow(entry: event)
                        }
                    }
                    .padding(.horizontal)
                }
                .frame(maxHeight: .infinity)
            }
        }
        .padding(.vertical)
    }

    @ViewBuilder
    private func filterButton(_ label: String, filter: String?, count: Int) -> some View {
        Button {
            severityFilter = (severityFilter == filter) ? nil : filter
        } label: {
            Text("\(label) (\(count))")
                .font(.caption)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(
                    RoundedRectangle(cornerRadius: 8)
                        .fill(severityFilter == filter
                              ? severityColor(filter).opacity(0.2)
                              : Color.gray.opacity(0.1))
                )
        }
        .buttonStyle(.plain)
    }

    private func severityColor(_ severity: String?) -> Color {
        switch severity {
        case "INFO": return .green
        case "WARN": return .orange
        case "ERROR": return .red
        default: return .blue
        }
    }
}

// MARK: - Diagnostic Event Row

private struct DiagnosticEventRow: View {
    let entry: DiagnosticEntry

    var body: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(dotColor)
                .frame(width: 6, height: 6)
            Text(entry.code)
                .font(.system(.body, design: .monospaced))
                .fontWeight(.medium)
            if let payload = entry.payload, !payload.isEmpty {
                Text("— \(payload)")
                    .font(.system(.body, design: .monospaced))
                    .foregroundColor(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            Text(entry.timestamp)
                .font(.system(.caption, design: .monospaced))
                .foregroundColor(.secondary)
        }
        .padding(.vertical, 2)
    }

    private var dotColor: Color {
        switch entry.severity {
        case "INFO": return .green
        case "WARN": return .orange
        case "ERROR": return .red
        default: return .gray
        }
    }
}
