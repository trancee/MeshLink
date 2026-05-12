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
            benchmarkPayloadBytes: 64 * 1024,
            transportTelemetry: true
        )

        // Act
        let logLine = BenchmarkTestSupport.waitForLogLine(
            in: application,
            containing: "BENCHMARK transport bytes=",
            timeout: 30
        )
        let writeTelemetry = BenchmarkTestSupport.waitForTransportTelemetryLine(
            in: application,
            containing: "event=write.frame"
                + " directType=DATA"
                + " dataClass=bulkLikely",
            timeout: 10
        )
        let readTelemetry = BenchmarkTestSupport.waitForTransportTelemetryLine(
            in: application,
            containing: "event=read.frame"
                + " directType=DATA"
                + " dataClass=ackLikely",
            timeout: 10
        )
        let throughputKilobytesPerSecond = BenchmarkTestSupport.extractThroughputKilobytesPerSecond(from: logLine)
        let result = BenchmarkTestSupport.extractResult(from: logLine)
        let writeBatches = BenchmarkTestSupport.extractTelemetryInteger(from: writeTelemetry, key: "writeBatches")
        let maxWriteBatchBytes = BenchmarkTestSupport.extractTelemetryInteger(from: writeTelemetry, key: "maxWriteBatchBytes")
        let readCalls = BenchmarkTestSupport.extractTelemetryInteger(from: readTelemetry, key: "readCalls")

        // Assert
        XCTAssertEqual(
            result,
            "Sent",
            "Throughput benchmarks require a nearby proof peer running appId=demo.meshlink.benchmark.throughput"
        )
        XCTAssertGreaterThanOrEqual(writeBatches, 1, "Expected at least one bounded write batch to be recorded")
        XCTAssertGreaterThan(maxWriteBatchBytes, 0, "Expected write batch telemetry to report a positive batch size")
        XCTAssertGreaterThanOrEqual(readCalls, 1, "Expected read-drain telemetry to report at least one stream read")
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
