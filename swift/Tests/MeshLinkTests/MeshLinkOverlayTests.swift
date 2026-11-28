import MeshLink
import XCTest

// The overlay against the real MeshLinkKit.xcframework on the iOS Simulator, whose radio reports
// Unsupported (ADR 0005): a node there is Blocked, yet accepts sends into its queue. Under Swift 6
// strict concurrency, a main-actor caller can't await MeshLink's Kotlin suspend functions directly
// ("sending 'node' risks causing data races"); it awaits them through the @MainActor MeshNode protocol.
@MainActor
final class MeshLinkOverlayTests: XCTestCase {
    private var nodes: [MeshLink] = []

    override func tearDown() async throws {
        for node in nodes { node.close() }
        nodes = []
    }

    func testNodeSeenThroughTheProtocolIsBlockedAsUnsupported() async throws {
        // Arrange
        let node: any MeshNode = try makeNode()

        // Act
        try await (node as any MeshNode).start()
        var updates = node.stateUpdates.makeAsyncIterator()
        let update = await updates.next()

        // Assert
        XCTAssertEqual(node.currentState, .blocked(requirements: [.unsupported]))
        XCTAssertEqual(update, .blocked(requirements: [.unsupported]))
    }

    func testKotlinResultsSwitchExhaustivelyWithOnEnum() async throws {
        // Arrange
        let node = try makeNode()
        try await (node as any MeshNode).start()

        // Act
        let result = try node.broadcast(payload: Data("Flood warning".utf8).kotlinByteArray, hopLimit: 3)
        try await (node as any MeshNode).stop()

        // Assert
        guard case .accepted(let accepted) = onEnum(of: result) else { return XCTFail("expected Accepted, got \(result)") }
        let failure: FailureReason? = switch onEnum(of: accepted.message.status.value) {
        case .failed(let failed): failed.reason
        case .queued, .held, .sent, .delivered, .read, .expired: nil
        }
        XCTAssertEqual(failure, .stopped)
        let stopped = switch onEnum(of: node.state.value) {
        case .stopped: true
        case .starting, .running, .blocked, .stopping: false
        }
        XCTAssertTrue(stopped)
    }

    func testAwaitSendCapacityReturnsWhenStopFreesTheQueue() async throws {
        // Arrange
        let node = try makeNode()
        try await (node as any MeshNode).start()
        try fillQueue(node)
        let waiter = Task { try await (node as any MeshNode).awaitSendCapacity() }

        // Act
        try await (node as any MeshNode).stop()

        // Assert
        try await waiter.value
        XCTAssertEqual(try node.broadcast(Data([1]), hopLimit: 1).rejection, .notRunning)
    }

    func testCancellingAwaitSendCapacityThrowsCancellationError() async throws {
        // Arrange
        let node = try makeNode()
        try await (node as any MeshNode).start()
        try fillQueue(node)
        let waiter = Task { try await (node as any MeshNode).awaitSendCapacity() }

        // Act
        waiter.cancel()

        // Assert
        do {
            try await waiter.value
            XCTFail("awaitSendCapacity returned with a full queue")
        } catch {
            XCTAssertTrue(error is CancellationError, "expected CancellationError, got \(error)")
        }
    }

    func testStartOnAClosedNodeThrowsClosed() async throws {
        // Arrange
        let node = try makeNode()
        node.close()

        // Act
        var thrown: Error?
        do { try await (node as any MeshNode).start() } catch { thrown = error }

        // Assert
        let exception = try XCTUnwrap(thrown?.meshLink, "expected a MeshLinkException, got \(String(describing: thrown))")
        let closed = switch onEnum(of: exception) {
        case .closed: true
        default: false
        }
        XCTAssertTrue(closed, "expected Closed, got \(exception)")
    }

    func testDataRoundTripsThroughKotlinByteArray() {
        for size in [0, 1, 128 * 1024] {
            // Arrange
            let data = Data((0..<size).map { UInt8(truncatingIfNeeded: $0 &* 31 &+ 7) })

            // Act
            let roundTripped = Data(data.kotlinByteArray)

            // Assert
            XCTAssertEqual(roundTripped, data, "\(size) B")
        }
    }

    // P1 {workload: one 16 KiB payload converted Data -> KotlinByteArray and back,
    //     environment: iOS Simulator (arm64) on the CI macOS runner or a developer Mac, release XCFramework,
    //     method: 30 warm-up then 300 timed iterations per direction, median of DispatchTime uptime,
    //     threshold: median <= 250 µs per direction}. The SE 2016 bench measured 7.62 µs and 28.71 µs
    //     on a device (docs/evidence/2026-11-se2016-bench); a per-byte copy takes about 2.6 ms.
    func testSixteenKibConvertsWithinBudget() {
        // Arrange
        let data = Data((0..<16 * 1024).map { UInt8(truncatingIfNeeded: $0) })
        let bytes = data.kotlinByteArray

        // Act
        let toKotlin = medianNanoseconds { _ = data.kotlinByteArray }
        let toSwift = medianNanoseconds { _ = Data(bytes) }

        // Assert
        XCTAssertLessThanOrEqual(toKotlin, 250_000, "Data -> KotlinByteArray median \(toKotlin) ns")
        XCTAssertLessThanOrEqual(toSwift, 250_000, "KotlinByteArray -> Data median \(toSwift) ns")
    }

    private func makeNode() throws -> MeshLink {
        let config = try meshLinkConfig(preset: Preset.EmergencyBroadcast.shared, securityProfile: SecurityProfileUnsecured.shared) {
            $0.meshId = "Swift overlay"
        }.build()
        let node = MeshLink.create(config: config)
        nodes.append(node)
        return node
    }

    // Queues maximum-size broadcasts until the node refuses one as QueueFull.
    private func fillQueue(_ node: MeshLink, file: StaticString = #filePath, line: UInt = #line) throws {
        let payload = Data(count: Int(node.config.limits.maxBroadcastPayload))
        for _ in 0..<10_000 {
            switch try node.broadcast(payload, hopLimit: 1) {
            case .accepted: continue
            case .rejected(.queueFull): return
            case .rejected(let reason): return XCTFail("expected QueueFull, got \(reason)", file: file, line: line)
            }
        }
        XCTFail("the queue never filled", file: file, line: line)
    }

    private func medianNanoseconds(_ body: () -> Void) -> UInt64 {
        for _ in 0..<30 { body() }
        let samples = (0..<300).map { _ in
            let start = DispatchTime.now().uptimeNanoseconds
            body()
            return DispatchTime.now().uptimeNanoseconds - start
        }
        return samples.sorted()[samples.count / 2]
    }
}

private extension MeshSendResult {
    var rejection: RejectionReason? {
        if case .rejected(let reason) = self { reason } else { nil }
    }
}
