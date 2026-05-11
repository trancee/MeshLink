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
}
