import CoreBluetooth
import Foundation
import ReferenceAppShared

enum MeshLinkReferenceTransportBridge {
    static func install() {
        IosBleTransportBridge.shared.installData(
            gattNotifySendData: { peripheralManagerHandle, notifyCharacteristicHandle, centralHandle, payloadDataHandle in
                guard
                    let peripheralManager = peripheralManagerHandle as? CBPeripheralManager,
                    let notifyCharacteristic = notifyCharacteristicHandle as? CBMutableCharacteristic,
                    let central = centralHandle as? CBCentral,
                    let payload = Self.resolvePayloadData(payloadDataHandle)
                else {
                    return KotlinBoolean(bool: false)
                }
                let send = {
                    peripheralManager.updateValue(
                        payload,
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

    private static func resolvePayloadData(_ payloadDataHandle: Any) -> Data? {
        if let data = payloadDataHandle as? Data {
            return data
        }
        if let nsData = payloadDataHandle as? NSData {
            return nsData as Data
        }
        return nil
    }
}
