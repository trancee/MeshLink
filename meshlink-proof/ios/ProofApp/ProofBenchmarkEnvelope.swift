import Foundation

final class PendingBenchmarkReceipt {
    private var continuation: CheckedContinuation<BenchmarkReceiptEnvelope?, Never>?
    private var resolved = false

    init(continuation: CheckedContinuation<BenchmarkReceiptEnvelope?, Never>) {
        self.continuation = continuation
    }

    func resolve(_ receipt: BenchmarkReceiptEnvelope?) {
        guard !resolved, let continuation else {
            return
        }
        resolved = true
        self.continuation = nil
        continuation.resume(returning: receipt)
    }
}

struct BenchmarkPayloadEnvelope {
    static let magic = Array("MLBM1000".utf8)
    static let headerBytes = 16

    let totalBytes: Int
    let tokenHex: String

    init(totalBytes: Int, tokenHex: String) {
        precondition(totalBytes >= Self.headerBytes, "Benchmark payload must be at least \(Self.headerBytes) bytes")
        precondition(tokenHex.count == 16, "Benchmark token must be 16 hex characters")
        self.totalBytes = totalBytes
        self.tokenHex = tokenHex
    }

    func encode() -> Data {
        var bytes = [UInt8](unsafeUninitializedCapacity: totalBytes) { buffer, count in
            for index in 0..<totalBytes {
                buffer[index] = UInt8((index * 31) & 0xFF)
            }
            count = totalBytes
        }
        Self.magic.enumerated().forEach { index, byte in
            bytes[index] = byte
        }
        tokenHex.toBytes().enumerated().forEach { index, byte in
            bytes[Self.magic.count + index] = byte
        }
        return Data(bytes)
    }

    static func decode(_ data: Data) -> BenchmarkPayloadEnvelope? {
        guard data.count >= Self.headerBytes else {
            return nil
        }
        let bytes = [UInt8](data)
        guard Array(bytes.prefix(Self.magic.count)) == Self.magic else {
            return nil
        }
        let tokenBytes = bytes[Self.magic.count..<Self.headerBytes]
        let tokenHex = tokenBytes.map { String(format: "%02x", $0) }.joined()
        return BenchmarkPayloadEnvelope(totalBytes: data.count, tokenHex: tokenHex)
    }
}

struct BenchmarkReceiptEnvelope {
    static let prefix = "MLBM1_ACK:"

    let tokenHex: String
    let totalBytes: Int

    func encode() -> Data {
        Data("\(Self.prefix)\(tokenHex):\(totalBytes)".utf8)
    }

    static func decode(_ data: Data) -> BenchmarkReceiptEnvelope? {
        guard let text = String(data: data, encoding: .utf8), text.hasPrefix(Self.prefix) else {
            return nil
        }
        let payload = text.dropFirst(Self.prefix.count)
        let parts = payload.split(separator: ":", maxSplits: 1).map(String.init)
        guard parts.count == 2, let totalBytes = Int(parts[1]) else {
            return nil
        }
        return BenchmarkReceiptEnvelope(tokenHex: parts[0], totalBytes: totalBytes)
    }
}

private extension String {
    func toBytes() -> [UInt8] {
        toBytesOrNil() ?? []
    }

    func toBytesOrNil() -> [UInt8]? {
        guard count.isMultiple(of: 2) else {
            return nil
        }
        var output: [UInt8] = []
        output.reserveCapacity(count / 2)
        var index = startIndex
        while index < endIndex {
            let next = self.index(index, offsetBy: 2)
            guard let value = UInt8(self[index..<next], radix: 16) else {
                return nil
            }
            output.append(value)
            index = next
        }
        return output
    }
}
