// MeshVisualizerView.swift
// MeshLink iOS Sample — Interactive mesh network topology visualizer
//
// Draws the local device and all discovered peers in a circular layout
// using SwiftUI Canvas. Links between nodes are colored by signal quality
// (green = strong, yellow = moderate, red = weak).
// Tap a peer node to see detailed connection, routing, and identity info.

import SwiftUI
import MeshLink

// MARK: - Mesh Visualizer View

/// Visualizes the BLE mesh network as a node-link diagram.
///
/// - The **self node** is always centered and drawn in the accent color.
/// - **Peer nodes** are arranged in a circle around the self node.
/// - **Link lines** connect every peer to the self node, with thickness
///   and color proportional to signal quality (derived from RSSI).
/// - **Tap** a peer node to open a detail panel with routing and identity info.
/// - A legend and summary badges are displayed for quick reference.
struct MeshVisualizerView: View {
    @ObservedObject var viewModel: MeshLinkViewModel

    /// Timer that fires periodically so `lastSeen` relative timestamps refresh.
    @State private var tick = Date()
    private let refreshTimer = Timer.publish(every: 5, on: .main, in: .common).autoconnect()

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 0) {
                    statusBadges
                        .padding(.horizontal)
                        .padding(.top, 8)

                    if viewModel.discoveredPeers.isEmpty {
                        emptyState
                    } else {
                        meshGraph
                    }

                    // Peer Detail Panel
                    if let selectedId = viewModel.selectedPeerId,
                       let peer = viewModel.discoveredPeers.first(where: { $0.id == selectedId }) {
                        PeerDetailPanel(
                            peer: peer,
                            detail: viewModel.peerDetail(selectedId),
                            onDismiss: { viewModel.selectPeer(nil) }
                        )
                        .padding(.horizontal)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                    }

                    legend
                        .padding(.horizontal)
                        .padding(.bottom, 8)
                }
            }
            .navigationTitle("Mesh Visualizer")
            .onReceive(refreshTimer) { tick = $0 }
            .animation(.easeInOut(duration: 0.25), value: viewModel.selectedPeerId)
        }
    }

    // MARK: - Status Badges

    private var statusBadges: some View {
        HStack(spacing: 12) {
            badge(
                label: "\(viewModel.discoveredPeers.count) peer\(viewModel.discoveredPeers.count == 1 ? "" : "s")",
                systemImage: "antenna.radiowaves.left.and.right",
                tint: .blue
            )
            badge(
                label: viewModel.powerMode,
                systemImage: "bolt.fill",
                tint: powerModeTint
            )
            badge(
                label: viewModel.isRunning ? "Running" : "Stopped",
                systemImage: viewModel.isRunning ? "checkmark.circle.fill" : "xmark.circle.fill",
                tint: viewModel.isRunning ? .green : .secondary
            )
        }
    }

    private func badge(label: String, systemImage: String, tint: Color) -> some View {
        Label(label, systemImage: systemImage)
            .font(.caption.weight(.medium))
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(tint.opacity(0.15), in: Capsule())
            .foregroundStyle(tint)
    }

    private var powerModeTint: Color {
        switch viewModel.powerMode {
        case "LOW_POWER": return .green
        case "BALANCED":  return .blue
        case "AGGRESSIVE": return .orange
        default:           return .secondary
        }
    }

    // MARK: - Empty State

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "circle.grid.hex")
                .font(.system(size: 48))
                .foregroundStyle(.secondary)
            Text("No Peers Discovered")
                .font(.title3.weight(.semibold))
            Text("Start the mesh to discover nearby peers.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxHeight: .infinity)
    }

    // MARK: - Mesh Graph with Tap Targets

    private var meshGraph: some View {
        GeometryReader { geometry in
            let center = CGPoint(x: geometry.size.width / 2, y: geometry.size.height / 2)
            let radius = min(geometry.size.width, geometry.size.height) * 0.35
            let peers = viewModel.discoveredPeers
            let positions = circularPositions(count: peers.count, center: center, radius: radius)

            ZStack {
                // Canvas draws links and the self node
                Canvas { context, size in
                    // Draw links
                    for (index, peer) in peers.enumerated() {
                        let peerPos = positions[index]
                        let quality = peer.signalQuality
                        var path = Path()
                        path.move(to: center)
                        path.addLine(to: peerPos)
                        context.stroke(
                            path,
                            with: .color(linkColor(quality: quality)),
                            lineWidth: linkWidth(quality: quality)
                        )
                    }

                    // Self node
                    let selfRadius: CGFloat = 24
                    let selfRect = CGRect(
                        x: center.x - selfRadius,
                        y: center.y - selfRadius,
                        width: selfRadius * 2,
                        height: selfRadius * 2
                    )
                    context.fill(Circle().path(in: selfRect), with: .color(.accentColor))
                    context.stroke(
                        Circle().path(in: selfRect),
                        with: .color(.accentColor.opacity(0.6)),
                        lineWidth: 3
                    )
                    let selfLabel = Text("ME")
                        .font(.system(size: 11, weight: .heavy, design: .rounded))
                        .foregroundColor(.white)
                    context.draw(context.resolve(selfLabel), at: center)
                }

                // Tappable peer overlays
                ForEach(Array(peers.enumerated()), id: \.element.id) { index, peer in
                    let pos = positions[index]
                    let isSelected = peer.id == viewModel.selectedPeerId

                    PeerNodeView(peer: peer, isSelected: isSelected)
                        .position(pos)
                        .onTapGesture {
                            withAnimation(.easeInOut(duration: 0.2)) {
                                if viewModel.selectedPeerId == peer.id {
                                    viewModel.selectPeer(nil)
                                } else {
                                    viewModel.selectPeer(peer.id)
                                }
                            }
                        }
                }
            }
        }
        .frame(height: 320)
        .padding()
    }

    // MARK: - Legend

    private var legend: some View {
        VStack(spacing: 6) {
            Divider()
            HStack(spacing: 16) {
                legendItem(color: .green, label: "Strong")
                legendItem(color: .yellow, label: "Moderate")
                legendItem(color: .red, label: "Weak")
            }
            .font(.caption2)
        }
    }

    private func legendItem(color: Color, label: String) -> some View {
        HStack(spacing: 4) {
            RoundedRectangle(cornerRadius: 2)
                .fill(color)
                .frame(width: 16, height: 4)
            Text(label)
                .foregroundStyle(.secondary)
        }
    }

    // MARK: - Layout Helpers

    private func circularPositions(count: Int, center: CGPoint, radius: CGFloat) -> [CGPoint] {
        guard count > 0 else { return [] }
        return (0..<count).map { i in
            let angle = (2 * .pi / Double(count)) * Double(i) - .pi / 2
            return CGPoint(
                x: center.x + radius * cos(angle),
                y: center.y + radius * sin(angle)
            )
        }
    }

    private func linkColor(quality: Double) -> Color {
        switch quality {
        case 0..<0.33:  return .red
        case 0.33..<0.66: return .yellow
        default:         return .green
        }
    }

    private func linkWidth(quality: Double) -> CGFloat {
        1.5 + quality * 3.0
    }
}

// MARK: - Peer Node View

private struct PeerNodeView: View {
    let peer: PeerInfo
    let isSelected: Bool

    var body: some View {
        ZStack {
            if isSelected {
                Circle()
                    .stroke(Color.white, lineWidth: 3)
                    .frame(width: 44, height: 44)
            }
            Circle()
                .fill(Color.blue.opacity(0.8))
                .frame(width: 36, height: 36)
            Circle()
                .stroke(Color.blue, lineWidth: 2)
                .frame(width: 36, height: 36)
            Text(peer.shortId)
                .font(.system(size: 10, weight: .bold, design: .monospaced))
                .foregroundColor(.white)
        }
    }
}

// MARK: - Peer Detail Panel

private struct PeerDetailPanel: View {
    let peer: PeerInfo
    let detail: PeerDetail?
    let onDismiss: () -> Void

    var body: some View {
        VStack(spacing: 8) {
            // Header
            HStack {
                Label("Peer Detail", systemImage: "info.circle.fill")
                    .font(.headline)
                Spacer()
                Button("Close", action: onDismiss)
                    .font(.subheadline)
            }

            Divider()

            // Identity & Signal
            detailRow("Peer ID", peer.id.uppercased())
            detailRow("Signal", "\(peer.rssi) dBm (\(rssiLabel(peer.rssi)))")
            detailRow("First seen", relativeTime(from: peer.firstSeen))
            detailRow("Last seen", relativeTime(from: peer.lastSeen))

            if let detail = detail {
                Divider()

                // Connection
                detailRow("Presence", presenceLabel(detail.presenceState))
                detailRow("Connection", detail.isDirectNeighbor ? "Direct (1-hop)" : "Multi-hop")

                Divider()

                // Routing
                routingSection(detail)

                // Reliability
                let reliability = (1.0 - detail.nextHopFailureRate) * 100
                detailRow(
                    "Reliability",
                    String(format: "%.0f%% (%d failures)", reliability, detail.nextHopFailureCount)
                )

                // Key
                if let keyHex = detail.publicKeyHex {
                    detailRow("Public key", String(keyHex.prefix(16)).uppercased() + "…")
                }
            }
        }
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(Color.secondary.opacity(0.12))
        )
    }

    @ViewBuilder
    private func routingSection(_ detail: PeerDetail) -> some View {
        if let nextHop = detail.routeNextHop {
            let cost = detail.routeCost?.doubleValue ?? 0.0
            let seq = detail.routeSequenceNumber?.uint32Value ?? 0
            detailRow("Next hop", String(nextHop.prefix(12)).uppercased() + "…")
            detailRow("Route cost", String(format: "%.2f", cost))
            detailRow("Seq #", "\(seq)")
        } else {
            detailRow("Route", "No route available")
        }
    }

    private func detailRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
            Spacer()
            Text(value)
                .font(.caption.monospaced())
                .fontWeight(.medium)
        }
    }

    private func rssiLabel(_ rssi: Int) -> String {
        if rssi >= -60 { return "Good" }
        if rssi >= -80 { return "Fair" }
        return "Poor"
    }

    private func presenceLabel(_ state: PresenceState) -> String {
        switch state {
        case .connected:    return "🟢 Connected"
        case .disconnected: return "🟡 Disconnected"
        case .gone:         return "🔴 Gone"
        default:            return "Unknown"
        }
    }

    private func relativeTime(from date: Date) -> String {
        let seconds = Int(Date().timeIntervalSince(date))
        switch seconds {
        case ..<5:    return "just now"
        case ..<60:   return "\(seconds)s ago"
        case ..<3600: return "\(seconds / 60)m \(seconds % 60)s ago"
        default:      return "\(seconds / 3600)h \((seconds % 3600) / 60)m ago"
        }
    }
}

#Preview {
    MeshVisualizerView(viewModel: MeshLinkViewModel())
}
