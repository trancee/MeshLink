package ch.trancee.meshlink.platform.android.gatt

import android.os.Build

internal fun <T> connectGattSession(
    sdkInt: Int,
    leTransportFactory: () -> T,
    legacyFactory: () -> T,
): T {
    return if (sdkInt >= Build.VERSION_CODES.M) {
        leTransportFactory()
    } else {
        legacyFactory()
    }
}
