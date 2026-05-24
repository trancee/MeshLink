package ch.trancee.meshlink.platform.android

import android.os.Build

internal fun <T> connectAndroidGattSession(
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
