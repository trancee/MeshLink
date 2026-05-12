package ch.trancee.meshlink.platform.android

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Build

internal object AndroidL2capSocketFactory {
    internal fun listenInsecure(
        adapter: BluetoothAdapter,
        sdkInt: Int = Build.VERSION.SDK_INT,
        onExplicitFailure: ((Throwable) -> Unit)? = null,
    ): BluetoothServerSocket {
        return selectInsecureFactory(
            sdkInt = sdkInt,
            explicitFactory = { listenWithExplicitSocketSettings(adapter) },
            legacyFactory = { adapter.listenUsingInsecureL2capChannel() },
            onExplicitFailure = onExplicitFailure,
        )
    }

    internal fun createInsecure(
        device: BluetoothDevice,
        psm: Int,
        sdkInt: Int = Build.VERSION.SDK_INT,
        onExplicitFailure: ((Throwable) -> Unit)? = null,
    ): BluetoothSocket {
        return selectInsecureFactory(
            sdkInt = sdkInt,
            explicitFactory = { createWithExplicitSocketSettings(device, psm) },
            legacyFactory = { device.createInsecureL2capChannel(psm) },
            onExplicitFailure = onExplicitFailure,
        )
    }

    internal fun <T> selectInsecureFactory(
        sdkInt: Int,
        explicitFactory: () -> T,
        legacyFactory: () -> T,
        onExplicitFailure: ((Throwable) -> Unit)? = null,
    ): T {
        if (sdkInt < EXPLICIT_SOCKET_SETTINGS_SDK_INT) {
            return legacyFactory()
        }
        return runCatching(explicitFactory).getOrElse { error ->
            onExplicitFailure?.invoke(error)
            legacyFactory()
        }
    }

    private fun listenWithExplicitSocketSettings(adapter: BluetoothAdapter): BluetoothServerSocket {
        val settingsClass = Class.forName(BLUETOOTH_SOCKET_SETTINGS_CLASS_NAME)
        val method =
            BluetoothAdapter::class.java.getMethod("listenUsingSocketSettings", settingsClass)
        return method.invoke(adapter, buildExplicitInsecureLeSocketSettings())
            as BluetoothServerSocket
    }

    private fun createWithExplicitSocketSettings(
        device: BluetoothDevice,
        psm: Int,
    ): BluetoothSocket {
        val settingsClass = Class.forName(BLUETOOTH_SOCKET_SETTINGS_CLASS_NAME)
        val method =
            BluetoothDevice::class.java.getMethod("createUsingSocketSettings", settingsClass)
        return method.invoke(device, buildExplicitInsecureLeSocketSettings(psm)) as BluetoothSocket
    }

    private fun buildExplicitInsecureLeSocketSettings(psm: Int? = null): Any {
        val builderClass = Class.forName("$BLUETOOTH_SOCKET_SETTINGS_CLASS_NAME\$Builder")
        val builder = builderClass.getDeclaredConstructor().newInstance()
        val typeLe = BluetoothSocket::class.java.getField("TYPE_LE").getInt(null)
        builderClass
            .getMethod("setSocketType", Int::class.javaPrimitiveType)
            .invoke(builder, typeLe)
        if (psm != null) {
            builderClass.getMethod("setL2capPsm", Int::class.javaPrimitiveType).invoke(builder, psm)
        }
        builderClass
            .getMethod("setAuthenticationRequired", Boolean::class.javaPrimitiveType)
            .invoke(builder, false)
        builderClass
            .getMethod("setEncryptionRequired", Boolean::class.javaPrimitiveType)
            .invoke(builder, false)
        return checkNotNull(builderClass.getMethod("build").invoke(builder)) {
            "BluetoothSocketSettings.Builder.build() returned null"
        }
    }

    private const val EXPLICIT_SOCKET_SETTINGS_SDK_INT: Int = 36
    private const val BLUETOOTH_SOCKET_SETTINGS_CLASS_NAME: String =
        "android.bluetooth.BluetoothSocketSettings"
}
