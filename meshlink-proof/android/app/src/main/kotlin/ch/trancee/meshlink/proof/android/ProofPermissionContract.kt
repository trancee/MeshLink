package ch.trancee.meshlink.proof.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

internal object ProofPermissionContract {
    internal const val REQUEST_PERMISSIONS_CODE: Int = 1001

    fun requiredPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= 31) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun missingPermissions(context: Context): List<String> {
        return requiredPermissions().filterNot { permission ->
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isRequestGranted(grantResults: IntArray): Boolean {
        return grantResults.isNotEmpty() &&
            grantResults.all { result -> result == PackageManager.PERMISSION_GRANTED }
    }
}
