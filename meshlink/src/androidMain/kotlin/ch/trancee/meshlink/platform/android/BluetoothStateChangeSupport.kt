package ch.trancee.meshlink.platform.android

import android.bluetooth.BluetoothAdapter

// Delay after BluetoothAdapter.STATE_ON before restarting discovery. Restarting immediately on
// every ACTION_STATE_CHANGED is the single most common trigger for Android's undocumented
// "5 scan/advertise restarts per 30s" throttle (see android-17-ble-migration skill), particularly
// because the OS itself can deliver more than one STATE_ON-adjacent transition in quick succession
// while the radio finishes powering back up. Debouncing (delay, and only act on the most recent
// toggle) keeps a user flipping Bluetooth off/on from tripping that throttle on its own.
internal const val BLUETOOTH_STATE_CHANGE_RESTART_DEBOUNCE_MILLIS = 1_500L

/**
 * Pure, host-testable debounce logic for [BluetoothAdapter.ACTION_STATE_CHANGED]. Separated from
 * the actual `BroadcastReceiver`/`registerReceiver` platform glue (see
 * [BleTransportAdapter.registerBluetoothStateChangeReceiver]) so the debounce behavior itself is
 * directly unit-testable without a real `BroadcastReceiver` or `Context`.
 *
 * Only [BluetoothAdapter.STATE_ON] schedules a restart -- there is nothing productive to restart on
 * [BluetoothAdapter.STATE_OFF]/`STATE_TURNING_OFF`/`STATE_TURNING_ON`, since the existing
 * scan/advertise-start failure paths already handle a radio that is off or mid-transition when a
 * start is attempted.
 */
internal class BluetoothStateChangeDebouncer(
    private val restartDelayMillis: Long = BLUETOOTH_STATE_CHANGE_RESTART_DEBOUNCE_MILLIS,
    private val scheduleRestart: (delayMillis: Long, restart: () -> Unit) -> Unit,
    private val log: (String) -> Unit,
) {
    // Incremented on every STATE_ON transition observed; a scheduled restart only actually runs if
    // its own generation is still the latest one by the time its delay elapses, so rapid
    // off/on/off/on toggling collapses into a single restart after the last transition rather than
    // firing once per toggle.
    private var generation: Int = 0

    fun onStateChanged(state: Int, restartDiscovery: () -> Unit): Unit {
        if (state != BluetoothAdapter.STATE_ON) {
            return
        }
        generation += 1
        val scheduledGeneration = generation
        log("bluetooth state changed to STATE_ON; scheduling debounced discovery restart")
        scheduleRestart(restartDelayMillis) {
            if (scheduledGeneration != generation) {
                log("bluetooth state change restart superseded; skipping")
                return@scheduleRestart
            }
            log("bluetooth state change restart invoked")
            restartDiscovery()
        }
    }
}
