package ch.trancee.meshlink.reference

import android.content.Context
import android.os.PowerManager

internal fun Context.isDeviceInteractive(): Boolean {
    val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT_WATCH) {
        powerManager.isInteractive
    } else {
        @Suppress("DEPRECATION") powerManager.isScreenOn
    }
}

internal fun Context.isPowerSaveMode(): Boolean {
    val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
        powerManager.isPowerSaveMode
    } else {
        false
    }
}
