// MeshVisualizerView.swift
// MeshLink iOS Sample — Interactive mesh network topology visualizer
//
// Draws the local device and all discovered peers in a circular layout
// using SwiftUI Canvas. Links between nodes are colored by signal quality
// (green = strong, yellow = moderate, red = weak).

import SwiftUI

// MARK: - Mesh Visualizer View

/// Visualizes the BLE mesh network as a node-link diagram.
///
/// - The **self node** is always centered and drawn in the accent color.
/// - **Peer nodes** are arranged in a circle around the self node.
/// - **Link lines** connect every peer to the self node, with thickness
///   and color proportional to signal quality (derived from RSSI).
/// - A legend and summary badges are displayed for quick reference.
struct MeshVisualizerView: View {
    @ObservedObject var viewModel: MeshLinkViewModel

    /// Timer that fires periodically so `lastSeen` relative timestamps refresh.
    @State private var tick = Date()
    private let refreshTimer = Timer.publish(every: 5, on: .main, in: .common).autoconnect()

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                statusBadges
                    .padding(.horizontal)
                    .padding(.top, 8)

                if viewModel.discoveredPeers.isEmpty {
                    emptyState
                } else {
                    meshCanvas
                }

                legend
                    .padding(.horizontal)
                    .padding(.bottom, 8)
            }
            .navigationTitle("Mesh Visualizer")
            .onReceive(refreshTimer) { tick = $0 }
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
        ContentUnavailableView(
            "No Peers Discovered",
            systemImage: "circle.grid.hex",
            description: Text("Start the mesh to discover nearby peers.")
        )
        .frame(maxHeight: .infinity)
    }

    // MARK: - Canvas

    private var meshCanvas: some View {
        Canvas { context, size in
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let radius = min(size.width, size.height) * 0.35
            let peers = viewModel.discoveredPeers
            let peerPositions = circularPositions(count: peers.count, center: center, radius: radius)

            // Draw links from self (center) to each peer.
            for (index, peer) in peers.enumerated() {
                let peerPos = peerPositions[index]
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

            // Draw peer nodes.
            let peerNodeRadius: CGFloat = 18
            for (index, peer) in peers.enumerated() {
                let pos = peerPositions[index]
                let rect = CGRect(
                    x: pos.x - peerNodeRadius,
                    y: pos.y - peerNodeRadius,
                    width: peerNodeRadius * 2,
                    height: peerNodeRadius * 2
                )

                // Filled circle
                context.fill(Circle().path(in: rect), with: .color(.blue.opacity(0.8)))
                // Border
                context.stroke(Circle().path(in: rect), with: .color(.blue), lineWidth: 2)

                // Peer label (short ID)
                let label = Text(peer.shortId)
                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                    .foregroundColor(.white)
                context.draw(context.resolve(label), at: pos)
            }

            // Draw self node (centered, larger, accent-colored).
            let selfRadius: CGFloat = 24
            let selfRect = CGRect(
                x: center.x - selfRadius,
                y: center.y - selfRadius,
                width: selfRadius * 2,
                height: selfRadius * 2
            )
            context.fill(Circle().path(in: selfRect), with: .color(.accentColor))
            context.stroke(Circle().path(in: selfRect), with: .color(.accentColor.opacity(0.6)), lineWidth: 3)

            let selfLabel = Text("ME")
                .font(.system(size: 11, weight: .heavy, design: .rounded))
                .foregroundColor(.white)
            context.draw(context.resolve(selfLabel), at: center)

        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
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

    /// Arrange `count` points evenly on a circle.
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

    /// Map signal quality `[0, 1]` to a color gradient: red → yellow → green.
    private func linkColor(quality: Double) -> Color {
        switch quality {
        case 0..<0.33:  return .red
        case 0.33..<0.66: return .yellow
        default:         return .green
        }
    }

    /// Map signal quality to line thickness (thicker = stronger).
    private func linkWidth(quality: Double) -> CGFloat {
        1.5 + quality * 3.0
    }
}

#Preview {
    MeshVisualizerView(viewModel: MeshLinkViewModel())
}
