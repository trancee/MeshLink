package ch.trancee.meshlink.platform.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build

// Shared by registerBluetoothStateChangeReceiver() and registerBackgroundScanReceiver() -- both
// dynamically (Context.registerReceiver, never manifest-declared) register a short-lived
// BroadcastReceiver scoped to this process for the lifetime of a single BleTransportAdapter
// start()/stop() cycle, and both need the same API-33+ exported-flag handling.

/**
 * Registers [receiver] for [action] using [Context.RECEIVER_NOT_EXPORTED] on API 33+ -- required
 * from API 34 onward for every context-registered receiver, and correct/intentional here even where
 * merely recommended: these receivers only ever observe broadcasts this same process sends to
 * itself (a protected system broadcast, or an explicit same-package Intent), never ones meant to be
 * reachable by other apps.
 */
internal fun Context.registerDynamicReceiver(receiver: BroadcastReceiver, action: String): Unit {
    val filter = IntentFilter(action)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(receiver, filter)
    }
}

/**
 * Unregisters [receiver], swallowing any exception (e.g. it was already unregistered, or the owning
 * [Context] is already torn down) -- teardown must not throw partway through `stopTransports()`.
 */
internal fun Context.unregisterDynamicReceiverQuietly(receiver: BroadcastReceiver): Unit {
    runCatching { unregisterReceiver(receiver) }
}
