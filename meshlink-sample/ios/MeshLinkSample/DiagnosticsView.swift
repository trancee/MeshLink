// DiagnosticsView.swift
// MeshLink iOS Sample — Scrollable, filterable diagnostic event viewer
//
// Displays DiagnosticEvents from the MeshLink diagnostics Flow with
// severity filtering (INFO, WARN, ERROR) and auto-scroll.

import SwiftUI
import MeshLink

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
        NavigationView {
            VStack(spacing: 0) {
                // Severity filter chips
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        filterButton("All", filter: nil, count: viewModel.diagnosticEntries.count)
                        filterButton("INFO", filter: "INFO",
                                     count: viewModel.diagnosticEntries.filter { $0.severity == "INFO" }.count)
                        filterButton("WARN", filter: "WARN",
                                     count: viewModel.diagnosticEntries.filter { $0.severity == "WARN" }.count)
                        filterButton("ERROR", filter: "ERROR",
                                     count: viewModel.diagnosticEntries.filter { $0.severity == "ERROR" }.count)
                    }
                    .padding(.horizontal)
                    .padding(.vertical, 8)
                }

                Divider()

                if filteredEvents.isEmpty {
                    Spacer()
                    Text(viewModel.diagnosticEntries.isEmpty
                         ? "No diagnostic events yet.\nStart the mesh to begin."
                         : "No events match the selected filter.")
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                        .padding()
                    Spacer()
                } else {
                    ScrollViewReader { proxy in
                        List(filteredEvents) { event in
                            DiagnosticEventRow(entry: event)
                        }
                        .listStyle(.plain)
                        .onChange(of: filteredEvents.count) { _ in
                            if let last = filteredEvents.last {
                                withAnimation {
                                    proxy.scrollTo(last.id, anchor: .bottom)
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Diagnostics")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Clear") {
                        viewModel.clearDiagnostics()
                    }
                    .font(.caption)
                }
            }
        }
        .navigationViewStyle(.stack)
    }

    @ViewBuilder
    private func filterButton(_ label: String, filter: String?, count: Int) -> some View {
        Button {
            severityFilter = (severityFilter == filter) ? nil : filter
        } label: {
            Text("\(label) (\(count))")
                .font(.caption)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(
                    RoundedRectangle(cornerRadius: 16)
                        .fill(severityFilter == filter
                              ? severityColor(filter).opacity(0.2)
                              : Color.gray.opacity(0.1))
                )
                .foregroundColor(severityFilter == filter
                                 ? severityColor(filter)
                                 : .primary)
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
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Circle()
                    .fill(dotColor)
                    .frame(width: 8, height: 8)
                Text(entry.code)
                    .font(.system(.caption, design: .monospaced))
                    .fontWeight(.medium)
                Spacer()
                Text(entry.timestamp)
                    .font(.system(.caption2, design: .monospaced))
                    .foregroundColor(.secondary)
            }
            if let payload = entry.payload, !payload.isEmpty {
                Text(payload)
                    .font(.system(.caption2, design: .monospaced))
                    .foregroundColor(.secondary)
            }
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
