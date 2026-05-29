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

    private let runtime: MeshLink.MeshLinkRuntime
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
    private var autoSendTasks: [String: Task<Void, Never>] = [:]
    private var flowTasks: [Task<Void, Never>] = []
    private var peerBytesByValue: [String: [UInt8]] = [:]
    private var pendingBenchmarkReceipts: [String: PendingBenchmarkReceipt] = [:]
    private var benchmarkTokenCounter: UInt64 = 0
    private let transportLogCapture = ProofTransportLogCapture()

    init() {
        launchConfig = ProofLaunchConfig.fromEnvironment(ProcessInfo.processInfo.environment)
        logFileUrl =
            FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("proof.log")
        try? FileManager.default.removeItem(at: logFileUrl)
        let resolvedLaunchConfig = launchConfig
        let config = meshLinkConfig { builder in
            builder.appId = resolvedLaunchConfig.appId
            builder.powerMode = resolvedLaunchConfig.powerMode
        }
        runtime = meshLink(config: config)
        if resolvedLaunchConfig.benchmarkTransport == .meshLink {
            transportLogCapture.startIfNeeded(
                launchConfig: resolvedLaunchConfig,
                appendLog: { [weak self] message in
                    self?.appendLog(message)
                }
            )
            bindFlows()
        }
        appendLog(
            "MeshLink proof app ready on iPhone appId=\(launchConfig.appId) powerMode=\(launchConfig.powerModeLabel) transport=\(launchConfig.benchmarkTransport.logLabel)"
        )
        start()
    }

    func start() {
        if ProofBenchmarkModeController.startIfNeeded(
            launchConfig: launchConfig,
            gattBenchmarkClient: gattBenchmarkClient,
            gattNotifyBenchmarkServer: gattNotifyBenchmarkServer,
            setState: { [weak self] state in
                self?.stateText = state
            },
            appendLog: { [weak self] message in
                self?.appendLog(message)
            }
        ) {
            return
        }

        let startedAtNanos = DispatchTime.now().uptimeNanoseconds
        Task { @MainActor [weak self] in
            guard let self else {
                return
            }
            do {
                let result = try await self.runtime.start()
                self.appendLog("mesh.start() -> \(result)")
                if self.launchConfig.benchmarkColdStart {
                    self.appendLog(
                        "BENCHMARK coldStart elapsedMs=\(self.elapsedMilliseconds(since: startedAtNanos)) result=\(result)"
                    )
                }
                self.applyBenchmarkPowerSnapshot()
            } catch {
                self.appendLog("mesh.start() failed: \(error.localizedDescription)")
                if self.launchConfig.benchmarkColdStart {
                    self.appendLog(
                        "BENCHMARK coldStart failed=\(error.localizedDescription)"
                    )
                }
            }
        }
    }

    func stop() {
        if ProofBenchmarkModeController.stopIfNeeded(
            launchConfig: launchConfig,
            gattBenchmarkClient: gattBenchmarkClient,
            gattNotifyBenchmarkServer: gattNotifyBenchmarkServer,
            appendLog: { [weak self] message in
                self?.appendLog(message)
            }
        ) {
            return
        }
        Task { @MainActor [weak self] in
            guard let self else {
                return
            }
            do {
                let result = try await self.runtime.stop()
                self.appendLog("mesh.stop() -> \(result)")
            } catch {
                self.appendLog("mesh.stop() failed: \(error.localizedDescription)")
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
        guard flowTasks.isEmpty else {
            return
        }
        let currentRuntime = runtime
        flowTasks = [
            Task { @MainActor [weak self] in
                for await state in currentRuntime.state {
                    self?.stateText = String(describing: state)
                }
            },
            Task { @MainActor [weak self] in
                for await event in currentRuntime.peerEvents {
                    self?.handlePeerEvent(event)
                }
            },
            Task { @MainActor [weak self] in
                for await diagnostic in currentRuntime.diagnosticEvents {
                    self?.handleDiagnosticEvent(diagnostic)
                }
            },
            Task { @MainActor [weak self] in
                for await message in currentRuntime.messages {
                    self?.handleInboundMessage(message)
                }
            },
        ]
    }

    private func handlePeerEvent(_ event: PeerEvent) {
        switch onEnum(of: event) {
        case .found(let found):
            peers = insertOrReplace(found.peerId, into: peers)
            cachePeerBytes(found.peerId)
            appendLog("Peer found: \(found.peerId.value)")
            scheduleAutoSend(for: found.peerId)
        case .lost(let lost):
            peers.removeAll { peer in peer.value == lost.peerId.value }
            peerBytesByValue.removeValue(forKey: lost.peerId.value)
            autoSendTasks.removeValue(forKey: lost.peerId.value)?.cancel()
            appendLog("Peer lost: \(lost.peerId.value)")
        case .stateChanged(let changed):
            appendLog("Peer state changed: \(changed.peerId.value) -> \(changed.state)")
        }
    }

    private func handleDiagnosticEvent(_ diagnostic: DiagnosticEvent) {
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
        cachePeerBytes(recoveredPeer)
        scheduleAutoSend(for: recoveredPeer)
    }

    private func handleInboundMessage(_ message: InboundMessage) {
        let payloadData = message.payload.toData()

        if let receipt = BenchmarkReceiptEnvelope.decode(payloadData) {
            if let pendingReceipt = pendingBenchmarkReceipts.removeValue(forKey: receipt.tokenHex) {
                pendingReceipt.resolve(receipt)
                Task { @MainActor [weak self] in
                    self?.appendLog(
                        "BENCHMARK receipt from \(message.originPeerId.value) token=\(receipt.tokenHex) bytes=\(receipt.totalBytes)"
                    )
                    self?.appendBenchmarkCorrelation(
                        role: "sender.receipt.arrived",
                        tokenHex: receipt.tokenHex,
                        peerValue: message.originPeerId.value,
                        outcome: "received"
                    )
                }
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
            Task { @MainActor in
                let receiptPayload = BenchmarkReceiptEnvelope(tokenHex: benchmarkPayload.tokenHex, totalBytes: payloadData.count)
                let result = await sendPayload(
                    to: receiptPeerId,
                    payload: receiptPayload.encode().toKotlinByteArray(),
                    priority: DeliveryPriority.high
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
        guard let originPeerBytes = originPeerId.value.toBytesOrNil() else {
            return peers.count == 1 ? peers.first ?? originPeerId : originPeerId
        }
        if let prefixMatch = peers.first(where: { peer in
            guard let peerBytes = peerBytesByValue[peer.value] else {
                return false
            }
            return originPeerBytes.starts(with: peerBytes)
        }) {
            return prefixMatch
        }
        if peers.count == 1, let onlyPeer = peers.first {
            return onlyPeer
        }
        return originPeerId
    }

    private func scheduleAutoSend(for peerId: PeerId) {
        guard autoSendTasks[peerId.value] == nil else {
            return
        }
        if launchConfig.disableAutoSend {
            appendLog(
                "auto-send skipped for \(peerId.value.suffix(6)) because passive benchmark mode is enabled"
            )
            return
        }
        let task = Task { [weak self] in
            defer {
                Task { @MainActor [weak self] in
                    self?.autoSendTasks.removeValue(forKey: peerId.value)
                }
            }
            guard let self else {
                return
            }
            if launchConfig.benchmarkPayloadBytes != nil {
                _ = await sendPayload(
                    to: peerId,
                    payload: "benchmark warmup".toKotlinByteArray(),
                    priority: DeliveryPriority.normal,
                    benchmarkWarmup: true
                )
                try? await Task.sleep(nanoseconds: benchmarkWarmupSettlementDelayNanos)
            }
            for attempt in 0..<6 {
                try? await Task.sleep(nanoseconds: benchmarkAttemptDelayNanos)
                guard !Task.isCancelled else {
                    return
                }
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
                    Task<BenchmarkReceiptEnvelope?, Never> { @MainActor [weak self] in
                        guard let self else {
                            return nil
                        }
                        return await self.awaitBenchmarkReceipt(
                            tokenHex: envelope.tokenHex,
                            timeoutNanos: self.benchmarkReceiptTimeoutNanos
                        )
                    }
                }
                let result = await sendPayload(to: peerId, payload: payload, priority: DeliveryPriority.normal)
                if let result {
                    appendLog(
                        "auto-send attempt \(attempt + 1) -> \(result) for \(peerId.value.suffix(6))"
                    )
                    let resultDescription = String(describing: result)
                    let wasSent: Bool
                    switch onEnum(of: result) {
                    case .sent:
                        wasSent = true
                    case .notSent:
                        wasSent = false
                    }
                    var receiptConfirmed = true
                    if let benchmarkPayload {
                        let receipt: BenchmarkReceiptEnvelope?
                        if wasSent {
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
                        let benchmarkResult = receiptConfirmed ? resultDescription : (wasSent ? "ReceiptTimeout" : resultDescription)
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
                    if wasSent && receiptConfirmed {
                        return
                    }
                }
            }
        }
        autoSendTasks[peerId.value] = task
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
        runtime.updateBattery(snapshot: BatterySnapshot(level: batteryLevel, isCharging: isCharging))
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

        do {
            let result = try await runtime.send(peerId: peerId, payload: payload, priority: priority)
            if let logPrefix {
                appendLog("\(logPrefix)(\(peerSuffix)) -> \(result)")
            }
            if benchmarkWarmup {
                appendLog("BENCHMARK transport warmup=\(result)")
            }
            return result
        } catch {
            appendLog("mesh.send() failed: \(error.localizedDescription)")
            return nil
        }
    }

    private var benchmarkReceiptTimeoutNanos: UInt64 {
        20_000_000_000
    }

    private var benchmarkWarmupSettlementDelayNanos: UInt64 {
        500_000_000
    }

    private var benchmarkAttemptDelayNanos: UInt64 {
        2_000_000_000
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

    private func cachePeerBytes(_ peerId: PeerId) {
        peerBytesByValue[peerId.value] = peerId.value.toBytesOrNil()
    }
}

private extension String {
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
    func toBytes() -> [UInt8] {
        toBytesOrNil() ?? []
    }

    func toBytesOrNil() -> [UInt8]? {
        guard count.isMultiple(of: 2) else {
            return nil
        }
        var output: [UInt8] = []
        output.reserveCapacity(count / 2)
        var index = startIndex
        while index < endIndex {
            let next = self.index(index, offsetBy: 2)
            guard let value = UInt8(self[index..<next], radix: 16) else {
                return nil
            }
            output.append(value)
            index = next
        }
        return output
    }
}
