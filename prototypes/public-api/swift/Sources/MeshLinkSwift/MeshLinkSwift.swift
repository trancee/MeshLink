// Minimal Swift overlay over the SKIE-generated MeshLinkKit API. Idioms only, no logic.
@_exported import MeshLinkKit
import Foundation

public extension KotlinByteArray {
    /// Copies `data` into a new Kotlin byte array.
    convenience init(data: Data) {
        self.init(size: Int32(data.count))
        for (index, byte) in data.enumerated() {
            set(index: Int32(index), value: Int8(bitPattern: byte))
        }
    }

    /// Copies the bytes into `Data`.
    var data: Data {
        Data((0..<size).map { UInt8(bitPattern: get(index: $0)) })
    }
}

public extension MeshLink {
    /// `send(to:payload:options:)` with `Data` and Swift default options.
    func send(to destination: NodeId, data: Data, options: SendOptions = .default) -> SendResult {
        send(to: destination, payload: KotlinByteArray(data: data), options: options)
    }

    /// `broadcast(payload:hopLimit:options:)` with `Data` and Swift default options.
    func broadcast(_ data: Data, hopLimit: Int, options: BroadcastOptions = .default) -> SendResult {
        broadcast(payload: KotlinByteArray(data: data), hopLimit: Int32(hopLimit), options: options)
    }
}

public extension ReceivedMessage {
    /// The payload as `Data`.
    var data: Data { payload.data }
}

public extension SendOptions {
    /// Routed, no receipts, preset lifetime.
    static var `default`: SendOptions { SendOptions.companion.Default }

    /// Swift-default-argument initializer; `messageLifetime` in seconds, `nil` for the preset lifetime.
    convenience init(directOnly: Bool = false, deliveryReceipt: Bool = false, readReceipt: Bool = false, messageLifetime: Int? = nil) {
        self.init(
            directOnly: directOnly,
            deliveryReceipt: deliveryReceipt,
            readReceipt: readReceipt,
            messageLifetimeSeconds: messageLifetime.map { KotlinInt(value: Int32($0)) }
        )
    }
}

public extension BroadcastOptions {
    /// Preset lifetime.
    static var `default`: BroadcastOptions { BroadcastOptions.companion.Default }
}

public extension MeshLinkConfig.Builder {
    /// Unlabeled fluent setter: `.meshId("x")` instead of `.meshId(id: "x")`.
    func meshId(_ id: String) -> MeshLinkConfig.Builder { meshId(id: id) }

    /// `Data` fluent setter for the mesh key.
    func meshKey(_ key: Data) -> MeshLinkConfig.Builder { meshKey(key: KotlinByteArray(data: key)) }
}

public extension MeshLink {
    /// `MeshLink.companion.create(config:platform:)` as a static factory.
    static func create(config: MeshLinkConfig, platform: MeshLinkPlatform) throws -> MeshLink {
        try MeshLink.companion.create(config: config, platform: platform)
    }
}
