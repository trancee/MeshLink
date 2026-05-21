package ch.trancee.meshlink.platform.android

import android.Manifest
import android.annotation.SuppressLint
import ch.trancee.meshlink.platform.PlatformPermissionDeniedException

internal object AndroidBlePermissionContract {
    @SuppressLint("InlinedApi")
    internal fun requiredPermissions(sdkInt: Int): List<String> {
        return if (sdkInt >= 31) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    internal fun ensureRequiredPermissionsGranted(
        sdkInt: Int,
        isGranted: (String) -> Boolean,
    ): Unit {
        val missingPermissions = requiredPermissions(sdkInt).filterNot(isGranted)
        if (missingPermissions.isNotEmpty()) {
            throw PlatformPermissionDeniedException(
                message =
                    "Android BLE permissions denied: ${missingPermissions.joinToString(separator = ", ")}"
            )
        }
    }
}
