package ch.trancee.meshlink.reference.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager

internal fun readinessGuidance(): List<String> {
    return listOf(
        "Confirm Bluetooth is enabled and the Android device is on API 26 or newer.",
        "Use the debug install path so runtime permissions are granted where the platform allows it.",
        "Keep the device awake during direct proof on aggressive OEM builds, or rely on the " +
            "live-proof foreground wake-lock mitigation when the reference app starts it; doze " +
            "can stall BLE discovery.",
        "Keep the device offline and near the peer before starting the guided exchange.",
    )
}

internal fun readinessBlockers(context: Context): List<String> {
    val missingPermissions =
        requiredPermissions(Build.VERSION.SDK_INT).filterNot { permission ->
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
    return readinessBlockers(
        missingPermissions = missingPermissions,
        powerManagement = powerManagementBlockers(context),
    )
}

internal fun requiredPermissions(sdkInt: Int): List<String> {
    return if (sdkInt >= Build.VERSION_CODES.S) {
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

internal fun readinessBlockers(
    missingPermissions: List<String>,
    powerManagement: List<String> = emptyList(),
): List<String> {
    val blockers = mutableListOf<String>()
    if (missingPermissions.isNotEmpty()) {
        val permissionNames =
            missingPermissions.joinToString(separator = ", ") { permission ->
                permission.substringAfterLast('.')
            }
        blockers +=
            "Grant the required Android BLE permissions before starting MeshLink: $permissionNames."
        blockers +=
            "Some Android devices also require Location permission before BLE scan results become visible."
    }
    blockers += powerManagement
    return blockers
}

private fun powerManagementBlockers(context: Context): List<String> {
    val powerManager = context.powerManagerOrNull()
    val needsMitigation =
        powerManager != null &&
            (powerManager.isDeviceIdleMode ||
                (!powerManager.isInteractive &&
                    !powerManager.isIgnoringBatteryOptimizations(context.packageName)))
    return if (needsMitigation) {
        listOf(
            "Keep the screen awake or disable battery optimization before starting MeshLink " +
                "direct proof; on some Android 14 devices the live-proof foreground wake-lock " +
                "mitigation is still needed to avoid quick-doze discovery stalls."
        )
    } else {
        emptyList()
    }
}

private fun Context.powerManagerOrNull(): PowerManager? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        return null
    }
    return getSystemService(Context.POWER_SERVICE) as? PowerManager
}
