// MeshVisualizerView.swift
// MeshLink macOS Sample — Interactive mesh network topology visualizer
//
// Renders discovered peers as nodes in a circular layout with signal
// strength indicators. The local device is at the center.

import SwiftUI

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

                            // Peer node
                            VStack(spacing: 2) {
                                Circle()
                                    .fill(Color.green)
                                    .frame(width: 18, height: 18)
                                Text(peer.shortId)
                                    .font(.caption2)
                                Text("\(peer.rssi) dBm")
                                    .font(.system(size: 9))
                                    .foregroundColor(.secondary)
                            }
                            .position(peerCenter)
                        }
                    }
                }
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
    }
}
