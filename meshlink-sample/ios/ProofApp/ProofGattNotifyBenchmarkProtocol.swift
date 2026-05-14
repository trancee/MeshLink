import CoreBluetooth
import Foundation

enum ProofGattNotifyBenchmarkProtocol {
    static let serviceUUID = CBUUID(string: "4d455348-1011-1000-8000-000000000000")
    static let appHashCharacteristicUUID = CBUUID(string: "4d455348-1012-1000-8000-000000000000")
    static let notifyCharacteristicUUID = CBUUID(string: "4d455348-1013-1000-8000-000000000000")
    static let ackCharacteristicUUID = CBUUID(string: "4d455348-1014-1000-8000-000000000000")

    private static let startFrameType: UInt8 = 0x01
    private static let dataFrameType: UInt8 = 0x02
    private static let ackFrameType: UInt8 = 0x11
    private static let tokenBytes = 8
    private static let preferredNotificationFrameBytes = 495

    struct StartFrame {
        let tokenHex: String
        let totalBytes: Int
    }

    struct DataFrame {
        let tokenHex: String
        let chunk: Data
    }

    struct AckFrame {
        let tokenHex: String
        let totalBytes: Int
    }

    static func appHashData(appId: String) -> Data {
        let appHash = foldedAppHash(appId)
        return Data([
            UInt8(appHash & 0x00ff),
            UInt8((appHash & 0xff00) >> 8),
        ])
    }

    static func matchesAppHash(_ data: Data?, appId: String) -> Bool {
        data == appHashData(appId: appId)
    }

    static func encodeStart(tokenHex: String, totalBytes: Int) -> Data {
        var data = Data([startFrameType])
        data.append(tokenData(tokenHex))
        appendUInt32(UInt32(totalBytes), to: &data)
        return data
    }

    static func encodeData(tokenHex: String, chunk: Data) -> Data {
        var data = Data([dataFrameType])
        data.append(tokenData(tokenHex))
        data.append(chunk)
        return data
    }

    static func encodeAck(tokenHex: String, totalBytes: Int) -> Data {
        var data = Data([ackFrameType])
        data.append(tokenData(tokenHex))
        appendUInt32(UInt32(totalBytes), to: &data)
        return data
    }

    static func decodeNotify(_ data: Data) -> Any? {
        guard let type = data.first else {
            return nil
        }
        switch type {
        case startFrameType:
            return decodeStart(data)
        case dataFrameType:
            return decodeData(data)
        default:
            return nil
        }
    }

    static func decodeAck(_ data: Data) -> AckFrame? {
        guard data.count == 1 + tokenBytes + MemoryLayout<UInt32>.size, data.first == ackFrameType else {
            return nil
        }
        let tokenHex = hexToken(from: data.subdata(in: 1..<(1 + tokenBytes)))
        let totalBytes = data[(1 + tokenBytes)...].reduce(0) { partial, next in
            (partial << 8) | Int(next)
        }
        return AckFrame(tokenHex: tokenHex, totalBytes: totalBytes)
    }

    static func chunkPayloadBytes(maxUpdateValueLength: Int) -> Int {
        let maxFrameBytes = min(preferredNotificationFrameBytes, maxUpdateValueLength)
        return max(1, maxFrameBytes - (1 + tokenBytes))
    }

    private static func decodeStart(_ data: Data) -> StartFrame? {
        guard data.count == 1 + tokenBytes + MemoryLayout<UInt32>.size else {
            return nil
        }
        let tokenHex = hexToken(from: data.subdata(in: 1..<(1 + tokenBytes)))
        let totalBytes = data[(1 + tokenBytes)...].reduce(0) { partial, next in
            (partial << 8) | Int(next)
        }
        guard totalBytes > 0 else {
            return nil
        }
        return StartFrame(tokenHex: tokenHex, totalBytes: totalBytes)
    }

    private static func decodeData(_ data: Data) -> DataFrame? {
        guard data.count > 1 + tokenBytes else {
            return nil
        }
        let tokenHex = hexToken(from: data.subdata(in: 1..<(1 + tokenBytes)))
        let chunk = data.subdata(in: (1 + tokenBytes)..<data.count)
        return DataFrame(tokenHex: tokenHex, chunk: chunk)
    }

    private static func tokenData(_ tokenHex: String) -> Data {
        precondition(tokenHex.count == tokenBytes * 2, "Expected a 16-character benchmark token")
        return Data(stride(from: 0, to: tokenHex.count, by: 2).map { index in
            let start = tokenHex.index(tokenHex.startIndex, offsetBy: index)
            let end = tokenHex.index(start, offsetBy: 2)
            return UInt8(tokenHex[start..<end], radix: 16) ?? 0
        })
    }

    private static func hexToken(from data: Data) -> String {
        data.map { byte in String(format: "%02x", byte) }.joined()
    }

    private static func appendUInt32(_ value: UInt32, to data: inout Data) {
        data.append(UInt8((value >> 24) & 0xff))
        data.append(UInt8((value >> 16) & 0xff))
        data.append(UInt8((value >> 8) & 0xff))
        data.append(UInt8(value & 0xff))
    }

    private static func foldedAppHash(_ appId: String) -> UInt16 {
        let hash = fnv1a32(Array(appId.utf8))
        let folded = ((hash >> 16) ^ (hash & 0xffff)) & 0xffff
        return UInt16(folded == 0 ? 1 : folded)
    }

    private static func fnv1a32(_ bytes: [UInt8]) -> UInt32 {
        var hash: UInt32 = 0x811c9dc5
        for byte in bytes {
            hash ^= UInt32(byte)
            hash &*= 0x01000193
        }
        return hash
    }
}
