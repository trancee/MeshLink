import XCTest

enum BenchmarkTestSupport {
    static func launchProofApp(
        appId: String,
        powerMode: String? = nil,
        benchmarkPayloadBytes: Int? = nil,
        benchmarkBatteryLevel: Float? = nil,
        benchmarkIsCharging: Bool? = nil,
        benchmarkColdStart: Bool = false,
        disableAutoSend: Bool = false,
        transportTelemetry: Bool = false,
        benchmarkTransport: String? = nil
    ) -> XCUIApplication {
        let application = XCUIApplication()
        var environment: [String: String] = [
            "MESHLINK_APP_ID": appId,
        ]
        if let powerMode {
            environment["MESHLINK_POWER_MODE"] = powerMode
        }
        if let benchmarkPayloadBytes {
            environment["MESHLINK_BENCHMARK_PAYLOAD_BYTES"] = String(benchmarkPayloadBytes)
        }
        if let benchmarkBatteryLevel {
            environment["MESHLINK_BENCHMARK_BATTERY_LEVEL"] = String(benchmarkBatteryLevel)
        }
        if let benchmarkIsCharging {
            environment["MESHLINK_BENCHMARK_IS_CHARGING"] = benchmarkIsCharging ? "true" : "false"
        }
        if benchmarkColdStart {
            environment["MESHLINK_BENCHMARK_COLD_START"] = "true"
        }
        if disableAutoSend {
            environment["MESHLINK_DISABLE_AUTO_SEND"] = "true"
        }
        if transportTelemetry {
            environment["MESHLINK_TRANSPORT_TELEMETRY"] = "true"
        }
        if let benchmarkTransport {
            environment["MESHLINK_BENCHMARK_TRANSPORT"] = benchmarkTransport
        }
        application.launchEnvironment = environment
        application.launch()
        return application
    }

    static func waitForLogLine(
        in application: XCUIApplication,
        containing text: String,
        timeout: TimeInterval
    ) -> String {
        let logs = application.staticTexts["proof.logsAggregate"]
        XCTAssertTrue(logs.waitForExistence(timeout: 5), "Expected proof.logsAggregate to exist")
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            let value = logs.label
            if let line = value.split(separator: "\n").map(String.init).last(where: { $0.contains(text) }) {
                return line
            }
            RunLoop.current.run(until: Date(timeIntervalSinceNow: 0.2))
        }
        XCTFail("Timed out waiting for log line containing '\(text)'")
        return logs.label
    }

    static func extractElapsedMilliseconds(from logLine: String) -> Int {
        extractInteger(from: logLine, pattern: #"elapsedMs=(\d+)"#)
    }

    static func extractThroughputKilobytesPerSecond(from logLine: String) -> Double {
        extractDouble(from: logLine, pattern: #"throughputKBps=([0-9]+(?:\.[0-9]+)?)"#)
    }

    static func extractResult(from logLine: String) -> String {
        extractString(from: logLine, pattern: #"result=([^ ]+)"#)
    }

    static func waitForTransportTelemetryLine(
        in application: XCUIApplication,
        containing text: String,
        timeout: TimeInterval
    ) -> String {
        waitForLogLine(in: application, containing: "MeshLinkTransportTelemetry \(text)", timeout: timeout)
    }

    static func extractTelemetryInteger(from logLine: String, key: String) -> Int {
        extractInteger(from: logLine, pattern: #"\#(key)=(-?\d+)"#)
    }

    static func extractTelemetryString(from logLine: String, key: String) -> String {
        extractString(from: logLine, pattern: #"\#(key)=([^ ]+)"#)
    }

    private static func extractInteger(from text: String, pattern: String) -> Int {
        guard let match = text.range(of: pattern, options: .regularExpression) else {
            XCTFail("Missing integer pattern \(pattern) in '\(text)'")
            return 0
        }
        let value = String(text[match]).components(separatedBy: "=").last ?? "0"
        return Int(value) ?? 0
    }

    private static func extractDouble(from text: String, pattern: String) -> Double {
        guard let match = text.range(of: pattern, options: .regularExpression) else {
            XCTFail("Missing double pattern \(pattern) in '\(text)'")
            return 0
        }
        let value = String(text[match]).components(separatedBy: "=").last ?? "0"
        return Double(value) ?? 0
    }

    private static func extractString(from text: String, pattern: String) -> String {
        guard let match = text.range(of: pattern, options: .regularExpression) else {
            XCTFail("Missing string pattern \(pattern) in '\(text)'")
            return ""
        }
        let value = String(text[match]).components(separatedBy: "=").last ?? ""
        return value
    }
}
