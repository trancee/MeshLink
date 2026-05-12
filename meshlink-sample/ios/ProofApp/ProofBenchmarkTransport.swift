import Foundation

enum ProofBenchmarkTransport {
    case meshLink
    case gattPrototype

    static func parse(_ rawValue: String?) -> ProofBenchmarkTransport {
        switch rawValue?.lowercased() {
        case "gatt", "gattprototype", "gatt-prototype":
            return .gattPrototype
        default:
            return .meshLink
        }
    }

    var logLabel: String {
        switch self {
        case .meshLink:
            return "meshlink"
        case .gattPrototype:
            return "gattPrototype"
        }
    }
}
