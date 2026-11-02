import Darwin
import Foundation

func uptimeNanos() -> UInt64 { clock_gettime_nsec_np(CLOCK_UPTIME_RAW) }

/// `phys_footprint` from `TASK_VM_INFO`, the number Xcode's memory gauge and jetsam use.
func physFootprint() -> UInt64 {
    var info = task_vm_info_data_t()
    var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
    let result = withUnsafeMutablePointer(to: &info) {
        $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
            task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
        }
    }
    return result == KERN_SUCCESS ? info.phys_footprint : 0
}

func machineIdentifier() -> String {
    var system = utsname()
    uname(&system)
    return withUnsafeBytes(of: &system.machine) { String(decoding: $0.prefix(while: { $0 != 0 }), as: UTF8.self) }
}

/// Order statistics over nanosecond samples; p95 is nearest rank, as in the Kotlin side.
struct Stats {
    let count: Int
    let median: UInt64
    let p95: UInt64
    let min: UInt64
    let max: UInt64

    init(_ samples: [UInt64]) {
        let sorted = samples.sorted()
        count = sorted.count
        median = count % 2 == 1 ? sorted[count / 2] : (sorted[count / 2 - 1] + sorted[count / 2]) / 2
        p95 = sorted[Swift.min(Swift.max((95 * count + 99) / 100 - 1, 0), count - 1)]
        min = sorted.first!
        max = sorted.last!
    }

    func line(_ name: String) -> String {
        let padded = name.padding(toLength: 52, withPad: " ", startingAt: 0)
        return "\(padded) n=\(count) median=\(micros(median)) us  p95=\(micros(p95)) us  min=\(micros(min)) us  max=\(micros(max)) us"
    }
}

func micros(_ nanos: UInt64) -> String {
    let text = String(format: "%.2f", Double(nanos) / 1000)
    return String(repeating: " ", count: Swift.max(0, 10 - text.count)) + text
}

func megabytes(_ bytes: UInt64) -> String { String(format: "%.1f MB", Double(bytes) / 1_048_576) }

/// Appends lines to `Documents/results/<run>.txt`, rewriting the file after every line so partial runs survive.
final class Report {
    private let url: URL
    private var text = ""
    private let lock = NSLock()

    init(run: String) {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let directory = documents.appendingPathComponent("results", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        url = directory.appendingPathComponent("\(run).txt")
    }

    func add(_ line: String) {
        lock.lock()
        defer { lock.unlock() }
        text += line + "\n"
        try? text.write(to: url, atomically: true, encoding: .utf8)
    }
}
