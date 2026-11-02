import Foundation
import MeshLinkKit

/// I1, I2 and I3 against the fake echo mesh. Every line goes to the report as soon as it exists.
final class InteropBench {
    private let report: Report
    private let warmup: Int
    private let iterations: Int
    private let sections: Set<String>

    init(report: Report, warmup: Int, iterations: Int, sections: Set<String>) {
        self.report = report
        self.warmup = warmup
        self.iterations = iterations
        self.sections = sections
    }

    func run() async throws {
        let mesh = MeshLink(outboundCapacity: 64, inboxCapacity: 64)
        try await mesh.start()
        report.add("footprint at start: \(megabytes(physFootprint()))")
        if sections.contains("i1") {
            for overlay in Overlay.allCases {
                for size in [64, 1024, 16384] { conversion(overlay, size: size) }
            }
            for overlay in Overlay.allCases {
                for size in [64, 1024, 16384] { try await roundTrip(mesh, overlay: overlay, size: size, preConverted: false) }
            }
            for size in [64, 1024, 16384] { try await roundTrip(mesh, overlay: .bulk, size: size, preConverted: true) }
        }
        if sections.contains("i2") {
            try await throughput(mesh, overlay: .bulk, count: 40_000, size: 1024)
            try await throughput(mesh, overlay: .perByte, count: 4_000, size: 1024)
        }
        if sections.contains("i2w") {
            try await throughput(mesh, overlay: .bulk, count: 40_000, size: 1024, window: 32)
        }
        if sections.contains("i3") { try await cancellation(mesh) }
        try await mesh.stop()
        mesh.close()
    }

    private func payload(_ size: Int) -> Data { Data((0..<size).map { UInt8(truncatingIfNeeded: $0 &* 31 &+ 7) }) }

    private func conversion(_ overlay: Overlay, size: Int) {
        let data = payload(size)
        let bytes = overlay.toKotlin(data)
        precondition(overlay.toData(bytes) == data, "conversion mismatch")
        var toKotlin = [UInt64]()
        var toData = [UInt64]()
        for index in 0..<(warmup + iterations) {
            let start = uptimeNanos()
            let converted = overlay.toKotlin(data)
            let middle = uptimeNanos()
            let back = overlay.toData(bytes)
            let end = uptimeNanos()
            precondition(converted.size == Int32(size) && back.count == size)
            if index >= warmup {
                toKotlin.append(middle - start)
                toData.append(end - middle)
            }
        }
        report.add(Stats(toKotlin).line("I1 conv \(overlay.rawValue) Data->KotlinByteArray \(size)B"))
        report.add(Stats(toData).line("I1 conv \(overlay.rawValue) KotlinByteArray->Data \(size)B"))
    }

    private func roundTrip(_ mesh: MeshLink, overlay: Overlay, size: Int, preConverted: Bool) async throws {
        try await waitUntil { mesh.probes.activeCollectorCount == 0 }
        let data = payload(size)
        let array = overlay.toKotlin(data)
        let options = SendOptions(directOnly: false, deliveryReceipt: false)
        var iterator = mesh.messages.makeAsyncIterator()
        var samples = [UInt64]()
        for index in 0..<(warmup + iterations) {
            let start = uptimeNanos()
            let result = preConverted
                ? mesh.send(to: mesh.echoPeer, payload: array, options: options)
                : overlay.send(mesh, to: mesh.echoPeer, data: data)
            guard result is SendResult.Accepted else { throw BenchError.rejected("\(result)") }
            guard let message = await iterator.next() else { throw BenchError.flowEnded }
            if preConverted {
                precondition(message.payload.size == Int32(size))
            } else {
                let received = overlay.data(of: message)
                if index == 0 { precondition(received == data, "echo mismatch") }
            }
            let end = uptimeNanos()
            if index >= warmup { samples.append(end - start) }
        }
        let label = preConverted ? "pre-converted (no Data conversion)" : overlay.rawValue
        report.add(Stats(samples).line("I1 rtt \(label) \(size)B"))
    }

    /// `window == nil`: the pre-registered sender (yield and retry on QueueFull). `window`: diagnostic sender that keeps
    /// at most `window` messages in flight, woken by the collector, so it never hits QueueFull.
    private func throughput(_ mesh: MeshLink, overlay: Overlay, count: Int, size: Int, window: Int? = nil) async throws {
        let gate = Gate(permits: window ?? Int.max)
        let name = window.map { "\(overlay.rawValue) window=\($0)" } ?? overlay.rawValue
        try await waitUntil { mesh.probes.activeCollectorCount == 0 }
        let data = payload(size)
        let progress = Progress()
        let before = physFootprint()
        let start = uptimeNanos()
        let collector = Task.detached { () -> (UInt64, [UInt64]) in
            var samples = [UInt64]()
            for await message in mesh.messages {
                let bytes = overlay.data(of: message)
                precondition(bytes.count == size)
                let received = progress.received()
                gate.release()
                if received % 2_000 == 0 { samples.append(physFootprint()) }
                if received == count { break }
            }
            return (uptimeNanos(), samples)
        }
        let report = self.report
        let watchdog = Task.detached {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                if Task.isCancelled { break }
                report.add("I2 progress \(name): \(progress.text) collectors=\(mesh.probes.activeCollectorCount) footprint=\(megabytes(physFootprint()))")
            }
        }
        defer { watchdog.cancel() }
        try await waitUntil { mesh.probes.activeCollectorCount == 1 }
        for _ in 0..<count {
            await gate.acquire()
            while overlay.send(mesh, to: mesh.echoPeer, data: data) is SendResult.Rejected {
                progress.retried()
                await Task.yield()
            }
            progress.sent()
        }
        let (end, samples) = await collector.value
        let seconds = Double(end - start) / 1e9
        let quarter = samples[Swift.max(0, samples.count / 4 - 1)]
        let last = samples.last ?? 0
        report.add(String(format: "I2 %@ %d x %dB: %.3f s, %.0f msg/s, QueueFull retries=%d", name, count, size, seconds, Double(count) / seconds, progress.retries))
        report.add("I2 \(name) footprint: before=\(megabytes(before)) at25%=\(megabytes(quarter)) at100%=\(megabytes(last)) peak=\(megabytes(samples.max() ?? 0)) growth25to100=\(String(format: "%.2f MB", (Double(last) - Double(quarter)) / 1_048_576))")
        report.add("I2 \(name) footprint samples (MB, every 2000): " + samples.map { String(format: "%.1f", Double($0) / 1_048_576) }.joined(separator: " "))
        let lastGcHeap = BenchRuntime.shared.heapBytesAfterLastGc()
        BenchRuntime.shared.collectGarbage()
        report.add("I2 \(name) after run: Kotlin heap after last automatic GC=\(megabytes(UInt64(max(0, lastGcHeap)))) footprint before forced GC=\(megabytes(last)) after forced GC=\(megabytes(physFootprint())) Kotlin heap after forced GC=\(megabytes(UInt64(max(0, BenchRuntime.shared.heapBytesAfterLastGc()))))")
    }

    private func cancellation(_ mesh: MeshLink) async throws {
        let probes = mesh.probes
        var collectorLatencies = [UInt64]()
        var collectorCancelled = 0
        for _ in 0..<100 {
            try await waitUntil { probes.activeCollectorCount == 0 }
            let cancelledBefore = probes.collectorCancellationCount
            let task = Task.detached { for await _ in mesh.messages {} }
            try await waitUntil { probes.activeCollectorCount == 1 }
            let cancelAt = uptimeNanos()
            task.cancel()
            if (try? await waitUntil(timeoutSeconds: 2) { probes.collectorCancellationCount > cancelledBefore }) != nil {
                collectorCancelled += 1
                collectorLatencies.append(UInt64(probes.lastCollectorCancelUptimeNanos) &- cancelAt)
            }
            _ = await task.value
        }
        report.add("I3a collector Task cancelled on Kotlin side: \(collectorCancelled)/100")
        if !collectorLatencies.isEmpty { report.add(Stats(collectorLatencies).line("I3a cancel -> Kotlin flow cancellation")) }

        var suspendLatencies = [UInt64]()
        var suspendCancelled = 0
        for _ in 0..<100 {
            try await waitUntil { probes.activeSuspendCallCount == 0 }
            let cancelledBefore = probes.suspendCancellationCount
            let task = Task.detached { try await mesh.awaitForever() }
            try await waitUntil { probes.activeSuspendCallCount == 1 }
            let cancelAt = uptimeNanos()
            task.cancel()
            if (try? await waitUntil(timeoutSeconds: 2) { probes.suspendCancellationCount > cancelledBefore }) != nil {
                suspendCancelled += 1
                suspendLatencies.append(UInt64(probes.lastSuspendCancelUptimeNanos) &- cancelAt)
            }
            let outcome = await task.result
            if case .success = outcome { report.add("I3b unexpected: awaitForever returned normally") }
        }
        report.add("I3b suspend-call Task cancelled on Kotlin side: \(suspendCancelled)/100")
        if !suspendLatencies.isEmpty { report.add(Stats(suspendLatencies).line("I3b cancel -> Kotlin coroutine cancellation")) }

        try? await waitUntil(timeoutSeconds: 2) { probes.activeCollectorCount == 0 && probes.activeSuspendCallCount == 0 }
        report.add("I3 leaks after trials: activeCollectors=\(probes.activeCollectorCount) activeSuspendCalls=\(probes.activeSuspendCallCount) liveNodeJobs=\(mesh.liveJobs) (1 = echo loop) rejectedCollectors=\(probes.rejectedCollectorCount)")
        let fresh = Task.detached { () -> Bool in
            for await message in mesh.messages { return message.payload.size == 64 }
            return false
        }
        try await waitUntil { probes.activeCollectorCount == 1 }
        _ = Overlay.bulk.send(mesh, to: mesh.echoPeer, data: payload(64))
        report.add("I3 new collector after trials receives: \(await fresh.value)")
    }

    private func waitUntil(timeoutSeconds: Double = 5, _ condition: () -> Bool) async throws {
        let deadline = uptimeNanos() + UInt64(timeoutSeconds * 1e9)
        while !condition() {
            if uptimeNanos() > deadline { throw BenchError.timeout }
            try await Task.sleep(nanoseconds: 100_000)
        }
    }
}

/// Counters shared between the I2 sender, collector and watchdog.
final class Progress {
    private let lock = NSLock()
    private var sentCount = 0
    private var receivedCount = 0
    private var retryCount = 0

    func sent() { lock.lock(); sentCount += 1; lock.unlock() }
    func retried() { lock.lock(); retryCount += 1; lock.unlock() }
    func received() -> Int { lock.lock(); defer { lock.unlock() }; receivedCount += 1; return receivedCount }
    var retries: Int { lock.lock(); defer { lock.unlock() }; return retryCount }
    var text: String { lock.lock(); defer { lock.unlock() }; return "sent=\(sentCount) received=\(receivedCount) retries=\(retryCount)" }
}

/// Counting semaphore for async code: `acquire` suspends while no permit is left.
final class Gate {
    private let lock = NSLock()
    private var permits: Int
    private var waiters = [CheckedContinuation<Void, Never>]()

    init(permits: Int) { self.permits = permits }

    func acquire() async {
        lock.lock()
        if permits > 0 {
            permits -= 1
            lock.unlock()
            return
        }
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            waiters.append(continuation)
            lock.unlock()
        }
    }

    func release() {
        lock.lock()
        if waiters.isEmpty {
            if permits < Int.max { permits += 1 }
            lock.unlock()
        } else {
            let waiter = waiters.removeFirst()
            lock.unlock()
            waiter.resume()
        }
    }
}

enum BenchError: Error {
    case rejected(String)
    case flowEnded
    case timeout
}
