import SwiftUI

struct ContentView: View {
    @StateObject private var viewModel = ProofViewModel()

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("State: \(viewModel.stateText)")
                .font(.headline)

            if viewModel.peers.isEmpty {
                Text("Peers: none")
            } else {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Peers:")
                    ForEach(viewModel.peers, id: \.value) { peer in
                        Text("- \(peer.value)")
                            .font(.footnote)
                    }
                }
            }

            HStack {
                Button("Start MeshLink") {
                    viewModel.start()
                }
                Button("Stop MeshLink") {
                    viewModel.stop()
                }
                Button("Send Hello") {
                    viewModel.sendHello()
                }
                .disabled(viewModel.peers.isEmpty)
            }

            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(Array(viewModel.logs.enumerated()), id: \.offset) { _, line in
                        Text(line)
                            .font(.footnote.monospaced())
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding()
    }
}
