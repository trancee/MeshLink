import SwiftUI
import CodecBench

// Runs the codec benchmark once per launch on a background thread and writes Documents/codec-results.txt.
@main
struct CodecBenchApp: App {
    @State private var status = "running…"

    var body: some Scene {
        WindowGroup {
            Text(status).padding().onAppear(perform: start)
        }
    }

    private func start() {
        let env = ProcessInfo.processInfo.environment
        let fuzz = Int32(env["FUZZ"] ?? "") ?? 100_000
        let batches = Int32(env["BATCHES"] ?? "") ?? 15
        DispatchQueue.global(qos: .userInitiated).async {
            let device = UIDevice.current
            var text = "# device=\(device.model) \(device.systemName) \(device.systemVersion) fuzz=\(fuzz) batches=\(batches)\n"
            text += CodecBenchEntry.shared.run(fuzzIterations: fuzz, batches: batches)
            let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            try? text.write(to: docs.appendingPathComponent("codec-results.txt"), atomically: true, encoding: .utf8)
            DispatchQueue.main.async { status = "done" }
        }
    }
}
