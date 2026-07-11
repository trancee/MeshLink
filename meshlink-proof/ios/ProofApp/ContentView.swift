import SwiftUI

struct ContentView: View {
    @StateObject private var viewModel = ProofViewModel()
    @State private var peerDetailsVisible: Bool = false
    @State private var previousPeerCount: Int = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("State: \(viewModel.stateText)")
                .font(.headline)
                .accessibilityIdentifier("proof.state")

            Text(peersSummaryText)
                .accessibilityIdentifier("proof.peersSummary")

            Text(viewModel.lifecycleStatusText)
                .font(.subheadline)
                .accessibilityIdentifier("proof.lifecycleStatus")

            if !viewModel.peers.isEmpty {
                Button(peerDetailsVisible ? "Hide peer IDs" : "Show peer IDs") {
                    peerDetailsVisible.toggle()
                }
                .accessibilityIdentifier("proof.togglePeerDetails")

                if peerDetailsVisible {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Peer IDs:")
                        ForEach(viewModel.peers, id: \.value) { peer in
                            Text("- \(peer.value.suffix(6))")
                                .font(.footnote)
                        }
                    }
                    .accessibilityIdentifier("proof.peerDetails")
                }
            }

            HStack {
                Button(viewModel.isRunning ? "Stop Proof" : "Start Proof") {
                    if viewModel.isRunning {
                        viewModel.stop()
                    } else {
                        viewModel.start()
                    }
                }
                .accessibilityIdentifier("proof.startStop")

                Button("Send Hello") {
                    viewModel.sendHello()
                }
                .disabled(viewModel.peers.isEmpty)
                .accessibilityIdentifier("proof.sendHello")
            }

            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(Array(viewModel.logs.enumerated()), id: \.offset) { _, line in
                        Text(line)
                            .font(Font.system(.footnote, design: .monospaced))
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            Text(viewModel.logText)
                .font(.system(size: 1))
                .opacity(0.01)
                .frame(maxWidth: .infinity, maxHeight: 1, alignment: .leading)
                .accessibilityIdentifier("proof.logsAggregate")
        }
        .padding()
        // Mirrors the Android proof app's auto-expand/auto-collapse behavior
        // (MainActivity.renderSnapshot's shouldAutoExpandPeers/shouldCollapsePeers): peer details
        // auto-expand the moment the peer list transitions from empty to non-empty (unless the
        // user already hid them), and auto-collapse once every peer is lost, without overriding a
        // manual toggle otherwise. Uses the single-parameter onChange(of:perform:) overload (not
        // the iOS 17+ two-parameter one) to keep this app's iOS 14.0+ deployment target working.
        .onChange(of: viewModel.peers.count) { newCount in
            if newCount > 0 && previousPeerCount == 0 && !peerDetailsVisible {
                peerDetailsVisible = true
            } else if newCount == 0 && previousPeerCount > 0 && peerDetailsVisible {
                peerDetailsVisible = false
            }
            previousPeerCount = newCount
        }
    }

    private var peersSummaryText: String {
        "Peers: \(viewModel.peers.count)"
    }
}
