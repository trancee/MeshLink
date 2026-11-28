import Foundation
import MeshLinkKit

/// A MeshLink node in Swift types. `MeshLink` conforms, and so does `FakeMeshNode` from
/// `MeshLinkTesting`, so an app's tests swap the fake in for the radio.
///
/// Use it from the main actor (ADR 0007 decision 13).
@MainActor
public protocol MeshNode: AnyObject {
    /// The node's state now.
    var currentState: MeshNodeState { get }

    /// The node's state now, then each change; cancel the collecting task to stop.
    var stateUpdates: AsyncStream<MeshNodeState> { get }

    /// The peers the node reaches now.
    var currentPeers: [MeshPeer] { get }

    /// The peers now, then each change; cancel the collecting task to stop.
    var peerUpdates: AsyncStream<[MeshPeer]> { get }

    /// The messages other nodes sent to this one, until the node closes. One collector at a time.
    var receivedMessages: AsyncStream<MeshMessage> { get }

    /// Starts the node and returns once it runs or is blocked.
    func start() async throws

    /// Stops the node and returns once it has stopped.
    func stop() async throws

    /// Stops the node for good and frees its radio.
    func close()

    /// Sends `payload` to `peer`, or says why not.
    func send(_ payload: Data, to peer: MeshNodeID, options: MeshSendOptions) throws -> MeshSendResult

    /// Broadcasts `payload` to every node within `hopLimit` hops, or says why not.
    func broadcast(_ payload: Data, hopLimit: Int) throws -> MeshSendResult

    /// Returns once the send queue has room again, after a `.rejected(.queueFull)`.
    func awaitSendCapacity() async throws
}

extension MeshNode {
    /// Sends `payload` to `peer` over any way, with the config's read-receipt default.
    public func send(_ payload: Data, to peer: MeshNodeID) throws -> MeshSendResult {
        try send(payload, to: peer, options: MeshSendOptions())
    }

    /// Broadcasts `payload` to every node within 255 hops.
    public func broadcast(_ payload: Data) throws -> MeshSendResult {
        try broadcast(payload, hopLimit: 255)
    }
}

/// A node's NodeId. Two IDs are equal if their bytes are.
public struct MeshNodeID: Hashable, @unchecked Sendable, CustomStringConvertible {
    /// The NodeId's bytes.
    public let bytes: Data

    // The Kotlin NodeId this ID came from, which `MeshLink.send` needs; immutable, so safe to share.
    let nodeId: NodeId?

    /// An ID of `bytes`. `MeshLink` rejects a send to it as `.unknownPeer`; use the IDs of peers and messages.
    public init(bytes: Data) {
        self.init(bytes: bytes, nodeId: nil)
    }

    init(bytes: Data, nodeId: NodeId?) {
        self.bytes = bytes
        self.nodeId = nodeId
    }

    /// The bytes in lowercase hex.
    public var description: String {
        bytes.map { String(format: "%02x", $0) }.joined()
    }

    /// Whether `lhs` and `rhs` have the same bytes.
    public static func == (lhs: MeshNodeID, rhs: MeshNodeID) -> Bool {
        lhs.bytes == rhs.bytes
    }

    /// Hashes the bytes.
    public func hash(into hasher: inout Hasher) {
        hasher.combine(bytes)
    }
}

/// The state of a node, as `MeshState`.
public enum MeshNodeState: Hashable, Sendable {
    /// Not started, or stopped.
    case stopped
    /// Starting.
    case starting
    /// Running, perhaps with `limitations`.
    case running(limitations: Set<MeshLimitation>)
    /// Started but waiting for `requirements`.
    case blocked(requirements: Set<MeshRequirement>)
    /// Stopping.
    case stopping
}

/// What a blocked node waits for, as `Requirement`.
public enum MeshRequirement: Hashable, Sendable {
    /// Bluetooth is off.
    case bluetoothOff
    /// The app lacks a permission.
    case permissionDenied(PermissionKind)
    /// This device can't run MeshLink, for example the iOS simulator.
    case unsupported
    /// Another node of this app holds the radio.
    case radioInUse
    /// The node's identity can't be loaded.
    case identityUnavailable
}

/// What a running node can't do, as `Limitation`.
public enum MeshLimitation: Hashable, Sendable {
    /// The node only connects out; others can't find it.
    case centralOnly(CentralOnlyReason)
}

/// A peer a node reaches, as `Peer`.
public struct MeshPeer: Hashable, Sendable {
    /// The peer's NodeId.
    public let id: MeshNodeID
    /// How the node reaches the peer.
    public let reachability: MeshReachability
    /// Whether the peer's link is secured.
    public let secured: Bool
    /// When the node last heard from the peer.
    public let lastSeen: Date
    /// The signal strength of a direct peer; `nil` for a routed one.
    public let proximity: MeshProximity?

    /// A peer of these values.
    public init(id: MeshNodeID, reachability: MeshReachability, secured: Bool, lastSeen: Date, proximity: MeshProximity?) {
        self.id = id
        self.reachability = reachability
        self.secured = secured
        self.lastSeen = lastSeen
        self.proximity = proximity
    }
}

/// How a node reaches a peer, as `Reachability`.
public enum MeshReachability: Hashable, Sendable {
    /// Over a link of its own.
    case direct
    /// Through other nodes, on a route of `hops` links.
    case routed(hops: Int)
}

/// The signal strength of a direct peer, as `Proximity`. Never a distance.
public struct MeshProximity: Hashable, Sendable {
    /// The smoothed RSSI in dBm, if measured.
    public let rssi: Double?
    /// The bucket of `rssi`.
    public let bucket: ProximityBucket
    /// When `rssi` was measured.
    public let measuredAt: Date?

    /// A proximity of these values.
    public init(rssi: Double?, bucket: ProximityBucket, measuredAt: Date?) {
        self.rssi = rssi
        self.bucket = bucket
        self.measuredAt = measuredAt
    }
}

/// How `MeshNode.send` sends one message, as `SendOptions`.
public struct MeshSendOptions: Hashable, Sendable {
    /// Whether only a link to the destination may carry the message.
    public var directOnly: Bool
    /// Whether the message asks for a read receipt.
    public var readReceipt: ReadReceiptRequest

    /// Options of these values; by default any way, with the config's read-receipt default.
    public init(directOnly: Bool = false, readReceipt: ReadReceiptRequest = .default) {
        self.directOnly = directOnly
        self.readReceipt = readReceipt
    }
}

/// The answer to a send or broadcast, as `SendResult`.
public enum MeshSendResult: Sendable {
    /// Accepted; follow the message's status.
    case accepted(MeshOutgoingMessage)
    /// Rejected for `reason`; nothing was sent.
    case rejected(RejectionReason)
}

/// A message a node accepted, as `OutgoingMessage`.
public struct MeshOutgoingMessage: Sendable {
    /// The destination; `nil` for a broadcast.
    public let to: MeshNodeID?
    private let current: @MainActor @Sendable () -> MeshDeliveryStatus
    private let updates: @MainActor @Sendable () -> AsyncStream<MeshDeliveryStatus>

    /// A message to `to` whose status `status` reads and `statusUpdates` follows.
    public init(
        to: MeshNodeID?,
        status: @escaping @MainActor @Sendable () -> MeshDeliveryStatus,
        statusUpdates: @escaping @MainActor @Sendable () -> AsyncStream<MeshDeliveryStatus>
    ) {
        self.to = to
        current = status
        updates = statusUpdates
    }

    /// The status now.
    @MainActor public var status: MeshDeliveryStatus { current() }

    /// The status now, then each change; cancel the collecting task to stop.
    @MainActor public var statusUpdates: AsyncStream<MeshDeliveryStatus> { updates() }
}

/// Where an accepted message is, as `DeliveryStatus`.
public enum MeshDeliveryStatus: Hashable, Sendable {
    /// Accepted; nothing is on air yet.
    case queued
    /// Waiting for a way to the destination.
    case held
    /// A copy left this node.
    case sent
    /// The destination's app took it; final unless a requested read receipt follows.
    case delivered
    /// Final: the destination's app marked it read.
    case read
    /// Final: no receipt arrived by its expiry.
    case expired
    /// Final: a local cause ended it.
    case failed(FailureReason)
}

/// A message another node sent to this one, as `ReceivedMessage`.
public struct MeshMessage: Sendable {
    /// The sender's NodeId.
    public let sender: MeshNodeID
    /// The payload.
    public let payload: Data
    /// Whether the sender broadcast it to the mesh.
    public let isBroadcast: Bool
    /// Whether the sender asked for a read receipt.
    public let readReceiptRequested: Bool
    private let read: @MainActor @Sendable () -> Void

    /// A message of these values; `markRead` runs on `markRead()`.
    public init(
        sender: MeshNodeID,
        payload: Data,
        isBroadcast: Bool,
        readReceiptRequested: Bool,
        markRead: @escaping @MainActor @Sendable () -> Void
    ) {
        self.sender = sender
        self.payload = payload
        self.isBroadcast = isBroadcast
        self.readReceiptRequested = readReceiptRequested
        read = markRead
    }

    /// Sends the read receipt if one was requested and is allowed; idempotent.
    @MainActor public func markRead() {
        read()
    }
}
