import Foundation

@MainActor
enum ProofBenchmarkModeController {
    static func startIfNeeded(
        launchConfig: ProofLaunchConfig,
        gattBenchmarkClient: ProofGattBenchmarkClient,
        gattNotifyBenchmarkServer: ProofGattNotifyBenchmarkServer,
        setState: (String) -> Void,
        appendLog: (String) -> Void
    ) -> Bool {
        switch launchConfig.benchmarkTransport {
        case .meshLink:
            return false
        case .gattPrototype:
            guard let benchmarkPayloadBytes = launchConfig.benchmarkPayloadBytes else {
                setState("Error(GATT benchmark)")
                appendLog("gatt.benchmark.start() failed: MESHLINK_BENCHMARK_PAYLOAD_BYTES is required for GATT benchmark mode")
                return true
            }
            if launchConfig.disableAutoSend {
                setState("Error(GATT benchmark)")
                appendLog("gatt.benchmark.start() failed: passive iOS GATT benchmark mode is not implemented")
                return true
            }
            gattBenchmarkClient.start(payloadBytes: benchmarkPayloadBytes)
            return true
        case .gattNotifyPrototype:
            guard let benchmarkPayloadBytes = launchConfig.benchmarkPayloadBytes else {
                setState("Error(GATT notify benchmark)")
                appendLog("gatt.notify.start() failed: MESHLINK_BENCHMARK_PAYLOAD_BYTES is required for GATT notify benchmark mode")
                return true
            }
            if launchConfig.disableAutoSend {
                setState("Error(GATT notify benchmark)")
                appendLog("gatt.notify.start() failed: passive iOS GATT notify benchmark mode is not implemented")
                return true
            }
            gattNotifyBenchmarkServer.start(payloadBytes: benchmarkPayloadBytes)
            return true
        }
    }

    static func stopIfNeeded(
        launchConfig: ProofLaunchConfig,
        gattBenchmarkClient: ProofGattBenchmarkClient,
        gattNotifyBenchmarkServer: ProofGattNotifyBenchmarkServer,
        appendLog: (String) -> Void
    ) -> Bool {
        switch launchConfig.benchmarkTransport {
        case .meshLink:
            return false
        case .gattPrototype:
            gattBenchmarkClient.stop()
            appendLog("gatt.benchmark.stop() -> Stopped")
            return true
        case .gattNotifyPrototype:
            gattNotifyBenchmarkServer.stop()
            appendLog("gatt.notify.stop() -> Stopped")
            return true
        }
    }
}
