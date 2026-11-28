@_exported import MeshLinkKit
import Foundation

extension Data {
    /// Copies the bytes of a Kotlin `ByteArray`, such as a `ReceivedMessage` payload, in one bulk copy.
    public init(_ bytes: KotlinByteArray) {
        self = bytes.__toNSData() as Data
    }

    /// These bytes as a Kotlin `ByteArray`, for `MeshLink.send` and `MeshLink.broadcast`, copied in one bulk copy.
    public var kotlinByteArray: KotlinByteArray {
        DataBridgeKt.__toByteArray(self)
    }
}

extension SendOptions {
    /// Sends over any way, with a read receipt as the node's config asks by default.
    public convenience init() {
        self.init(directOnly: false, readReceipt: .default)
    }

    /// Sends only over a link to the destination if `directOnly`, with the config's read-receipt default.
    public convenience init(directOnly: Bool) {
        self.init(directOnly: directOnly, readReceipt: .default)
    }

    /// Sends over any way, asking for a read receipt as `readReceipt` says.
    public convenience init(readReceipt: ReadReceiptRequest) {
        self.init(directOnly: false, readReceipt: readReceipt)
    }
}

extension MeshLink {
    /// Creates a stopped node with `config` on `platform`, by default this iOS installation's radio.
    public static func create(config: MeshLinkConfig, platform: MeshPlatform = MeshPlatform.companion.ios()) -> MeshLink {
        companion.create(config: config, platform: platform)
    }
}

extension Error {
    /// The `MeshLinkException` that a throwing MeshLink call raised, or `nil` for any other error.
    /// Switch over it with `onEnum(of:)`.
    public var meshLink: MeshLinkException? {
        (self as NSError).userInfo["KotlinException"] as? MeshLinkException
    }
}
