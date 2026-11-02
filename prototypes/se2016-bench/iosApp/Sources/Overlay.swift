import Foundation
import MeshLinkKit

// Two Data <-> KotlinByteArray overlays: "bulk" copies with memcpy through the Kotlin DataBridge, "per-byte" is the
// prototype/public-api overlay code unchanged (one get/set call per byte).

enum Overlay: String, CaseIterable {
    case bulk
    case perByte = "per-byte"

    func toKotlin(_ data: Data) -> KotlinByteArray {
        switch self {
        case .bulk: return DataBridge.shared.toByteArray(data: data)
        case .perByte: return KotlinByteArray(perByte: data)
        }
    }

    func toData(_ bytes: KotlinByteArray) -> Data {
        switch self {
        case .bulk: return DataBridge.shared.toNSData(bytes: bytes)
        case .perByte: return bytes.perByteData
        }
    }

    func send(_ mesh: MeshLink, to: NodeId, data: Data) -> SendResult {
        mesh.send(to: to, payload: toKotlin(data), options: SendOptions(directOnly: false, deliveryReceipt: false))
    }

    func data(of message: ReceivedMessage) -> Data { toData(message.payload) }
}

extension KotlinByteArray {
    /// prototype/public-api `KotlinByteArray(data:)`.
    convenience init(perByte data: Data) {
        self.init(size: Int32(data.count))
        for (index, byte) in data.enumerated() {
            set(index: Int32(index), value: Int8(bitPattern: byte))
        }
    }

    /// prototype/public-api `KotlinByteArray.data`.
    var perByteData: Data {
        Data((0..<size).map { UInt8(bitPattern: get(index: $0)) })
    }
}
