import XCTest

final class RecipientConfirmedBenchmarkDiagnostic: XCTestCase {
    func testSamsungRecipientConfirmed256ByteReproCapturesSenderEvidence() throws {
        try requirePeerBenchmarksEnabled()
        try runRecipientConfirmedDiagnostic(
            scenario: "samsung256",
            appId: "demo.meshlink.benchmark.recipientconfirmed.samsung256"
        )
    }

    func testOppoRecipientConfirmed256ByteReproCapturesSenderEvidence() throws {
        try requirePeerBenchmarksEnabled()
        try runRecipientConfirmedDiagnostic(
            scenario: "oppo256",
            appId: "demo.meshlink.benchmark.recipientconfirmed.oppo256"
        )
    }

    private func runRecipientConfirmedDiagnostic(
        scenario: String,
        appId: String
    ) throws {
        // Arrange
        let application = BenchmarkTestSupport.launchProofApp(
            appId: appId,
            benchmarkPayloadBytes: 256
        )

        // Act
        let senderSendLine = BenchmarkTestSupport.waitForLogLine(
            in: application,
            containing: "BENCHMARK correlation role=sender.benchmark.send",
            timeout: 20
        )
        let benchmarkLine = BenchmarkTestSupport.waitForLogLine(
            in: application,
            containing: "BENCHMARK transport bytes=256",
            timeout: 25
        )
        let senderResultLine = BenchmarkTestSupport.waitForLogLine(
            in: application,
            containing: "BENCHMARK correlation role=sender.benchmark.result",
            timeout: 5
        )
        let tokenHex = BenchmarkTestSupport.extractTokenHex(from: senderSendLine)
        let senderRecentDiagnosticsLine = BenchmarkTestSupport.latestLogLine(
            in: application,
            containing: "BENCHMARK correlation token=\(tokenHex) recentDiags="
        )
        let senderRouteStateLine = BenchmarkTestSupport.latestLogLine(
            in: application,
            containing: "BENCHMARK correlation token=\(tokenHex) routeState="
        )
        let senderRouteTimelineLine = BenchmarkTestSupport.latestLogLine(
            in: application,
            containing: "BENCHMARK correlation token=\(tokenHex) routeTimeline="
        )

        print("RECIPIENT_CONFIRMED_SCENARIO=\(scenario)")
        print("RECIPIENT_CONFIRMED_SENDER_SEND=\(senderSendLine)")
        print("RECIPIENT_CONFIRMED_SENDER_BENCHMARK=\(benchmarkLine)")
        print("RECIPIENT_CONFIRMED_SENDER_RESULT=\(senderResultLine)")
        print("RECIPIENT_CONFIRMED_SENDER_RECENT_DIAGS=\(senderRecentDiagnosticsLine)")
        print("RECIPIENT_CONFIRMED_SENDER_ROUTE_STATE=\(senderRouteStateLine)")
        print("RECIPIENT_CONFIRMED_SENDER_ROUTE_TIMELINE=\(senderRouteTimelineLine)")

        // Assert
        XCTAssertFalse(tokenHex.isEmpty, "Expected sender benchmark correlation token for \(scenario)")
        XCTAssertTrue(
            benchmarkLine.contains("result="),
            "Expected benchmark result line for \(scenario)"
        )
        XCTAssertTrue(
            senderResultLine.contains(tokenHex),
            "Expected sender result correlation to retain token \(tokenHex) for \(scenario)"
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
