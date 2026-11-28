import Foundation
import MeshLink

/// An in-memory mesh of `FakeMeshNode`s on a virtual clock, for an app's tests. Pure Swift; it never
/// touches the radio, so never link it into an app target.
///
/// Links are explicit: `connect` puts two nodes in range at an RSSI, `disconnect` takes them apart.
/// Messages move at once along the links between running nodes; `advance(by:)` moves the clock, which
/// expires what is still held. The fake follows the reference scenarios of `meshlink-testing`; it lists
/// neighbors only, because routed peers come from route discovery, which it doesn't model.
@MainActor
public final class FakeMesh {
    /// The virtual time now.
    public private(set) var now: Date
    private var nodes: [FakeMeshNode] = []
    private var rssi: [Set<Int>: Double] = [:]

    /// An empty mesh whose clock starts at `start`.
    public init(start: Date = Date(timeIntervalSince1970: 0)) {
        now = start
    }

    /// Adds a stopped node with `config`, named `name` in its ID's description.
    public func node(_ name: String, config: MeshLinkConfig) -> FakeMeshNode {
        var bytes = Data(repeating: 0, count: 32)
        bytes.replaceSubrange(0..<min(name.utf8.count, 31), with: name.utf8.prefix(31))
        bytes[31] = UInt8(truncatingIfNeeded: nodes.count)
        let node = FakeMeshNode(mesh: self, id: MeshNodeID(bytes: bytes), config: config)
        nodes.append(node)
        return node
    }

    /// Puts `first` and `second` in range of each other at `rssi` dBm. Below -85 dBm they don't link.
    public func connect(_ first: FakeMeshNode, _ second: FakeMeshNode, rssi: Double = -60) {
        self.rssi[[first.index, second.index]] = rssi
        changed()
    }

    /// Takes `first` and `second` out of range of each other.
    public func disconnect(_ first: FakeMeshNode, _ second: FakeMeshNode) {
        rssi[[first.index, second.index]] = nil
        changed()
    }

    /// Moves the clock on by `seconds`, expiring the messages whose lifetime and 30 s of skew passed.
    public func advance(by seconds: TimeInterval) {
        now += seconds
        for node in nodes { node.expire() }
    }

    func index(of node: FakeMeshNode) -> Int { nodes.firstIndex { $0 === node }! }

    func node(_ id: MeshNodeID) -> FakeMeshNode? { nodes.first { $0.id == id } }

    func linkRssi(_ first: FakeMeshNode, _ second: FakeMeshNode) -> Double? {
        guard first.isRunning, second.isRunning, let value = rssi[[first.index, second.index]], value >= -85 else { return nil }
        return value
    }

    func neighbors(of node: FakeMeshNode) -> [FakeMeshNode] {
        nodes.filter { $0 !== node && linkRssi(node, $0) != nil }
    }

    // The nodes within `hops` links of `origin`, nearest first, without `origin`.
    func reach(from origin: FakeMeshNode, hops: Int) -> [FakeMeshNode] {
        var seen: [FakeMeshNode] = [origin]
        var frontier = [origin]
        for _ in 0..<hops {
            var next: [FakeMeshNode] = []
            for neighbor in frontier.flatMap(neighbors) where !seen.contains(where: { $0 === neighbor }) {
                seen.append(neighbor)
                next.append(neighbor)
            }
            frontier = next
        }
        return Array(seen.dropFirst())
    }

    func changed() {
        for node in nodes { node.topologyChanged() }
    }
}

/// A node of a `FakeMesh`, conforming to `MeshNode` as `MeshLink` does.
@MainActor
public final class FakeMeshNode: MeshNode {
    /// This node's NodeId.
    public let id: MeshNodeID
    /// The config the node follows: lifetime, read receipts, broadcast origination and payload limits.
    public let config: MeshLinkConfig
    /// Every message that arrived here, in order, whether or not the app took it yet.
    public private(set) var arrived: [MeshMessage] = []

    private unowned let mesh: FakeMesh
    private let state = Cell<MeshNodeState>(.stopped)
    private let peers = Cell<[MeshPeer]>([])
    private var closed = false
    private var inbox: [(MeshMessage, Envelope?)] = []
    private var taker: CheckedContinuation<MeshMessage?, Never>?
    private var outgoing: [Envelope] = []
    private var capacityWaiters: [CheckedContinuation<Void, Never>] = []

    init(mesh: FakeMesh, id: MeshNodeID, config: MeshLinkConfig) {
        self.mesh = mesh
        self.id = id
        self.config = config
    }

    var index: Int { mesh.index(of: self) }
    var isRunning: Bool { if case .running = state.value { true } else { false } }

    /// The state now.
    public var currentState: MeshNodeState { state.value }
    /// The state now, then each change.
    public var stateUpdates: AsyncStream<MeshNodeState> { state.stream() }
    /// The neighbors now, each `.direct` with the proximity of its link.
    public var currentPeers: [MeshPeer] { peers.value }
    /// The neighbors now, then each change.
    public var peerUpdates: AsyncStream<[MeshPeer]> { peers.stream() }

    /// The arrived messages the app hasn't taken, then each new one; taking one makes it `.delivered`.
    public var receivedMessages: AsyncStream<MeshMessage> {
        AsyncStream(unfolding: { [weak self] in await self?.take() })
    }

    /// Runs the node at once; throws `MeshLinkException.Closed` after `close()`.
    public func start() async throws {
        try checkOpen()
        guard !isRunning else { return }
        state.value = .running(limitations: [])
        mesh.changed()
    }

    /// Stops the node at once; its unfinished messages fail as `.failed(.stopped)`.
    public func stop() async throws {
        try checkOpen()
        halt()
    }

    /// Stops the node for good; afterwards `start()` and `stop()` throw.
    public func close() {
        guard !closed else { return }
        halt()
        closed = true
        taker?.resume(returning: nil)
        taker = nil
    }

    /// Sends as `MeshLink.send` does, with the same rejections.
    public func send(_ payload: Data, to peer: MeshNodeID, options: MeshSendOptions) throws -> MeshSendResult {
        try checkOpen()
        if !accepting { return .rejected(.notRunning) }
        guard peer != id else { return .rejected(.unknownPeer) }
        if let reason = rejection(payload.count, max: Int(config.limits.maxUnicastPayload)) { return .rejected(reason) }
        let receipt = switch options.readReceipt {
        case .request: true
        case .doNotRequest: false
        case .default: config.readReceipts.requestByDefault
        }
        return accept(Envelope(to: peer, payload: payload, directOnly: options.directOnly, readReceipt: receipt, hopLimit: 0, at: mesh.now))
    }

    /// Broadcasts as `MeshLink.broadcast` does: to every running node within `hopLimit` links, at once.
    public func broadcast(_ payload: Data, hopLimit: Int) throws -> MeshSendResult {
        try checkOpen()
        if !accepting { return .rejected(.notRunning) }
        guard (1...255).contains(hopLimit) else { return .rejected(.invalidHopLimit) }
        if let reason = rejection(payload.count, max: Int(config.limits.maxBroadcastPayload)) { return .rejected(reason) }
        guard config.broadcastOrigination else { return .rejected(.broadcastDisabled) }
        return accept(Envelope(to: nil, payload: payload, directOnly: false, readReceipt: false, hopLimit: hopLimit, at: mesh.now))
    }

    /// Returns once the unfinished payloads are below 1 MiB.
    public func awaitSendCapacity() async throws {
        try checkOpen()
        guard unfinishedBytes >= queueBytes else { return }
        await withCheckedContinuation { capacityWaiters.append($0) }
    }

    private let queueBytes = 1_048_576

    private var unfinishedBytes: Int { outgoing.filter { !$0.ended }.reduce(0) { $0 + $1.payload.count } }

    private func checkOpen() throws {
        if closed { throw NSError(domain: "KotlinException", code: 0, userInfo: ["KotlinException": MeshLinkException.Closed()]) }
    }

    private var accepting: Bool {
        switch state.value {
        case .running, .blocked: true
        case .stopped, .starting, .stopping: false
        }
    }

    private func rejection(_ size: Int, max: Int) -> RejectionReason? {
        if size > max { return .tooLarge }
        if unfinishedBytes + size > queueBytes { return .queueFull }
        return nil
    }

    private func accept(_ envelope: Envelope) -> MeshSendResult {
        outgoing.append(envelope)
        if let to = envelope.to { deliver(envelope, to: to) } else { flood(envelope) }
        return .accepted(MeshOutgoingMessage(
            to: envelope.to,
            status: { envelope.status.value },
            statusUpdates: { envelope.status.stream() }
        ))
    }

    private func flood(_ envelope: Envelope) {
        for node in mesh.reach(from: self, hops: envelope.hopLimit) {
            node.arrive(MeshMessage(sender: id, payload: envelope.payload, isBroadcast: true, readReceiptRequested: false) {}, nil)
        }
        envelope.status.value = .sent
    }

    private func deliver(_ envelope: Envelope, to id: MeshNodeID) {
        let ways = envelope.directOnly ? mesh.neighbors(of: self) : mesh.reach(from: self, hops: 255)
        guard let destination = ways.first(where: { $0.id == id }) else {
            envelope.status.value = .held
            return
        }
        envelope.status.value = .sent
        let message = MeshMessage(sender: self.id, payload: envelope.payload, isBroadcast: false, readReceiptRequested: envelope.readReceipt) { [weak destination] in
            destination?.markRead(envelope)
        }
        destination.arrive(message, envelope)
    }

    private func arrive(_ message: MeshMessage, _ envelope: Envelope?) {
        arrived.append(message)
        if let taker {
            self.taker = nil
            taken(envelope)
            taker.resume(returning: message)
        } else {
            inbox.append((message, envelope))
        }
    }

    private func take() async -> MeshMessage? {
        if !inbox.isEmpty {
            let (message, envelope) = inbox.removeFirst()
            taken(envelope)
            return message
        }
        if closed { return nil }
        return await withCheckedContinuation { taker = $0 }
    }

    private func taken(_ envelope: Envelope?) {
        guard let envelope, envelope.status.value == .sent else { return }
        envelope.status.value = .delivered
        envelope.ended = true
    }

    private func markRead(_ envelope: Envelope) {
        guard envelope.readReceipt, config.readReceipts.send, envelope.status.value == .delivered else { return }
        envelope.status.value = .read
    }

    private func halt() {
        for envelope in outgoing where !envelope.ended {
            envelope.status.value = .failed(.stopped)
            envelope.ended = true
        }
        state.value = .stopped
        mesh.changed()
        freeCapacity()
    }

    func topologyChanged() {
        peers.value = isRunning ? mesh.neighbors(of: self).map { neighbor in
            let rssi = mesh.linkRssi(self, neighbor)!
            let bucket: ProximityBucket = rssi >= -55 ? .immediate : rssi >= -75 ? .near : .far
            let proximity = MeshProximity(rssi: rssi, bucket: bucket, measuredAt: mesh.now)
            return MeshPeer(id: neighbor.id, reachability: .direct, secured: secured, lastSeen: mesh.now, proximity: proximity)
        } : []
        guard isRunning else { return }
        for envelope in outgoing where envelope.status.value == .held { deliver(envelope, to: envelope.to!) }
    }

    func expire() {
        for envelope in outgoing where !envelope.ended && envelope.status.value != .delivered {
            if mesh.now >= envelope.at + TimeInterval(config.messageLifetimeSeconds) + 30 {
                envelope.status.value = .expired
                envelope.ended = true
            }
        }
        freeCapacity()
    }

    private var secured: Bool {
        if case .unsecured = onEnum(of: config.securityProfile) { false } else { true }
    }

    private func freeCapacity() {
        guard unfinishedBytes < queueBytes else { return }
        let waiters = capacityWaiters
        capacityWaiters = []
        for waiter in waiters { waiter.resume() }
    }
}

@MainActor
private final class Envelope {
    let to: MeshNodeID?
    let payload: Data
    let directOnly: Bool
    let readReceipt: Bool
    let hopLimit: Int
    let at: Date
    let status = Cell<MeshDeliveryStatus>(.queued)
    var ended = false

    init(to: MeshNodeID?, payload: Data, directOnly: Bool, readReceipt: Bool, hopLimit: Int, at: Date) {
        self.to = to
        self.payload = payload
        self.directOnly = directOnly
        self.readReceipt = readReceipt
        self.hopLimit = hopLimit
        self.at = at
    }
}

// A value with streams that start with the value now, then follow each change, like a StateFlow.
@MainActor
private final class Cell<Value: Equatable & Sendable> {
    private var continuations: [UUID: AsyncStream<Value>.Continuation] = [:]

    var value: Value {
        didSet {
            guard value != oldValue else { return }
            for continuation in continuations.values { continuation.yield(value) }
        }
    }

    init(_ value: Value) {
        self.value = value
    }

    func stream() -> AsyncStream<Value> {
        let key = UUID()
        let (stream, continuation) = AsyncStream<Value>.makeStream()
        continuation.yield(value)
        continuations[key] = continuation
        continuation.onTermination = { [weak self] _ in Task { @MainActor in self?.continuations[key] = nil } }
        return stream
    }
}
