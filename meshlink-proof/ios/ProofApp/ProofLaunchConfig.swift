import Foundation
import MeshLink

struct ProofLaunchConfig {
    let appId: String
    let powerMode: PowerMode
    let powerModeLabel: String
    let benchmarkPayloadBytes: Int?
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

private extension String {
    var nonEmpty: String? {
        isEmpty ? nil : self
    }
}
