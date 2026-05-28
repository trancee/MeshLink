package ch.trancee.meshlink.reference.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

internal fun readinessGuidance(): List<String> {
    return listOf(
        "Confirm Bluetooth is enabled and the Android device is on API 29 or newer.",
        "Use the debug install path so runtime permissions are granted where the platform allows it.",
        "Keep the device offline and near the iPhone peer before starting the guided exchange.",
    )
}

internal fun readinessBlockers(context: Context): List<String> {
    val missingPermissions =
        requiredPermissions(Build.VERSION.SDK_INT).filterNot { permission ->
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
    return readinessBlockers(missingPermissions)
}

internal fun requiredPermissions(sdkInt: Int): List<String> {
    return if (sdkInt >= 31) {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

internal fun readinessBlockers(missingPermissions: List<String>): List<String> {
    if (missingPermissions.isEmpty()) {
        return emptyList()
    }
    val permissionNames =
        missingPermissions.joinToString(separator = ", ") { permission ->
            permission.substringAfterLast('.')
        }
    return listOf(
        "Grant the required Android BLE permissions before starting MeshLink: $permissionNames.",
        "Some Android devices also require Location permission before BLE scan results become visible.",
    )
}
