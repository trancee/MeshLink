import Foundation
import MeshLink

enum MeshLinkProofTransportBridge {
    static func install() {
        // The real GATT-notify send path already uses native CoreBluetooth
        // types internally, so there is no callback to wire up here — this
        // just tells MeshLink the iPhone-hosted GATT-notify bearer is
        // available.
        BleTransportBridge.shared.enableGattNotifyBearer()
    }
}
