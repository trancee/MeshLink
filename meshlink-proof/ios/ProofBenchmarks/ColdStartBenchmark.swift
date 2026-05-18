import XCTest

final class ColdStartBenchmark: XCTestCase {
    func testColdStartReachesMeshStartedWithinTarget() {
        // Arrange
        let application = BenchmarkTestSupport.launchProofApp(
            appId: "demo.meshlink.benchmark.coldstart",
            benchmarkColdStart: true
        )

        // Act
        let logLine = BenchmarkTestSupport.waitForLogLine(
            in: application,
            containing: "BENCHMARK coldStart",
            timeout: 10
        )
        let elapsedMilliseconds = BenchmarkTestSupport.extractElapsedMilliseconds(from: logLine)

        // Assert
        XCTAssertLessThan(
            elapsedMilliseconds,
            500,
            "Expected cold start < 500 ms, but observed \(elapsedMilliseconds) ms"
        )
    }
}
