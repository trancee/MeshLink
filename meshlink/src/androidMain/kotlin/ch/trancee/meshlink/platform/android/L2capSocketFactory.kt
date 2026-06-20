package ch.trancee.meshlink.platform.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothSocketSettings
import android.os.Build

@SuppressLint("MissingPermission")
internal object L2capSocketFactory {
    internal fun listenInsecure(
        adapter: BluetoothAdapter,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): BluetoothServerSocket {
        return if (sdkInt >= EXPLICIT_SOCKET_SETTINGS_SDK_INT) {
            adapter.listenUsingSocketSettings(buildExplicitInsecureLeSocketSettings())
        } else {
            adapter.listenUsingInsecureL2capChannel()
        }
    }

    internal fun createInsecure(
        device: BluetoothDevice,
        psm: Int,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): BluetoothSocket {
        return if (sdkInt >= EXPLICIT_SOCKET_SETTINGS_SDK_INT) {
            device.createUsingSocketSettings(buildExplicitInsecureLeSocketSettings(psm))
        } else {
            device.createInsecureL2capChannel(psm)
        }
    }

    internal fun <T> selectInsecureFactory(
        sdkInt: Int,
        explicitFactory: () -> T,
        legacyFactory: () -> T,
    ): T {
        return if (sdkInt >= EXPLICIT_SOCKET_SETTINGS_SDK_INT) {
            explicitFactory()
        } else {
            legacyFactory()
        }
    }

    private fun buildExplicitInsecureLeSocketSettings(psm: Int? = null): BluetoothSocketSettings {
        val builder = BluetoothSocketSettings.Builder()
        builder.setSocketType(BluetoothSocket.TYPE_LE)
        if (psm != null) {
            builder.setL2capPsm(psm)
        }
        builder.setAuthenticationRequired(false)
        builder.setEncryptionRequired(false)
        return builder.build()
    }

    private const val EXPLICIT_SOCKET_SETTINGS_SDK_INT: Int = 36
}
