import Foundation
import MeshLinkKit

public struct TimedOut: Error {
    public init() {}
}

/// Runs `operation`, failing with `TimedOut` after `seconds`.
public func within<T>(_ seconds: Double = 5, _ operation: @escaping () async throws -> T) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask { try await operation() }
        group.addTask {
            try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            throw TimedOut()
        }
        defer { group.cancelAll() }
        return try await group.next()!
    }
}

/// First element of `sequence` matching `predicate`, or `TimedOut`.
public func first<S: AsyncSequence>(
    _ sequence: S,
    timeout seconds: Double = 5,
    where predicate: @escaping (S.Element) -> Bool = { _ in true }
) async throws -> S.Element {
    try await within(seconds) {
        for try await element in sequence where predicate(element) {
            return element
        }
        throw TimedOut()
    }
}
