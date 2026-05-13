import XCTest

final class PowerProfileBenchmark: XCTestCase {
    func testLowBatteryPowerSaverModeClampsScanDutyCycle() {
        // Arrange
        let application = BenchmarkTestSupport.launchProofApp(
            appId: "demo.meshlink.benchmark.power",
            powerMode: "powersaver",
            benchmarkBatteryLevel: 0.5,
            benchmarkIsCharging: false
        )

        // Act
        let logLine = BenchmarkTestSupport.waitForLogLine(
            in: application,
            containing: "DIAG POWER_MODE_CHANGED",
            timeout: 10
        )

        // Assert
        XCTAssertTrue(
            logLine.contains("tier=POWER_SAVER"),
            "Expected POWER_MODE_CHANGED to report the POWER_SAVER tier"
        )
        XCTAssertTrue(
            logLine.contains("scanDutyCyclePercent=5"),
            "Expected LOW-power scan duty cycle to stay at or below 5 percent"
        )
    }

    func testLowBatteryPowerSaverModeDelivers256ByteMessageWithinFiveSeconds() throws {
        try requirePeerBenchmarksEnabled()

        // Arrange
        let application = BenchmarkTestSupport.launchProofApp(
            appId: "demo.meshlink.benchmark.power.delivery",
            powerMode: "powersaver",
            benchmarkPayloadBytes: 256,
            benchmarkBatteryLevel: 0.5,
            benchmarkIsCharging: false
        )

        // Act
        let logLine = BenchmarkTestSupport.waitForLogLine(
            in: application,
            containing: "BENCHMARK transport bytes=",
            timeout: lowPowerDeliveryResultTimeoutSeconds
        )
        let elapsedMilliseconds = BenchmarkTestSupport.extractElapsedMilliseconds(from: logLine)
        let result = BenchmarkTestSupport.extractResult(from: logLine)

        // Assert
        XCTAssertEqual(
            result,
            "Sent",
            "LOW-power delivery benchmarks require a nearby proof peer running appId=demo.meshlink.benchmark.power.delivery"
        )
        XCTAssertLessThanOrEqual(
            elapsedMilliseconds,
            5000,
            "Expected LOW-power 256-byte delivery <= 5000 ms, but observed \(elapsedMilliseconds) ms"
        )
    }

    private var lowPowerDeliveryResultTimeoutSeconds: TimeInterval {
        60
    }

    private func requirePeerBenchmarksEnabled() throws {
        guard ProcessInfo.processInfo.environment["MESHLINK_BENCHMARK_ENABLE_PEER_TESTS"] == "true" else {
            throw XCTSkip(
                "Requires a nearby proof peer and MESHLINK_BENCHMARK_ENABLE_PEER_TESTS=true"
            )
        }
    }
}
