import Foundation
import MeshLinkKit

extension MeshLink: MeshNode {
    /// `state.value` as a `MeshNodeState`.
    public var currentState: MeshNodeState { MeshNodeState(state.value) }

    /// `state` as `MeshNodeState`s.
    public var stateUpdates: AsyncStream<MeshNodeState> { mapped(state) { MeshNodeState($0) } }

    /// `peers.value` as `MeshPeer`s.
    public var currentPeers: [MeshPeer] { peers.value.map(MeshPeer.init) }

    /// `peers` as `MeshPeer`s.
    public var peerUpdates: AsyncStream<[MeshPeer]> { mapped(peers) { $0.map(MeshPeer.init) } }

    /// `messages` as `MeshMessage`s.
    public var receivedMessages: AsyncStream<MeshMessage> { mapped(messages) { MeshMessage($0) } }

    /// `send(to:payload:options:)` in Swift types; `.rejected(.unknownPeer)` for an ID of no peer or message.
    public func send(_ payload: Data, to peer: MeshNodeID, options: MeshSendOptions) throws -> MeshSendResult {
        guard let nodeId = peer.nodeId else { return .rejected(.unknownPeer) }
        let sendOptions = SendOptions(directOnly: options.directOnly, readReceipt: options.readReceipt)
        return MeshSendResult(try send(to: nodeId, payload: payload.kotlinByteArray, options: sendOptions))
    }

    /// `broadcast(payload:hopLimit:)` in Swift types.
    public func broadcast(_ payload: Data, hopLimit: Int) throws -> MeshSendResult {
        MeshSendResult(try broadcast(payload: payload.kotlinByteArray, hopLimit: Int32(clamping: hopLimit)))
    }

    /// `diagnostics.export()` from the main actor: the JSON report to attach to a bug report. Under
    /// Swift 6 a main-actor caller can't await `diagnostics.export()` itself ("sending risks causing
    /// data races"); the export runs off the main actor, and a Kotlin `suspend` function is safe from any thread.
    @MainActor public func exportDiagnostics() async throws -> String {
        let diagnostics = Unchecked(self.diagnostics)
        return try await Task.detached { try await diagnostics.value.export() }.value
    }
}

// Follows a SKIE flow as an AsyncStream; ending the stream cancels the Kotlin collection. Collection
// runs off the main actor and maps each Kotlin element to a Sendable Swift value there.
private func mapped<S: AsyncSequence, T: Sendable>(_ flow: S, _ transform: @escaping @Sendable (S.Element) -> T) -> AsyncStream<T> {
    let flow = Unchecked(flow)
    return AsyncStream { continuation in
        let task = Task.detached {
            for try await element in flow.value { continuation.yield(transform(element)) }
            continuation.finish()
        }
        continuation.onTermination = { _ in task.cancel() }
    }
}

// A SKIE flow handed to the collecting task; SKIE flows are safe to collect from any thread.
private struct Unchecked<Value>: @unchecked Sendable {
    let value: Value
    init(_ value: Value) { self.value = value }
}

private extension Date {
    init(_ instant: KotlinInstant) {
        self.init(timeIntervalSince1970: Double(instant.toEpochMilliseconds()) / 1000)
    }
}

private extension MeshNodeID {
    init(_ nodeId: NodeId) {
        self.init(bytes: Data(nodeId.toByteArray()), nodeId: nodeId)
    }
}

private extension MeshNodeState {
    init(_ state: MeshState) {
        switch onEnum(of: state) {
        case .stopped: self = .stopped
        case .starting: self = .starting
        case .running(let running):
            self = .running(limitations: Set(running.limitations.compactMap { ($0 as? Limitation).map(MeshLimitation.init) }))
        case .blocked(let blocked):
            self = .blocked(requirements: Set(blocked.requirements.compactMap { ($0 as? Requirement).map(MeshRequirement.init) }))
        case .stopping: self = .stopping
        }
    }
}

private extension MeshRequirement {
    init(_ requirement: Requirement) {
        switch onEnum(of: requirement) {
        case .bluetoothOff: self = .bluetoothOff
        case .permissionDenied(let denied): self = .permissionDenied(denied.kind)
        case .unsupported: self = .unsupported
        case .radioInUse: self = .radioInUse
        case .identityUnavailable: self = .identityUnavailable
        }
    }
}

private extension MeshLimitation {
    init(_ limitation: Limitation) {
        switch onEnum(of: limitation) {
        case .centralOnly(let centralOnly): self = .centralOnly(centralOnly.reason)
        }
    }
}

private extension MeshPeer {
    init(_ peer: Peer) {
        let reachability: MeshReachability = switch onEnum(of: peer.reachability) {
        case .direct: .direct
        case .routed(let routed): .routed(hops: Int(routed.hops))
        }
        let proximity = peer.proximity.map {
            MeshProximity(rssi: $0.rssi?.doubleValue, bucket: $0.bucket, measuredAt: $0.measuredAt.map(Date.init))
        }
        self.init(id: MeshNodeID(peer.nodeId), reachability: reachability, secured: peer.secured, lastSeen: Date(peer.lastSeen), proximity: proximity)
    }
}

private extension MeshDeliveryStatus {
    init(_ status: DeliveryStatus) {
        switch onEnum(of: status) {
        case .queued: self = .queued
        case .held: self = .held
        case .sent: self = .sent
        case .delivered: self = .delivered
        case .read: self = .read
        case .expired: self = .expired
        case .failed(let failed): self = .failed(failed.reason)
        }
    }
}

private extension MeshSendResult {
    init(_ result: SendResult) {
        switch onEnum(of: result) {
        case .accepted(let accepted):
            nonisolated(unsafe) let message = accepted.message
            self = .accepted(MeshOutgoingMessage(
                to: message.to.map(MeshNodeID.init),
                status: { MeshDeliveryStatus(message.status.value) },
                statusUpdates: { mapped(message.status) { MeshDeliveryStatus($0) } }
            ))
        case .rejected(let rejected): self = .rejected(rejected.reason)
        }
    }
}

private extension MeshMessage {
    init(_ message: ReceivedMessage) {
        nonisolated(unsafe) let message = message
        self.init(
            sender: MeshNodeID(message.sender),
            payload: Data(message.payload),
            isBroadcast: message.broadcast,
            readReceiptRequested: message.readReceiptRequested,
            markRead: { message.markRead() }
        )
    }
}
