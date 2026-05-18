import CoreBluetooth
import Foundation

enum ProofGattBenchmarkProtocol {
    static let serviceUUID = CBUUID(string: "4d455348-1001-1000-8000-000000000000")
    static let writeCharacteristicUUID = CBUUID(string: "4d455348-1002-1000-8000-000000000000")
    static let ackCharacteristicUUID = CBUUID(string: "4d455348-1003-1000-8000-000000000000")
    static let manufacturerPrefix = Data([0xff, 0xff])

    private static let advertisementVersion: UInt8 = 1
    private static let startFrameType: UInt8 = 0x01
    private static let dataFrameType: UInt8 = 0x02
    private static let ackFrameType: UInt8 = 0x11
    private static let tokenBytes = 8

    struct AckFrame {
        let tokenHex: String
        let totalBytes: Int
    }

    static func advertisementTag(appId: String) -> Data {
        let appHash = foldedAppHash(appId)
        return Data([
            advertisementVersion,
            UInt8(appHash & 0x00ff),
            UInt8((appHash & 0xff00) >> 8),
        ])
    }

    static func matchesAdvertisement(_ data: Data?, appId: String) -> Bool {
        var expected = manufacturerPrefix
        expected.append(advertisementTag(appId: appId))
        return data == expected
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

    static func chunkPayloadBytes(maxWriteValueLength: Int) -> Int {
        max(1, maxWriteValueLength - (1 + tokenBytes))
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
