@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.platform.PlatformPermissionDeniedException
import platform.CoreBluetooth.CBManagerAuthorization
import platform.CoreBluetooth.CBManagerAuthorizationDenied
import platform.CoreBluetooth.CBManagerAuthorizationRestricted

internal object IosBlePermissionContract {
    internal fun ensureBluetoothAuthorized(authorization: CBManagerAuthorization): Unit {
        when (authorization) {
            CBManagerAuthorizationDenied,
            CBManagerAuthorizationRestricted -> {
                throw PlatformPermissionDeniedException("iOS Bluetooth permission denied")
            }
        }
    }
}
