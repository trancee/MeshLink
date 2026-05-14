@preconcurrency import CoreBluetooth
import Foundation

@MainActor
final class ProofGattNotifyBenchmarkServer: NSObject {
    private let appId: String
    private let logger: (String) -> Void
    private let stateDidChange: (String) -> Void

    private var peripheralManager: CBPeripheralManager?
    private var notifyCharacteristic: CBMutableCharacteristic?
    private var ackCharacteristic: CBMutableCharacteristic?
    private var subscribedCentral: CBCentral?
    private var pendingFrames: [Data] = []
    private var transferStartTask: Task<Void, Never>?
    private var ackTimeoutTask: Task<Void, Never>?
    private var attemptStartedAtNanos: UInt64 = 0
    private var transferStartedAtNanos: UInt64 = 0
    private var totalBytes: Int = 0
    private var currentTokenHex: String = ""
    private var hasStartedTransfer: Bool = false
    private var finished: Bool = false
    private var setupMs: UInt64 = 0

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
            logger("gatt.notify.start() failed: benchmark payload size must be positive")
            stateDidChange("Error(GATT notify benchmark)")
            return
        }
        resetTransport(updateStateToStopped: false)
        totalBytes = payloadBytes
        currentTokenHex = Self.nextBenchmarkTokenHex(seed: payloadBytes)
        attemptStartedAtNanos = DispatchTime.now().uptimeNanoseconds
        transferStartedAtNanos = 0
        setupMs = 0
        hasStartedTransfer = false
        finished = false
        stateDidChange("Starting(GATT notify benchmark)")
        logger("GATT notify benchmark booting appId=\(appId) bytes=\(payloadBytes)")
        peripheralManager = CBPeripheralManager(delegate: self, queue: nil)
    }

    func stop() {
        finished = true
        resetTransport(updateStateToStopped: true)
    }

    private func resetTransport(updateStateToStopped: Bool) {
        transferStartTask?.cancel()
        transferStartTask = nil
        ackTimeoutTask?.cancel()
        ackTimeoutTask = nil
        peripheralManager?.stopAdvertising()
        peripheralManager?.removeAllServices()
        peripheralManager?.delegate = nil
        peripheralManager = nil
        notifyCharacteristic = nil
        ackCharacteristic = nil
        subscribedCentral = nil
        pendingFrames.removeAll(keepingCapacity: false)
        hasStartedTransfer = false
        if updateStateToStopped {
            stateDidChange("Stopped")
        }
    }

    private func installService() {
        guard let peripheralManager else {
            return
        }
        let appHashCharacteristic = CBMutableCharacteristic(
            type: ProofGattNotifyBenchmarkProtocol.appHashCharacteristicUUID,
            properties: [.read],
            value: ProofGattNotifyBenchmarkProtocol.appHashData(appId: appId),
            permissions: [.readable]
        )
        let notifyCharacteristic = CBMutableCharacteristic(
            type: ProofGattNotifyBenchmarkProtocol.notifyCharacteristicUUID,
            properties: [.notify],
            value: nil,
            permissions: []
        )
        let ackCharacteristic = CBMutableCharacteristic(
            type: ProofGattNotifyBenchmarkProtocol.ackCharacteristicUUID,
            properties: [.write],
            value: nil,
            permissions: [.writeable]
        )
        let service = CBMutableService(type: ProofGattNotifyBenchmarkProtocol.serviceUUID, primary: true)
        service.characteristics = [appHashCharacteristic, notifyCharacteristic, ackCharacteristic]
        self.notifyCharacteristic = notifyCharacteristic
        self.ackCharacteristic = ackCharacteristic
        peripheralManager.add(service)
    }

    private func startAdvertisingIfReady() {
        guard let peripheralManager else {
            return
        }
        peripheralManager.stopAdvertising()
        peripheralManager.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [ProofGattNotifyBenchmarkProtocol.serviceUUID],
        ])
        stateDidChange("Advertising(GATT notify benchmark)")
    }

    private func startTransferIfReady() {
        guard
            !finished,
            !hasStartedTransfer,
            let central = subscribedCentral,
            let notifyCharacteristic
        else {
            return
        }
        hasStartedTransfer = true
        logger("GATT notify benchmark requesting low connection latency for \(central.identifier.uuidString)")
        peripheralManager?.setDesiredConnectionLatency(.low, for: central)
        transferStartTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 300_000_000)
            guard let self, !self.finished else {
                return
            }
            self.setupMs = self.elapsedMilliseconds(since: self.attemptStartedAtNanos)
            self.transferStartedAtNanos = DispatchTime.now().uptimeNanoseconds
            let payload = Self.buildBenchmarkPayload(totalBytes: self.totalBytes, tokenHex: self.currentTokenHex)
            let chunkBytes = ProofGattNotifyBenchmarkProtocol.chunkPayloadBytes(
                maxUpdateValueLength: central.maximumUpdateValueLength
            )
            self.logger(
                "GATT notify benchmark link ready setupMs=\(self.setupMs) maxUpdateValueLength=\(central.maximumUpdateValueLength) chunkPayloadBytes=\(chunkBytes) startDelayMs=300"
            )
            self.pendingFrames = [
                ProofGattNotifyBenchmarkProtocol.encodeStart(tokenHex: self.currentTokenHex, totalBytes: payload.count),
            ]
            for offset in stride(from: 0, to: payload.count, by: chunkBytes) {
                let end = min(offset + chunkBytes, payload.count)
                self.pendingFrames.append(
                    ProofGattNotifyBenchmarkProtocol.encodeData(
                        tokenHex: self.currentTokenHex,
                        chunk: payload.subdata(in: offset..<end)
                    )
                )
            }
            self.stateDidChange("Transferring(GATT notify benchmark)")
            self.ackTimeoutTask = Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: 20_000_000_000)
                self?.finishIfNeeded(reason: "RECEIPT_TIMEOUT")
            }
            self.pumpFrames(notifyCharacteristic: notifyCharacteristic, central: central)
        }
    }

    private func pumpFrames(notifyCharacteristic: CBMutableCharacteristic, central: CBCentral) {
        guard let peripheralManager, !finished else {
            return
        }
        while let next = pendingFrames.first {
            let didSend = peripheralManager.updateValue(next, for: notifyCharacteristic, onSubscribedCentrals: [central])
            if !didSend {
                return
            }
            pendingFrames.removeFirst()
        }
    }

    private func finishIfNeeded(reason: String?) {
        guard !finished else {
            return
        }
        finished = true
        ackTimeoutTask?.cancel()
        ackTimeoutTask = nil
        let elapsedStart = transferStartedAtNanos == 0 ? attemptStartedAtNanos : transferStartedAtNanos
        let elapsedMs = elapsedMilliseconds(since: elapsedStart)
        let result = reason.map { "NotSent(reason=\($0))" } ?? "Sent"
        let throughput = reason == nil ? formatThroughputKilobytesPerSecond(bytes: totalBytes, elapsedMs: elapsedMs) : "0.00"
        logger(
            "BENCHMARK transport bytes=\(totalBytes) elapsedMs=\(elapsedMs) throughputKBps=\(throughput) result=\(result) mode=gattNotifyPrototype setupMs=\(setupMs)"
        )
        stateDidChange(reason == nil ? "Completed(GATT notify benchmark)" : "Error(GATT notify benchmark)")
        resetTransport(updateStateToStopped: false)
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
extension ProofGattNotifyBenchmarkServer: @preconcurrency CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        guard !finished else {
            return
        }
        switch peripheral.state {
        case .poweredOn:
            logger("GATT notify benchmark peripheral poweredOn; adding service")
            installService()
        case .unauthorized:
            finishIfNeeded(reason: "BLUETOOTH_UNAUTHORIZED")
        case .unsupported:
            finishIfNeeded(reason: "BLUETOOTH_UNSUPPORTED")
        case .poweredOff:
            finishIfNeeded(reason: "BLUETOOTH_POWERED_OFF")
        default:
            break
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error {
            logger("GATT notify benchmark addService failed: \(error.localizedDescription)")
            finishIfNeeded(reason: "ADD_SERVICE_FAILED")
            return
        }
        logger("GATT notify benchmark service added; starting advertising")
        startAdvertisingIfReady()
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error {
            logger("GATT notify benchmark advertising failed: \(error.localizedDescription)")
            finishIfNeeded(reason: "ADVERTISE_FAILED")
            return
        }
        logger("GATT notify benchmark advertising started service=\(ProofGattNotifyBenchmarkProtocol.serviceUUID.uuidString)")
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        guard characteristic.uuid == ProofGattNotifyBenchmarkProtocol.notifyCharacteristicUUID else {
            return
        }
        subscribedCentral = central
        logger("GATT notify benchmark central subscribed id=\(central.identifier.uuidString) maxUpdateValueLength=\(central.maximumUpdateValueLength)")
        startTransferIfReady()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        guard characteristic.uuid == ProofGattNotifyBenchmarkProtocol.notifyCharacteristicUUID else {
            return
        }
        logger("GATT notify benchmark central unsubscribed id=\(central.identifier.uuidString)")
        if !finished {
            finishIfNeeded(reason: "CENTRAL_UNSUBSCRIBED")
        }
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        guard let central = subscribedCentral, let notifyCharacteristic else {
            return
        }
        pumpFrames(notifyCharacteristic: notifyCharacteristic, central: central)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        guard let request = requests.first else {
            return
        }
        guard request.characteristic.uuid == ProofGattNotifyBenchmarkProtocol.ackCharacteristicUUID,
              let value = request.value,
              let ack = ProofGattNotifyBenchmarkProtocol.decodeAck(value),
              ack.tokenHex == currentTokenHex,
              ack.totalBytes == totalBytes
        else {
            peripheral.respond(to: request, withResult: .unlikelyError)
            return
        }
        logger("BENCHMARK receipt from gattNotify token=\(ack.tokenHex) bytes=\(ack.totalBytes)")
        peripheral.respond(to: request, withResult: .success)
        finishIfNeeded(reason: nil)
    }
}
