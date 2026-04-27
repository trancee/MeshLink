import SwiftUI

/// Main screen for the MeshLink iOS sample app.
///
/// Shows Start / Stop controls and a scrolling log of engine events so both devices
/// can be observed without an attached debugger during the S04 manual hardware test.
struct ContentView: View {
    @EnvironmentObject var bridge: MeshEngineBridge

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                controlBar
                Divider()
                logView
            }
            .navigationTitle("MeshLink Sample")
            .navigationBarTitleDisplayMode(.inline)
        }
        .onAppear { bridge.startEngine() }
        .onDisappear { bridge.stopEngine() }
    }

    // ── Control bar ────────────────────────────────────────────────────────────

    @ViewBuilder
    private var controlBar: some View {
        HStack(spacing: 16) {
            Button(action: { bridge.startEngine() }) {
                Label("Start", systemImage: "play.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(.green)
            .disabled(bridge.isRunning)

            Button(action: { bridge.stopEngine() }) {
                Label("Stop", systemImage: "stop.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(.red)
            .disabled(!bridge.isRunning)
        }
        .padding()

        // Status badge
        HStack {
            Circle()
                .fill(bridge.isRunning ? Color.green : Color.gray)
                .frame(width: 10, height: 10)
            Text(bridge.isRunning ? "Running" : "Stopped")
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .padding(.bottom, 8)
    }

    // ── Log view ───────────────────────────────────────────────────────────────

    private var logView: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 2) {
                    ForEach(Array(bridge.logLines.enumerated()), id: \.offset) { idx, line in
                        Text(line)
                            .font(.system(.caption, design: .monospaced))
                            .foregroundColor(lineColor(line))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 8)
                            .id(idx)
                    }
                }
            }
            .onChange(of: bridge.logLines.count) { _ in
                if let last = bridge.logLines.indices.last {
                    withAnimation { proxy.scrollTo(last, anchor: .bottom) }
                }
            }
        }
        .background(Color(.systemBackground))
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private func lineColor(_ line: String) -> Color {
        if line.contains("❌") { return .red }
        if line.contains("✅") { return .green }
        if line.contains("🔵") { return .blue }
        if line.contains("🔴") { return Color(red: 0.8, green: 0.1, blue: 0.1) }
        if line.contains("📤") || line.contains("📨") { return .purple }
        return .primary
    }
}

#Preview {
    ContentView()
        .environmentObject(MeshEngineBridge())
}
