import MeshLinkKit
import SwiftUI

// Launch arguments (idevicedebug run <bundle> k=v ...): mode=crypto|interop|all sections=i1,i2,i3 run=<id> warmup=<n> iterations=<n>.
// Results go to Documents/results/<run>.txt; the last line is "DONE" or "FAILED: ...".
@main
struct SE2016BenchApp: App {
    @State private var status = "running…"

    var body: some Scene {
        WindowGroup {
            Text(status).padding().onAppear(perform: start)
        }
    }

    private func start() {
        var arguments = [String: String]()
        for argument in CommandLine.arguments.dropFirst() {
            let parts = argument.split(separator: "=", maxSplits: 1).map(String.init)
            if parts.count == 2 { arguments[parts[0]] = parts[1] }
        }
        let mode = arguments["mode"] ?? "all"
        let warmup = Int(arguments["warmup"] ?? "") ?? 30
        let iterations = Int(arguments["iterations"] ?? "") ?? 300
        let sections = Set((arguments["sections"] ?? "i1,i2,i3").split(separator: ",").map(String.init))
        let report = Report(run: arguments["run"] ?? "bench-\(mode)")
        let device = UIDevice.current
        report.add("# device=\(machineIdentifier()) \(device.systemName) \(device.systemVersion) mode=\(mode) warmup=\(warmup) iterations=\(iterations) started=\(Date())")
        UIApplication.shared.isIdleTimerDisabled = true
        Task.detached(priority: .userInitiated) {
            do {
                if mode == "crypto" || mode == "all" {
                    let text = CryptoBenchmarks.shared.run(warmup: Int32(warmup), iterations: Int32(iterations))
                    for line in text.split(separator: "\n") { report.add(String(line)) }
                }
                if mode == "interop" || mode == "all" {
                    try await InteropBench(report: report, warmup: warmup, iterations: iterations, sections: sections).run()
                }
                report.add("# finished=\(Date())")
                report.add("DONE")
            } catch {
                report.add("FAILED: \(error)")
            }
            await MainActor.run { status = "done" }
        }
    }
}
