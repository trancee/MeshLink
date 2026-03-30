// MeshVisualizerView.swift
// MeshLink macOS Sample — Interactive mesh network topology visualizer
//
// Renders discovered peers as nodes in a circular layout with signal
// strength indicators. Click a peer node to see detailed info.
// The local device is at the center.

import SwiftUI
import MeshLink

struct MeshVisualizerView: View {
    @ObservedObject var viewModel: MeshLinkViewModel

    var body: some View {
        VStack(spacing: 12) {
            Text("Mesh Topology")
                .font(.title2)
                .fontWeight(.semibold)

            if viewModel.discoveredPeers.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "network")
                        .font(.system(size: 48))
                        .foregroundColor(.secondary)
                    Text("No peers discovered")
                        .foregroundColor(.secondary)
                    if !viewModel.isRunning {
                        Text("Start the mesh to discover peers")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                GeometryReader { geometry in
                    let center = CGPoint(x: geometry.size.width / 2, y: geometry.size.height / 2)
                    let radius = min(geometry.size.width, geometry.size.height) * 0.35

                    ZStack {
                        // Local node at center
                        VStack(spacing: 4) {
                            Circle()
                                .fill(Color.blue)
                                .frame(width: 24, height: 24)
                            Text("Local")
                                .font(.caption2)
                                .fontWeight(.bold)
                        }
                        .position(center)

                        // Peer nodes in a circle
                        ForEach(Array(viewModel.discoveredPeers.enumerated()), id: \.element.id) { index, peer in
                            let angle = 2 * .pi * Double(index) / Double(viewModel.discoveredPeers.count) - .pi / 2
                            let peerCenter = CGPoint(
                                x: center.x + radius * cos(angle),
                                y: center.y + radius * sin(angle)
                            )

                            // Connection line
                            Path { path in
                                path.move(to: center)
                                path.addLine(to: peerCenter)
                            }
                            .stroke(
                                Color.green.opacity(peer.signalQuality),
                                lineWidth: max(1, peer.signalQuality * 3)
                            )

                            // Peer node (tappable)
                            VStack(spacing: 2) {
                                ZStack {
                                    if peer.id == viewModel.selectedPeerId {
                                        Circle()
                                            .stroke(Color.white, lineWidth: 2)
                                            .frame(width: 26, height: 26)
                                    }
                                    Circle()
                                        .fill(Color.green)
                                        .frame(width: 18, height: 18)
                                }
                                Text(peer.shortId)
                                    .font(.caption2)
                                Text("\(peer.rssi) dBm")
                                    .font(.system(size: 9))
                                    .foregroundColor(.secondary)
                            }
                            .position(peerCenter)
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
            }

            // Peer Detail Panel
            if let selectedId = viewModel.selectedPeerId,
               let peer = viewModel.discoveredPeers.first(where: { $0.id == selectedId }) {
                MacPeerDetailPanel(
                    peer: peer,
                    detail: viewModel.peerDetail(selectedId),
                    onDismiss: { viewModel.selectPeer(nil) }
                )
                .transition(.opacity)
            }

            // Stats bar
            HStack(spacing: 20) {
                Label("\(viewModel.connectedPeers) connected", systemImage: "link")
                Label("\(viewModel.reachablePeers) reachable", systemImage: "antenna.radiowaves.left.and.right")
                Label(viewModel.powerMode, systemImage: "battery.50percent")
                Label("\(viewModel.bufferUsagePercent)% buffer", systemImage: "memorychip")
            }
            .font(.caption)
            .foregroundColor(.secondary)
        }
        .padding()
        .animation(.easeInOut(duration: 0.25), value: viewModel.selectedPeerId)
    }
}

// MARK: - macOS Peer Detail Panel

private struct MacPeerDetailPanel: View {
    let peer: PeerInfo
    let detail: PeerDetail?
    let onDismiss: () -> Void

    var body: some View {
        VStack(spacing: 6) {
            HStack {
                Label("Peer Detail", systemImage: "info.circle.fill")
                    .font(.headline)
                Spacer()
                Button("Close", action: onDismiss)
                    .buttonStyle(.plain)
                    .font(.subheadline)
                    .foregroundColor(.accentColor)
            }

            Divider()

            detailRow("Peer ID", peer.id.uppercased())
            detailRow("Signal", "\(peer.rssi) dBm (\(rssiLabel(peer.rssi)))")
            detailRow("First seen", relativeTime(from: peer.firstSeen))
            detailRow("Last seen", relativeTime(from: peer.lastSeen))

            if let detail = detail {
                Divider()

                detailRow("Presence", presenceLabel(detail.presenceState))
                detailRow("Connection", detail.isDirectNeighbor ? "Direct (1-hop)" : "Multi-hop")

                Divider()

                routingSection(detail)

                let reliability = (1.0 - detail.nextHopFailureRate) * 100
                detailRow(
                    "Reliability",
                    String(format: "%.0f%% (%d failures)", reliability, detail.nextHopFailureCount)
                )

                if let keyHex = detail.publicKeyHex {
                    detailRow("Public key", String(keyHex.prefix(16)).uppercased() + "…")
                }
            }
        }
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 8)
                .fill(Color(nsColor: .controlBackgroundColor))
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
                .foregroundColor(.secondary)
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
