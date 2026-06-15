package ch.trancee.meshlink.proof.android

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
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

internal object ProofBluetoothContract {
    internal data class Readiness(
        val ready: Boolean,
        val reason: String?,
    )

    fun evaluate(
        bluetoothManagerAvailable: Boolean,
        bluetoothAdapterAvailable: Boolean,
        bluetoothEnabled: Boolean,
    ): Readiness {
        if (!bluetoothManagerAvailable) {
            return Readiness(false, "BluetoothManager is unavailable")
        }
        if (!bluetoothAdapterAvailable) {
            return Readiness(false, "BluetoothAdapter is unavailable")
        }
        if (!bluetoothEnabled) {
            return Readiness(false, "Bluetooth is turned off")
        }
        return Readiness(true, null)
    }

    fun inspect(context: Context): Readiness {
        val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
        return evaluate(
            bluetoothManagerAvailable = bluetoothManager != null,
            bluetoothAdapterAvailable = bluetoothAdapter != null,
            bluetoothEnabled = bluetoothAdapter?.isEnabled == true,
        )
    }
}
