@preconcurrency import CoreBluetooth
import Foundation

@MainActor
final class ProofGattBenchmarkClient: NSObject {
    private let appId: String
    private let logger: (String) -> Void
    private let stateDidChange: (String) -> Void

    private var central: CBCentralManager?
    private var peripheral: CBPeripheral?
    private var writeCharacteristic: CBCharacteristic?
    private var ackCharacteristic: CBCharacteristic?
    private var transferTask: Task<Void, Never>?
    private var scanTimeoutTask: Task<Void, Never>?
    private var pendingStartWrite: CheckedContinuation<Void, Error>?
    private var pendingCanSendWithoutResponse: CheckedContinuation<Void, Never>?
    private var pendingAck: PendingGattAck?
    private var attemptStartedAtNanos: UInt64 = 0
    private var transferStartedAtNanos: UInt64 = 0
    private var totalBytes: Int = 0
    private var currentTokenHex: String = ""
    private var hasStartedTransfer: Bool = false
    private var finished: Bool = false

    init(
        appId: String,
        logger: @escaping (String) -> Void,
        stateDidChange: @escaping (String) -> Void
    ) {
        self.appId = appId
        self.logger = logger
        self.stateDidChange = stateDidChange
        super.init()
    }

    func start(payloadBytes: Int) {
        guard payloadBytes > 0 else {
            logger("gatt.benchmark.start() failed: benchmark payload size must be positive")
            stateDidChange("Error(GATT benchmark)")
            return
        }
        stopConnectionOnly()
        totalBytes = payloadBytes
        currentTokenHex = Self.nextBenchmarkTokenHex(seed: payloadBytes)
        attemptStartedAtNanos = DispatchTime.now().uptimeNanoseconds
        transferStartedAtNanos = 0
        hasStartedTransfer = false
        finished = false
        stateDidChange("Scanning(GATT benchmark)")
        logger("GATT benchmark scanning appId=\(appId) bytes=\(payloadBytes)")
        central = CBCentralManager(delegate: self, queue: nil)
        scanTimeoutTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 15_000_000_000)
            self?.finishIfNeeded(reason: "SCAN_TIMEOUT")
        }
    }

    func stop() {
        pendingAck?.resolve(nil)
        pendingAck = nil
        pendingCanSendWithoutResponse?.resume(returning: ())
        pendingCanSendWithoutResponse = nil
        pendingStartWrite?.resume(throwing: CancellationError())
        pendingStartWrite = nil
        transferTask?.cancel()
        transferTask = nil
        stopConnectionOnly()
        finished = true
        stateDidChange("Stopped")
    }

    private func stopConnectionOnly() {
        scanTimeoutTask?.cancel()
        scanTimeoutTask = nil
        transferTask?.cancel()
        transferTask = nil
        central?.stopScan()
        if let peripheral {
            central?.cancelPeripheralConnection(peripheral)
        }
        central = nil
        peripheral = nil
        writeCharacteristic = nil
        ackCharacteristic = nil
    }

    private func startTransferIfReady() {
        guard
            !finished,
            !hasStartedTransfer,
            let peripheral,
            let writeCharacteristic,
            let ackCharacteristic,
            ackCharacteristic.isNotifying
        else {
            return
        }
        hasStartedTransfer = true
        stateDidChange("Transferring(GATT benchmark)")
        transferTask = Task { [weak self] in
            await self?.runTransfer(peripheral: peripheral, writeCharacteristic: writeCharacteristic)
        }
    }

    private func runTransfer(peripheral: CBPeripheral, writeCharacteristic: CBCharacteristic) async {
        do {
            let setupMs = elapsedMilliseconds(since: attemptStartedAtNanos)
            transferStartedAtNanos = DispatchTime.now().uptimeNanoseconds
            let tokenHex = currentTokenHex
            let payload = Self.buildBenchmarkPayload(totalBytes: totalBytes, tokenHex: tokenHex)
            logger(
                "GATT benchmark link ready setupMs=\(setupMs) maxWriteWithoutResponse=\(peripheral.maximumWriteValueLength(for: .withoutResponse))"
            )
            try await writeStartFrame(peripheral: peripheral, characteristic: writeCharacteristic, tokenHex: tokenHex)
            let chunkBytes = ProofGattBenchmarkProtocol.chunkPayloadBytes(
                maxWriteValueLength: peripheral.maximumWriteValueLength(for: .withoutResponse)
            )
            let ackTask = Task { [weak self] in
                await self?.awaitAck(tokenHex: tokenHex, timeoutNanos: 20_000_000_000)
            }
            for offset in stride(from: 0, to: payload.count, by: chunkBytes) {
                let end = min(offset + chunkBytes, payload.count)
                let chunk = payload.subdata(in: offset..<end)
                if !peripheral.canSendWriteWithoutResponse {
                    await awaitReadyToSendWithoutResponse()
                }
                peripheral.writeValue(
                    ProofGattBenchmarkProtocol.encodeData(tokenHex: tokenHex, chunk: chunk),
                    for: writeCharacteristic,
                    type: .withoutResponse
                )
            }
            let ack = await ackTask.value
            if let ack, ack.tokenHex == tokenHex, ack.totalBytes == payload.count {
                logger("BENCHMARK receipt from gatt token=\(ack.tokenHex) bytes=\(ack.totalBytes)")
                finishIfNeeded(reason: nil, setupMs: setupMs)
            } else {
                finishIfNeeded(reason: "RECEIPT_TIMEOUT", setupMs: setupMs)
            }
        } catch {
            logger("GATT benchmark transfer failed: \(error.localizedDescription)")
            finishIfNeeded(reason: "TRANSFER_ERROR")
        }
    }

    private func writeStartFrame(
        peripheral: CBPeripheral,
        characteristic: CBCharacteristic,
        tokenHex: String
    ) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            pendingStartWrite = continuation
            peripheral.writeValue(
                ProofGattBenchmarkProtocol.encodeStart(tokenHex: tokenHex, totalBytes: totalBytes),
                for: characteristic,
                type: .withResponse
            )
        }
    }

    private func awaitReadyToSendWithoutResponse() async {
        guard let peripheral, !peripheral.canSendWriteWithoutResponse else {
            return
        }
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            pendingCanSendWithoutResponse = continuation
        }
    }

    private func awaitAck(tokenHex: String, timeoutNanos: UInt64) async -> ProofGattBenchmarkProtocol.AckFrame? {
        await withCheckedContinuation { continuation in
            let pendingAck = PendingGattAck(tokenHex: tokenHex, continuation: continuation)
            self.pendingAck = pendingAck
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: timeoutNanos)
                if let unresolvedAck = self.pendingAck, unresolvedAck.tokenHex == tokenHex {
                    self.pendingAck = nil
                    unresolvedAck.resolve(nil)
                }
            }
        }
    }

    private func finishIfNeeded(reason: String?, setupMs: UInt64? = nil) {
        guard !finished else {
            return
        }
        finished = true
        let elapsedStart = transferStartedAtNanos == 0 ? attemptStartedAtNanos : transferStartedAtNanos
        let elapsedMs = elapsedMilliseconds(since: elapsedStart)
        let result = reason.map { "NotSent(reason=\($0))" } ?? "Sent"
        let throughput = reason == nil ? formatThroughputKilobytesPerSecond(bytes: totalBytes, elapsedMs: elapsedMs) : "0.00"
        let setupSuffix = setupMs.map { " setupMs=\($0)" } ?? ""
        logger(
            "BENCHMARK transport bytes=\(totalBytes) elapsedMs=\(elapsedMs) throughputKBps=\(throughput) result=\(result) mode=gattPrototype\(setupSuffix)"
        )
        stateDidChange(reason == nil ? "Completed(GATT benchmark)" : "Error(GATT benchmark)")
        stopConnectionOnly()
    }

    private func elapsedMilliseconds(since startedAtNanos: UInt64) -> UInt64 {
        guard startedAtNanos > 0 else {
            return 0
        }
        return (DispatchTime.now().uptimeNanoseconds - startedAtNanos) / 1_000_000
    }

    private func formatThroughputKilobytesPerSecond(bytes: Int, elapsedMs: UInt64) -> String {
        guard elapsedMs > 0 else {
            return "0.00"
        }
        let kibPerSecond = (Double(bytes) / 1024.0) / (Double(elapsedMs) / 1000.0)
        return String(format: "%.2f", locale: Locale(identifier: "en_US_POSIX"), kibPerSecond)
    }

    private static func nextBenchmarkTokenHex(seed: Int) -> String {
        let tokenValue = DispatchTime.now().uptimeNanoseconds ^ UInt64(seed)
        return String(format: "%016llx", tokenValue)
    }

    private static func buildBenchmarkPayload(totalBytes: Int, tokenHex: String) -> Data {
        precondition(totalBytes >= 16, "Benchmark payload must be at least 16 bytes")
        var bytes = [UInt8](unsafeUninitializedCapacity: totalBytes) { buffer, count in
            for index in 0..<totalBytes {
                buffer[index] = UInt8((index * 31) & 0xff)
            }
            count = totalBytes
        }
        let magic = Array("MLBM1000".utf8)
        magic.enumerated().forEach { index, byte in
            bytes[index] = byte
        }
        stride(from: 0, to: tokenHex.count, by: 2).enumerated().forEach { index, offset in
            let start = tokenHex.index(tokenHex.startIndex, offsetBy: offset)
            let end = tokenHex.index(start, offsetBy: 2)
            bytes[magic.count + index] = UInt8(tokenHex[start..<end], radix: 16) ?? 0
        }
        return Data(bytes)
    }
}

@MainActor
extension ProofGattBenchmarkClient: @preconcurrency CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard !finished else {
            return
        }
        switch central.state {
        case .poweredOn:
            logger("GATT benchmark central poweredOn; starting scan")
            central.scanForPeripherals(withServices: [ProofGattBenchmarkProtocol.serviceUUID])
        case .unsupported:
            finishIfNeeded(reason: "BLUETOOTH_UNSUPPORTED")
        case .unauthorized:
            finishIfNeeded(reason: "BLUETOOTH_UNAUTHORIZED")
        case .poweredOff:
            finishIfNeeded(reason: "BLUETOOTH_POWERED_OFF")
        default:
            break
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        guard !finished else {
            return
        }
        let manufacturerData = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data
        guard ProofGattBenchmarkProtocol.matchesAdvertisement(manufacturerData, appId: appId) else {
            return
        }
        logger("GATT benchmark discovered peripheral id=\(peripheral.identifier.uuidString) rssi=\(RSSI)")
        self.peripheral = peripheral
        peripheral.delegate = self
        central.stopScan()
        stateDidChange("Connecting(GATT benchmark)")
        central.connect(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard !finished else {
            return
        }
        logger("GATT benchmark connected peripheral=\(peripheral.identifier.uuidString)")
        stateDidChange("Discovering(GATT benchmark)")
        peripheral.discoverServices([ProofGattBenchmarkProtocol.serviceUUID])
    }

    func centralManager(
        _ central: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: Error?
    ) {
        logger("GATT benchmark connect failed: \(error?.localizedDescription ?? "unknown")")
        finishIfNeeded(reason: "CONNECT_FAILED")
    }

    func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        guard !finished else {
            return
        }
        logger("GATT benchmark disconnected: \(error?.localizedDescription ?? "none")")
        finishIfNeeded(reason: "GATT_DISCONNECTED")
    }
}

@MainActor
extension ProofGattBenchmarkClient: @preconcurrency CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard !finished else {
            return
        }
        if let error {
            logger("GATT benchmark discoverServices failed: \(error.localizedDescription)")
            finishIfNeeded(reason: "SERVICE_DISCOVERY_FAILED")
            return
        }
        guard let service = peripheral.services?.first(where: { $0.uuid == ProofGattBenchmarkProtocol.serviceUUID }) else {
            finishIfNeeded(reason: "SERVICE_MISSING")
            return
        }
        peripheral.discoverCharacteristics(
            [ProofGattBenchmarkProtocol.writeCharacteristicUUID, ProofGattBenchmarkProtocol.ackCharacteristicUUID],
            for: service
        )
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        guard !finished else {
            return
        }
        if let error {
            logger("GATT benchmark discoverCharacteristics failed: \(error.localizedDescription)")
            finishIfNeeded(reason: "CHARACTERISTIC_DISCOVERY_FAILED")
            return
        }
        for characteristic in service.characteristics ?? [] {
            switch characteristic.uuid {
            case ProofGattBenchmarkProtocol.writeCharacteristicUUID:
                writeCharacteristic = characteristic
            case ProofGattBenchmarkProtocol.ackCharacteristicUUID:
                ackCharacteristic = characteristic
            default:
                break
            }
        }
        guard let ackCharacteristic else {
            finishIfNeeded(reason: "ACK_CHARACTERISTIC_MISSING")
            return
        }
        logger("GATT benchmark enabling notifications")
        peripheral.setNotifyValue(true, for: ackCharacteristic)
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateNotificationStateFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard !finished else {
            return
        }
        if let error {
            logger("GATT benchmark notification enable failed: \(error.localizedDescription)")
            finishIfNeeded(reason: "NOTIFY_ENABLE_FAILED")
            return
        }
        if characteristic.uuid == ProofGattBenchmarkProtocol.ackCharacteristicUUID, characteristic.isNotifying {
            logger("GATT benchmark notifications enabled")
            startTransferIfReady()
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didWriteValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard let pendingStartWrite else {
            return
        }
        self.pendingStartWrite = nil
        if let error {
            pendingStartWrite.resume(throwing: error)
        } else {
            pendingStartWrite.resume(returning: ())
        }
    }

    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        pendingCanSendWithoutResponse?.resume(returning: ())
        pendingCanSendWithoutResponse = nil
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard !finished else {
            return
        }
        if let error {
            logger("GATT benchmark notify update failed: \(error.localizedDescription)")
            finishIfNeeded(reason: "ACK_READ_FAILED")
            return
        }
        guard
            characteristic.uuid == ProofGattBenchmarkProtocol.ackCharacteristicUUID,
            let data = characteristic.value,
            let ack = ProofGattBenchmarkProtocol.decodeAck(data)
        else {
            return
        }
        logger("GATT benchmark ack notification token=\(ack.tokenHex) bytes=\(ack.totalBytes)")
        if let pendingAck, pendingAck.tokenHex == ack.tokenHex {
            self.pendingAck = nil
            pendingAck.resolve(ack)
        }
    }
}

private final class PendingGattAck {
    let tokenHex: String
    private var continuation: CheckedContinuation<ProofGattBenchmarkProtocol.AckFrame?, Never>?
    private var resolved = false

    init(
        tokenHex: String,
        continuation: CheckedContinuation<ProofGattBenchmarkProtocol.AckFrame?, Never>
    ) {
        self.tokenHex = tokenHex
        self.continuation = continuation
    }

    func resolve(_ ack: ProofGattBenchmarkProtocol.AckFrame?) {
        guard !resolved, let continuation else {
            return
        }
        resolved = true
        self.continuation = nil
        continuation.resume(returning: ack)
    }
}
