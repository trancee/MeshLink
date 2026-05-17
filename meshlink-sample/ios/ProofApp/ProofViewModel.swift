import Darwin
import Foundation
import MeshLink
import SwiftUI

@MainActor
final class ProofViewModel: ObservableObject {
    @Published private(set) var stateText: String = "Uninitialized"
    @Published private(set) var peers: [PeerId] = []
    @Published private(set) var logs: [String] = []

    var logText: String {
        logs.joined(separator: "\n")
    }

    private let api: MeshLinkApi
    private let logFileUrl: URL
    private let launchConfig: ProofLaunchConfig
    private lazy var gattBenchmarkClient: ProofGattBenchmarkClient = ProofGattBenchmarkClient(
        appId: launchConfig.appId,
        logger: { [weak self] message in
            self?.appendLog(message)
        },
        stateDidChange: { [weak self] state in
            self?.stateText = state
        }
    )
    private lazy var gattNotifyBenchmarkServer: ProofGattNotifyBenchmarkServer = ProofGattNotifyBenchmarkServer(
        appId: launchConfig.appId,
        logger: { [weak self] message in
            self?.appendLog(message)
        },
        stateDidChange: { [weak self] state in
            self?.stateText = state
        }
    )
    private var autoSendPeers: Set<String> = []
    private var pendingBenchmarkReceipts: [String: PendingBenchmarkReceipt] = [:]
    private var benchmarkTokenCounter: UInt64 = 0
    private var capturedStdoutBuffer: String = ""
    private var stdoutPipe: Pipe?
    private var stdoutOriginalDescriptor: Int32 = -1
    private lazy var stateCollector: FlowCollector = FlowCollector { [weak self] value in
        self?.stateText = String(describing: value ?? "Unknown")
    }
    private lazy var peerCollector: FlowCollector = FlowCollector { [weak self] value in
        self?.handlePeerEvent(value)
    }
    private lazy var diagnosticCollector: FlowCollector = FlowCollector { [weak self] value in
        self?.handleDiagnosticEvent(value)
    }
    private lazy var messageCollector: FlowCollector = FlowCollector { [weak self] value in
        self?.handleInboundMessage(value)
    }

    init() {
        launchConfig = ProofLaunchConfig.fromEnvironment(ProcessInfo.processInfo.environment)
        logFileUrl =
            FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("proof.log")
        try? FileManager.default.removeItem(at: logFileUrl)
        let resolvedLaunchConfig = launchConfig
        let config = MeshLinkConfigKt.meshLinkConfig { builder in
            builder.appId = resolvedLaunchConfig.appId
            builder.regulatoryRegion = RegulatoryRegion.default_
            builder.powerMode = resolvedLaunchConfig.powerMode
        }
        api = MeshLink.shared.create(config: config)
        if resolvedLaunchConfig.benchmarkTransport == .meshLink {
            startTransportLogCaptureIfNeeded()
            bindFlows()
        }
        appendLog(
            "MeshLink proof app ready on iPhone appId=\(launchConfig.appId) powerMode=\(launchConfig.powerModeLabel) transport=\(launchConfig.benchmarkTransport.logLabel)"
        )
        start()
    }

    func start() {
        if launchConfig.benchmarkTransport == .gattPrototype {
            guard let benchmarkPayloadBytes = launchConfig.benchmarkPayloadBytes else {
                stateText = "Error(GATT benchmark)"
                appendLog("gatt.benchmark.start() failed: MESHLINK_BENCHMARK_PAYLOAD_BYTES is required for GATT benchmark mode")
                return
            }
            if launchConfig.disableAutoSend {
                stateText = "Error(GATT benchmark)"
                appendLog("gatt.benchmark.start() failed: passive iOS GATT benchmark mode is not implemented")
                return
            }
            gattBenchmarkClient.start(payloadBytes: benchmarkPayloadBytes)
            return
        }
        if launchConfig.benchmarkTransport == .gattNotifyPrototype {
            guard let benchmarkPayloadBytes = launchConfig.benchmarkPayloadBytes else {
                stateText = "Error(GATT notify benchmark)"
                appendLog("gatt.notify.start() failed: MESHLINK_BENCHMARK_PAYLOAD_BYTES is required for GATT notify benchmark mode")
                return
            }
            if launchConfig.disableAutoSend {
                stateText = "Error(GATT notify benchmark)"
                appendLog("gatt.notify.start() failed: passive iOS GATT notify benchmark mode is not implemented")
                return
            }
            gattNotifyBenchmarkServer.start(payloadBytes: benchmarkPayloadBytes)
            return
        }

        let startedAtNanos = DispatchTime.now().uptimeNanoseconds
        api.start { [weak self] result, error in
            Task { @MainActor in
                guard let self else {
                    return
                }
                if let result {
                    self.appendLog("mesh.start() -> \(result)")
                    if self.launchConfig.benchmarkColdStart {
                        self.appendLog(
                            "BENCHMARK coldStart elapsedMs=\(self.elapsedMilliseconds(since: startedAtNanos)) result=\(result)"
                        )
                    }
                    self.applyBenchmarkPowerSnapshot()
                }
                if let error {
                    self.appendLog("mesh.start() failed: \(error.localizedDescription)")
                    if self.launchConfig.benchmarkColdStart {
                        self.appendLog(
                            "BENCHMARK coldStart failed=\(error.localizedDescription)"
                        )
                    }
                }
            }
        }
    }

    func stop() {
        if launchConfig.benchmarkTransport == .gattPrototype {
            gattBenchmarkClient.stop()
            appendLog("gatt.benchmark.stop() -> Stopped")
            return
        }
        if launchConfig.benchmarkTransport == .gattNotifyPrototype {
            gattNotifyBenchmarkServer.stop()
            appendLog("gatt.notify.stop() -> Stopped")
            return
        }
        api.stop { [weak self] result, error in
            Task { @MainActor in
                if let result {
                    self?.appendLog("mesh.stop() -> \(result)")
                }
                if let error {
                    self?.appendLog("mesh.stop() failed: \(error.localizedDescription)")
                }
            }
        }
    }

    func sendHello() {
        if launchConfig.benchmarkTransport != .meshLink {
            appendLog("Send Hello is unavailable in benchmark-only transport mode")
            return
        }
        guard let peer = peers.first else {
            appendLog("No discovered peer is available yet")
            return
        }
        Task {
            _ = await sendPayload(
                to: peer,
                payload: "hello mesh from iPhone".toKotlinByteArray(),
                priority: DeliveryPriority.normal,
                logPrefix: "mesh.send"
            )
        }
    }

    private func bindFlows() {
        guard launchConfig.benchmarkTransport == .meshLink else {
            return
        }
        stateText = String(describing: api.state.value ?? "Unknown")
        api.state.collect(collector: stateCollector) { [weak self] error in
            Task { @MainActor in
                if let error {
                    self?.appendLog("state flow ended: \(error.localizedDescription)")
                }
            }
        }
        api.peerEvents.collect(collector: peerCollector) { [weak self] error in
            Task { @MainActor in
                if let error {
                    self?.appendLog("peer flow ended: \(error.localizedDescription)")
                }
            }
        }
        api.diagnosticEvents.collect(collector: diagnosticCollector) { [weak self] error in
            Task { @MainActor in
                if let error {
                    self?.appendLog("diagnostic flow ended: \(error.localizedDescription)")
                }
            }
        }
        api.messages.collect(collector: messageCollector) { [weak self] error in
            Task { @MainActor in
                if let error {
                    self?.appendLog("message flow ended: \(error.localizedDescription)")
                }
            }
        }
    }

    private func handlePeerEvent(_ value: Any?) {
        if let found = value as? PeerEvent.Found {
            peers = insertOrReplace(found.peerId, into: peers)
            appendLog("Peer found: \(found.peerId.value)")
            scheduleAutoSend(for: found.peerId)
        } else if let lost = value as? PeerEvent.Lost {
            peers.removeAll { peer in peer.value == lost.peerId.value }
            autoSendPeers.remove(lost.peerId.value)
            appendLog("Peer lost: \(lost.peerId.value)")
        } else if let changed = value as? PeerEvent.StateChanged {
            appendLog("Peer state changed: \(changed.peerId.value) -> \(changed.state)")
        }
    }

    private func handleDiagnosticEvent(_ value: Any?) {
        guard let diagnostic = value as? DiagnosticEvent else {
            return
        }
        let reasonText = diagnostic.reason.map { String(describing: $0) } ?? "nil"
        let metadataSuffix: String
        if diagnostic.metadata.isEmpty {
            metadataSuffix = ""
        } else {
            let entries = diagnostic.metadata.keys.sorted().map { key in
                "\(key)=\(diagnostic.metadata[key] ?? "")"
            }
            metadataSuffix = " metadata={\(entries.joined(separator: ", "))}"
        }
        appendLog(
            "DIAG \(diagnostic.code) stage=\(diagnostic.stage) reason=\(reasonText)\(metadataSuffix)"
        )
        recoverPeerFromRouteDiagnosticIfNeeded(diagnostic)
    }

    private func recoverPeerFromRouteDiagnosticIfNeeded(_ diagnostic: DiagnosticEvent) {
        guard launchConfig.benchmarkTransport == .meshLink else {
            return
        }
        guard diagnostic.code == .routeDiscovered else {
            return
        }
        guard diagnostic.metadata["routeIsDirect"] == "true" else {
            return
        }
        let recoveredPeerValue =
            diagnostic.metadata["peerId"] ??
            diagnostic.metadata["destinationPeerId"] ??
            diagnostic.metadata["connectedPeerId"]
        guard let recoveredPeerValue else {
            return
        }
        let recoveredPeer = PeerId(value: recoveredPeerValue)
        peers = insertOrReplace(recoveredPeer, into: peers)
        scheduleAutoSend(for: recoveredPeer)
    }

    private func handleInboundMessage(_ value: Any?) {
        guard let message = value as? InboundMessage else {
            return
        }
        let payloadData = message.payload.toData()

        if let receipt = BenchmarkReceiptEnvelope.decode(payloadData) {
            if let pendingReceipt = pendingBenchmarkReceipts.removeValue(forKey: receipt.tokenHex) {
                appendLog(
                    "BENCHMARK receipt from \(message.originPeerId.value) token=\(receipt.tokenHex) bytes=\(receipt.totalBytes)"
                )
                appendBenchmarkCorrelation(
                    role: "sender.receipt.arrived",
                    tokenHex: receipt.tokenHex,
                    peerValue: message.originPeerId.value,
                    outcome: "received"
                )
                pendingReceipt.resolve(receipt)
                return
            }
        }

        if let benchmarkPayload = BenchmarkPayloadEnvelope.decode(payloadData) {
            appendLog(
                "MSG from \(message.originPeerId.value) bytes=\(payloadData.count) benchmarkToken=\(benchmarkPayload.tokenHex)"
            )
            let receiptPeerId = resolveBenchmarkReceiptPeerId(for: message.originPeerId)
            if receiptPeerId.value != message.originPeerId.value {
                appendLog(
                    "BENCHMARK receipt peer remapped origin=\(message.originPeerId.value.suffix(6)) direct=\(receiptPeerId.value.suffix(6))"
                )
            }
            appendBenchmarkCorrelation(
                role: "passive.receipt.start",
                tokenHex: benchmarkPayload.tokenHex,
                peerValue: receiptPeerId.value,
                outcome: "receivedPayload"
            )
            Task { @MainActor in
                let receiptPayload = BenchmarkReceiptEnvelope(tokenHex: benchmarkPayload.tokenHex, totalBytes: payloadData.count)
                let result = await sendPayload(
                    to: receiptPeerId,
                    payload: receiptPayload.encode().toKotlinByteArray(),
                    priority: DeliveryPriority.normal
                )
                appendBenchmarkCorrelation(
                    role: "passive.receipt.result",
                    tokenHex: benchmarkPayload.tokenHex,
                    peerValue: receiptPeerId.value,
                    outcome: result.map { String(describing: $0) } ?? "nil"
                )
            }
            return
        }

        appendLog(
            "MSG from \(message.originPeerId.value) bytes=\(message.payload.size) text=\(message.payload.toDataString())"
        )
    }

    private func resolveBenchmarkReceiptPeerId(for originPeerId: PeerId) -> PeerId {
        if peers.contains(where: { $0.value == originPeerId.value }) {
            return originPeerId
        }
        if let prefixMatch = peers.first(where: { originPeerId.value.hasPrefix($0.value) }) {
            return prefixMatch
        }
        if peers.count == 1, let onlyPeer = peers.first {
            return onlyPeer
        }
        return originPeerId
    }

    private func scheduleAutoSend(for peerId: PeerId) {
        guard autoSendPeers.insert(peerId.value).inserted else {
            return
        }
        if launchConfig.disableAutoSend {
            appendLog(
                "auto-send skipped for \(peerId.value.suffix(6)) because passive benchmark mode is enabled"
            )
            return
        }
        Task {
            if launchConfig.benchmarkPayloadBytes != nil {
                _ = await sendPayload(
                    to: peerId,
                    payload: "benchmark warmup".toKotlinByteArray(),
                    priority: DeliveryPriority.normal,
                    benchmarkWarmup: true
                )
                try? await Task.sleep(nanoseconds: 500_000_000)
            }
            for attempt in 0..<6 {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                guard peers.contains(where: { current in current.value == peerId.value }) else {
                    return
                }
                let benchmarkPayload = launchConfig.benchmarkPayloadBytes.map(buildBenchmarkPayload)
                if let benchmarkPayload {
                    appendBenchmarkCorrelation(
                        role: "sender.benchmark.send",
                        tokenHex: benchmarkPayload.tokenHex,
                        peerValue: peerId.value,
                        outcome: "attempt\(attempt + 1)"
                    )
                }
                let payload = benchmarkPayload?.encode().toKotlinByteArray() ?? buildHelloPayload()
                let startedAtNanos = DispatchTime.now().uptimeNanoseconds
                let receiptTask = benchmarkPayload.map { envelope in
                    Task { @MainActor in
                        await awaitBenchmarkReceipt(tokenHex: envelope.tokenHex, timeoutNanos: benchmarkReceiptTimeoutNanos)
                    }
                }
                let result = await sendPayload(to: peerId, payload: payload, priority: DeliveryPriority.normal)
                if let result {
                    appendLog(
                        "auto-send attempt \(attempt + 1) -> \(result) for \(peerId.value.suffix(6))"
                    )
                    var receiptConfirmed = true
                    if let benchmarkPayload {
                        let receipt: BenchmarkReceiptEnvelope?
                        if String(describing: result) == "Sent" {
                            receipt = await receiptTask?.value
                        } else {
                            pendingBenchmarkReceipts.removeValue(forKey: benchmarkPayload.tokenHex)?.resolve(nil)
                            receipt = nil
                        }
                        if receipt == nil {
                            pendingBenchmarkReceipts.removeValue(forKey: benchmarkPayload.tokenHex)?.resolve(nil)
                        }
                        receiptConfirmed = receipt != nil
                        let elapsedMs = elapsedMilliseconds(since: startedAtNanos)
                        let benchmarkResult = receiptConfirmed ? String(describing: result) : (String(describing: result) == "Sent" ? "ReceiptTimeout" : String(describing: result))
                        appendLog(
                            "BENCHMARK transport bytes=\(payload.size) elapsedMs=\(elapsedMs) throughputKBps=\(formatThroughputKilobytesPerSecond(bytes: Int(payload.size), elapsedMs: elapsedMs)) result=\(benchmarkResult)"
                        )
                        appendBenchmarkCorrelation(
                            role: "sender.benchmark.result",
                            tokenHex: benchmarkPayload.tokenHex,
                            peerValue: peerId.value,
                            outcome: benchmarkResult
                        )
                    }
                    if String(describing: result) == "Sent" && receiptConfirmed {
                        return
                    }
                }
            }
        }
    }

    private func applyBenchmarkPowerSnapshot() {
        guard launchConfig.benchmarkTransport == .meshLink else {
            return
        }
        guard
            let batteryLevel = launchConfig.benchmarkBatteryLevel,
            let isCharging = launchConfig.benchmarkIsCharging
        else {
            return
        }
        api.updateBattery(level: batteryLevel, isCharging: isCharging)
        appendLog(
            "BENCHMARK power batteryLevel=\(batteryLevel) isCharging=\(isCharging) powerMode=\(launchConfig.powerModeLabel)"
        )
    }

    private func buildBenchmarkPayload(totalBytes: Int) -> BenchmarkPayloadEnvelope {
        benchmarkTokenCounter += 1
        let tokenValue = DispatchTime.now().uptimeNanoseconds ^ benchmarkTokenCounter
        let tokenHex = String(format: "%016llx", tokenValue)
        return BenchmarkPayloadEnvelope(totalBytes: totalBytes, tokenHex: tokenHex)
    }

    private func buildHelloPayload() -> KotlinByteArray {
        "hello mesh from iPhone".toKotlinByteArray()
    }

    private func awaitBenchmarkReceipt(tokenHex: String, timeoutNanos: UInt64) async -> BenchmarkReceiptEnvelope? {
        await withCheckedContinuation { continuation in
            let pendingReceipt = PendingBenchmarkReceipt(continuation: continuation)
            pendingBenchmarkReceipts[tokenHex] = pendingReceipt
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: timeoutNanos)
                if let unresolvedReceipt = pendingBenchmarkReceipts.removeValue(forKey: tokenHex) {
                    unresolvedReceipt.resolve(nil)
                }
            }
        }
    }

    private func sendPayload(
        to peerId: PeerId,
        payload: KotlinByteArray,
        priority: DeliveryPriority,
        logPrefix: String? = nil,
        benchmarkWarmup: Bool = false
    ) async -> SendResult? {
        let peerSuffix = String(peerId.value.suffix(6))

        return await withCheckedContinuation { continuation in
            api.send(peerId: peerId, payload: payload, priority: priority) { [weak self] result, error in
                Task { @MainActor in
                    if let logPrefix, let result {
                        self?.appendLog("\(logPrefix)(\(peerSuffix)) -> \(result)")
                    }
                    if benchmarkWarmup, let result {
                        self?.appendLog("BENCHMARK transport warmup=\(result)")
                    }
                    if let error {
                        self?.appendLog("mesh.send() failed: \(error.localizedDescription)")
                    }
                    continuation.resume(returning: result)
                }
            }
        }
    }

    private var benchmarkReceiptTimeoutNanos: UInt64 {
        20_000_000_000
    }

    private func elapsedMilliseconds(since startedAtNanos: UInt64) -> UInt64 {
        (DispatchTime.now().uptimeNanoseconds - startedAtNanos) / 1_000_000
    }

    private func formatThroughputKilobytesPerSecond(bytes: Int, elapsedMs: UInt64) -> String {
        guard elapsedMs > 0 else {
            return "0.00"
        }
        let kibPerSecond = (Double(bytes) / 1024.0) / (Double(elapsedMs) / 1000.0)
        return String(format: "%.2f", locale: Locale(identifier: "en_US_POSIX"), kibPerSecond)
    }

    private func startTransportLogCaptureIfNeeded() {
        guard launchConfig.benchmarkTransport == .meshLink,
              launchConfig.transportTelemetryEnabled,
              stdoutOriginalDescriptor == -1 else {
            return
        }
        let pipe = Pipe()
        fflush(stdout)
        stdoutOriginalDescriptor = dup(STDOUT_FILENO)
        dup2(pipe.fileHandleForWriting.fileDescriptor, STDOUT_FILENO)
        stdoutPipe = pipe
        pipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
            let data = handle.availableData
            guard !data.isEmpty else {
                return
            }
            let output = String(decoding: data, as: UTF8.self)
            Task { @MainActor in
                self?.ingestTransportLogOutput(output)
            }
        }
    }

    private func stopTransportLogCapture() {
        guard stdoutOriginalDescriptor != -1 else {
            return
        }
        fflush(stdout)
        dup2(stdoutOriginalDescriptor, STDOUT_FILENO)
        close(stdoutOriginalDescriptor)
        stdoutOriginalDescriptor = -1
        stdoutPipe?.fileHandleForReading.readabilityHandler = nil
        stdoutPipe = nil
    }

    private func ingestTransportLogOutput(_ output: String) {
        capturedStdoutBuffer.append(output)
        while let newlineRange = capturedStdoutBuffer.range(of: "\n") {
            let line = String(capturedStdoutBuffer[..<newlineRange.lowerBound])
            capturedStdoutBuffer.removeSubrange(capturedStdoutBuffer.startIndex...newlineRange.lowerBound)
            if line.contains("MeshLinkTransport") {
                appendLog(line)
            }
        }
    }

    private func appendLog(_ message: String) {
        logs.append(message)
        if logs.count > 256 {
            logs.removeFirst(logs.count - 256)
        }
        if let lineData = (message + "\n").data(using: .utf8) {
            FileHandle.standardError.write(lineData)
        }
        let persisted = logs.joined(separator: "\n") + "\n"
        try? persisted.write(to: logFileUrl, atomically: true, encoding: .utf8)
    }

    private func appendBenchmarkCorrelation(
        role: String,
        tokenHex: String,
        peerValue: String,
        outcome: String
    ) {
        let knownPeers = peers.map(\.value).map { String($0.suffix(6)) }.sorted().joined(separator: ",")
        let recentPeers = recentLogSummary(limit: 4) { line in
            line.hasPrefix("Peer ")
        }
        let recentDiagnostics = recentLogSummary(limit: 4) { line in
            line.hasPrefix("DIAG ")
        }
        let recentRouteTimeline = peerTimelineLogSummary(peerValue: peerValue, limit: 4)
        let lastTransition = peerTimelineEntries(peerValue: peerValue, limit: 1).last ?? "none"
        let routeState = peerRouteState(peerValue: peerValue)
        appendLog(
            "BENCHMARK correlation role=\(role) token=\(tokenHex) peer=\(String(peerValue.suffix(6))) outcome=\(outcome) state=\(stateText) knownPeers=[\(knownPeers)]"
        )
        appendLog("BENCHMARK correlation token=\(tokenHex) recentPeers=\(recentPeers)")
        appendLog("BENCHMARK correlation token=\(tokenHex) recentDiags=\(recentDiagnostics)")
        appendLog("BENCHMARK correlation token=\(tokenHex) routeState=\(routeState) lastTransition=\(lastTransition)")
        appendLog("BENCHMARK correlation token=\(tokenHex) routeTimeline=\(recentRouteTimeline)")
    }

    private func peerTimelineLogSummary(peerValue: String, limit: Int) -> String {
        let selected = peerTimelineEntries(peerValue: peerValue, limit: limit)
        return "[\(selected.joined(separator: " | "))]"
    }

    private func peerTimelineEntries(peerValue: String, limit: Int) -> [String] {
        logs.filter { line in
            isPeerTimelineLine(line, peerValue: peerValue)
        }.suffix(limit).map(summarizeLogLine)
    }

    private func isPeerTimelineLine(_ line: String, peerValue: String) -> Bool {
        (line.hasPrefix("Peer ") && line.contains(peerValue)) ||
            (line.hasPrefix("DIAG ") && line.contains("peerId=\(peerValue)"))
    }

    private func peerRouteState(peerValue: String) -> String {
        guard let lastRouteDiagnostic = logs.last(where: { line in
            line.hasPrefix("DIAG ") &&
                line.contains("peerId=\(peerValue)") &&
                line.contains("routeAvailable=")
        }) else {
            return "unknown"
        }
        if lastRouteDiagnostic.contains("routeAvailable=true") {
            return "available"
        }
        if lastRouteDiagnostic.contains("routeAvailable=false") {
            return "unavailable"
        }
        return "unknown"
    }

    private func recentLogSummary(limit: Int, predicate: (String) -> Bool) -> String {
        let selected = logs.filter(predicate).suffix(limit).map(summarizeLogLine)
        return "[\(selected.joined(separator: " | "))]"
    }

    private func summarizeLogLine(_ line: String) -> String {
        let singleLine = line.replacingOccurrences(of: "\n", with: " ").trimmingCharacters(in: .whitespacesAndNewlines)
        if singleLine.count > 96 {
            return String(singleLine.prefix(96)) + "…"
        }
        return singleLine
    }

    private func insertOrReplace(_ peerId: PeerId, into existing: [PeerId]) -> [PeerId] {
        var copy = existing.filter { current in current.value != peerId.value }
        copy.append(peerId)
        return copy
    }
}

private final class PendingBenchmarkReceipt {
    private var continuation: CheckedContinuation<BenchmarkReceiptEnvelope?, Never>?
    private var resolved = false

    init(continuation: CheckedContinuation<BenchmarkReceiptEnvelope?, Never>) {
        self.continuation = continuation
    }

    func resolve(_ receipt: BenchmarkReceiptEnvelope?) {
        guard !resolved, let continuation else {
            return
        }
        resolved = true
        self.continuation = nil
        continuation.resume(returning: receipt)
    }
}

private struct BenchmarkPayloadEnvelope {
    static let magic = Array("MLBM1000".utf8)
    static let headerBytes = 16

    let totalBytes: Int
    let tokenHex: String

    init(totalBytes: Int, tokenHex: String) {
        precondition(totalBytes >= Self.headerBytes, "Benchmark payload must be at least \(Self.headerBytes) bytes")
        precondition(tokenHex.count == 16, "Benchmark token must be 16 hex characters")
        self.totalBytes = totalBytes
        self.tokenHex = tokenHex
    }

    func encode() -> Data {
        var bytes = [UInt8](unsafeUninitializedCapacity: totalBytes) { buffer, count in
            for index in 0..<totalBytes {
                buffer[index] = UInt8((index * 31) & 0xFF)
            }
            count = totalBytes
        }
        Self.magic.enumerated().forEach { index, byte in
            bytes[index] = byte
        }
        tokenHex.hexDecodedBytes().enumerated().forEach { index, byte in
            bytes[Self.magic.count + index] = byte
        }
        return Data(bytes)
    }

    static func decode(_ data: Data) -> BenchmarkPayloadEnvelope? {
        guard data.count >= Self.headerBytes else {
            return nil
        }
        let bytes = [UInt8](data)
        guard Array(bytes.prefix(Self.magic.count)) == Self.magic else {
            return nil
        }
        let tokenBytes = bytes[Self.magic.count..<Self.headerBytes]
        let tokenHex = tokenBytes.map { String(format: "%02x", $0) }.joined()
        return BenchmarkPayloadEnvelope(totalBytes: data.count, tokenHex: tokenHex)
    }
}

private struct BenchmarkReceiptEnvelope {
    static let prefix = "MLBM1_ACK:"

    let tokenHex: String
    let totalBytes: Int

    func encode() -> Data {
        Data("\(Self.prefix)\(tokenHex):\(totalBytes)".utf8)
    }

    static func decode(_ data: Data) -> BenchmarkReceiptEnvelope? {
        guard let text = String(data: data, encoding: .utf8), text.hasPrefix(Self.prefix) else {
            return nil
        }
        let payload = text.dropFirst(Self.prefix.count)
        let parts = payload.split(separator: ":", maxSplits: 1).map(String.init)
        guard parts.count == 2, let totalBytes = Int(parts[1]) else {
            return nil
        }
        return BenchmarkReceiptEnvelope(tokenHex: parts[0], totalBytes: totalBytes)
    }
}

private struct ProofLaunchConfig {
    let appId: String
    let powerMode: PowerMode
    let powerModeLabel: String
    let benchmarkPayloadBytes: Int?
    let benchmarkBatteryLevel: Float?
    let benchmarkIsCharging: Bool?
    let benchmarkColdStart: Bool
    let disableAutoSend: Bool
    let transportTelemetryEnabled: Bool
    let benchmarkTransport: ProofBenchmarkTransport

    static func fromEnvironment(_ environment: [String: String]) -> ProofLaunchConfig {
        ProofLaunchConfig(
            appId: environment["MESHLINK_APP_ID"]?.nonEmpty ?? "demo.meshlink",
            powerMode: parsePowerMode(environment["MESHLINK_POWER_MODE"]),
            powerModeLabel: parsePowerModeLabel(environment["MESHLINK_POWER_MODE"]),
            benchmarkPayloadBytes: environment["MESHLINK_BENCHMARK_PAYLOAD_BYTES"].flatMap(Int.init),
            benchmarkBatteryLevel: environment["MESHLINK_BENCHMARK_BATTERY_LEVEL"].flatMap(Float.init),
            benchmarkIsCharging: parseBoolean(environment["MESHLINK_BENCHMARK_IS_CHARGING"]),
            benchmarkColdStart: parseBoolean(environment["MESHLINK_BENCHMARK_COLD_START"]) ?? false,
            disableAutoSend: parseBoolean(environment["MESHLINK_DISABLE_AUTO_SEND"]) ?? false,
            transportTelemetryEnabled: parseBoolean(environment["MESHLINK_TRANSPORT_TELEMETRY"]) ?? false,
            benchmarkTransport: ProofBenchmarkTransport.parse(environment["MESHLINK_BENCHMARK_TRANSPORT"])
        )
    }

    private static func parsePowerMode(_ rawValue: String?) -> PowerMode {
        switch rawValue?.lowercased() {
        case "performance":
            return PowerMode.Performance.shared
        case "balanced":
            return PowerMode.Balanced.shared
        case "powersaver":
            return PowerMode.PowerSaver.shared
        default:
            return PowerMode.Automatic.shared
        }
    }

    private static func parsePowerModeLabel(_ rawValue: String?) -> String {
        switch rawValue?.lowercased() {
        case "performance":
            return "Performance"
        case "balanced":
            return "Balanced"
        case "powersaver":
            return "PowerSaver"
        default:
            return "Automatic"
        }
    }

    private static func parseBoolean(_ rawValue: String?) -> Bool? {
        switch rawValue?.lowercased() {
        case "1", "true", "yes":
            return true
        case "0", "false", "no":
            return false
        default:
            return nil
        }
    }
}

private final class FlowCollector: NSObject, Kotlinx_coroutines_coreFlowCollector {
    private let onValue: @MainActor (Any?) -> Void

    init(onValue: @escaping @MainActor (Any?) -> Void) {
        self.onValue = onValue
    }

    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        Task { @MainActor in
            onValue(value)
            completionHandler(nil)
        }
    }
}

private extension String {
    var nonEmpty: String? {
        isEmpty ? nil : self
    }

    func toKotlinByteArray() -> KotlinByteArray {
        Data(utf8).toKotlinByteArray()
    }
}

private extension Data {
    func toKotlinByteArray() -> KotlinByteArray {
        let kotlinBytes = KotlinByteArray(size: Int32(count))
        for (index, byte) in enumerated() {
            kotlinBytes.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return kotlinBytes
    }
}

private extension KotlinByteArray {
    func toData() -> Data {
        var bytes = [UInt8](repeating: 0, count: Int(size))
        for index in 0..<Int(size) {
            bytes[index] = UInt8(bitPattern: get(index: Int32(index)))
        }
        return Data(bytes)
    }

    func toDataString() -> String {
        String(decoding: toData(), as: UTF8.self)
    }
}

private extension String {
    func hexDecodedBytes() -> [UInt8] {
        stride(from: 0, to: count, by: 2).compactMap { index in
            let start = self.index(startIndex, offsetBy: index)
            let end = self.index(start, offsetBy: 2)
            return UInt8(self[start..<end], radix: 16)
        }
    }
}
