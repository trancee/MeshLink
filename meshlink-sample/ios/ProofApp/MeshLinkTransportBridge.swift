import CoreBluetooth
import Foundation
import MeshLink

enum MeshLinkProofTransportBridge {
    static func install() {
        IosBleTransportBridge.shared.install(
            gattNotifySend: { peripheralManagerHandle, notifyCharacteristicHandle, centralHandle, payload in
                guard
                    let peripheralManager = peripheralManagerHandle as? CBPeripheralManager,
                    let notifyCharacteristic = notifyCharacteristicHandle as? CBMutableCharacteristic,
                    let central = centralHandle as? CBCentral
                else {
                    return KotlinBoolean(bool: false)
                }
                let send = {
                    peripheralManager.updateValue(
                        payload.toData(),
                        for: notifyCharacteristic,
                        onSubscribedCentrals: [central]
                    )
                }
                let accepted: Bool
                if Thread.isMainThread {
                    accepted = send()
                } else {
                    accepted = DispatchQueue.main.sync { send() }
                }
                return KotlinBoolean(bool: accepted)
            }
        )
    }
}

private extension KotlinByteArray {
    func toData() -> Data {
        var bytes = [UInt8](repeating: 0, count: Int(size))
        for index in 0..<Int(size) {
            bytes[index] = UInt8(bitPattern: get(index: Int32(index)))
        }
        return Data(bytes)
    }
}
