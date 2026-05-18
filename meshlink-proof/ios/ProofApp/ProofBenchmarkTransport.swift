import Foundation

enum ProofBenchmarkTransport {
    case meshLink
    case gattPrototype
    case gattNotifyPrototype

    static func parse(_ rawValue: String?) -> ProofBenchmarkTransport {
        switch rawValue?.lowercased() {
        case "gatt", "gattprototype", "gatt-prototype":
            return .gattPrototype
        case "gatt-notify", "gattnotify", "gattnotifyprototype", "gatt-notify-prototype":
            return .gattNotifyPrototype
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
        case .gattNotifyPrototype:
            return "gattNotifyPrototype"
        }
    }
}
