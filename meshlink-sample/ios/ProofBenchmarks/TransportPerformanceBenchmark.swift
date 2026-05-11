import XCTest

final class TransportPerformanceBenchmark: XCTestCase {
    func testLatency256ByteSendStaysWithinTarget() throws {
        try requirePeerBenchmarksEnabled()

        // Arrange
        let application = BenchmarkTestSupport.launchProofApp(
            appId: "demo.meshlink.benchmark.latency",
            benchmarkPayloadBytes: 256
        )

        // Act
        let logLine = BenchmarkTestSupport.waitForLogLine(
            in: application,
            containing: "BENCHMARK transport bytes=",
            timeout: 20
        )
        let elapsedMilliseconds = BenchmarkTestSupport.extractElapsedMilliseconds(from: logLine)
        let result = BenchmarkTestSupport.extractResult(from: logLine)

        // Assert
        XCTAssertEqual(
            result,
            "Sent",
            "Latency benchmarks require a nearby proof peer running appId=demo.meshlink.benchmark.latency"
        )
        XCTAssertLessThanOrEqual(
            elapsedMilliseconds,
            50,
            "Expected 256-byte latency <= 50 ms, but observed \(elapsedMilliseconds) ms"
        )
    }

    func testThroughput64KiBStaysWithinTarget() throws {
        try requirePeerBenchmarksEnabled()

        // Arrange
        let application = BenchmarkTestSupport.launchProofApp(
            appId: "demo.meshlink.benchmark.throughput",
            benchmarkPayloadBytes: 64 * 1024
        )

        // Act
        let logLine = BenchmarkTestSupport.waitForLogLine(
            in: application,
            containing: "BENCHMARK transport bytes=",
            timeout: 30
        )
        let throughputKilobytesPerSecond = BenchmarkTestSupport.extractThroughputKilobytesPerSecond(from: logLine)
        let result = BenchmarkTestSupport.extractResult(from: logLine)

        // Assert
        XCTAssertEqual(
            result,
            "Sent",
            "Throughput benchmarks require a nearby proof peer running appId=demo.meshlink.benchmark.throughput"
        )
        XCTAssertGreaterThanOrEqual(
            throughputKilobytesPerSecond,
            60.0,
            "Expected throughput >= 60 KB/s, but observed \(throughputKilobytesPerSecond) KB/s"
        )
    }

    private func requirePeerBenchmarksEnabled() throws {
        guard ProcessInfo.processInfo.environment["MESHLINK_BENCHMARK_ENABLE_PEER_TESTS"] == "true" else {
            throw XCTSkip(
                "Requires a nearby proof peer and MESHLINK_BENCHMARK_ENABLE_PEER_TESTS=true"
            )
        }
    }
}
