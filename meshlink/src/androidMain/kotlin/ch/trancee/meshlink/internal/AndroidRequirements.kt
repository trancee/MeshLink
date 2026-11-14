package ch.trancee.meshlink.internal

import android.Manifest
import android.app.Activity
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Build
import android.os.Bundle
import ch.trancee.meshlink.PermissionKind
import ch.trancee.meshlink.Requirement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the Android radio is missing (ADR 0007 decision 3): no Bluetooth LE or no multiple
 * advertisement support ([Requirement.Unsupported]), a runtime permission the API level needs, or
 * Bluetooth turned off. Location services being off is not detected.
 */
internal class AndroidRequirements(
    private val context: Context,
    private val adapter: BluetoothAdapter?,
) {
    private val missing = MutableStateFlow(current())

    /** What is missing now. */
    val requirements: StateFlow<Set<Requirement>> = missing

    /** Checks again, after something that may have changed the answer. */
    fun refresh() {
        missing.value = current()
    }

    private fun current(): Set<Requirement> {
        val le = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        return if (adapter == null || !le) setOf(Requirement.Unsupported) else present(adapter)
    }

    private fun present(adapter: BluetoothAdapter): Set<Requirement> =
        buildSet {
            val denied = neededPermissions(Build.VERSION.SDK_INT).filterValues { names -> names.none(::granted) }
            denied.keys.mapTo(this, Requirement::PermissionDenied)
            when {
                !adapter.isEnabled -> add(Requirement.BluetoothOff)
                !adapter.isMultipleAdvertisementSupported -> add(Requirement.Unsupported)
            }
        }

    private fun granted(name: String): Boolean = context.checkSelfPermission(name) == PERMISSION_GRANTED
}

/** Each permission kind API level [sdk] needs for Bluetooth LE, met by any one of its permissions. */
internal fun neededPermissions(sdk: Int): Map<PermissionKind, List<String>> =
    when {
        sdk >= Build.VERSION_CODES.S -> {
            mapOf(
                PermissionKind.BluetoothScan to listOf(Manifest.permission.BLUETOOTH_SCAN),
                PermissionKind.BluetoothAdvertise to listOf(Manifest.permission.BLUETOOTH_ADVERTISE),
                PermissionKind.BluetoothConnect to listOf(Manifest.permission.BLUETOOTH_CONNECT),
            )
        }

        sdk >= Build.VERSION_CODES.Q -> {
            mapOf(PermissionKind.Location to listOf(Manifest.permission.ACCESS_FINE_LOCATION))
        }

        else -> {
            mapOf(
                PermissionKind.Location to
                    listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            )
        }
    }

/**
 * Refreshes [requirements] when Bluetooth turns on or off and when an activity resumes, which is
 * when a permission the user just granted takes effect; closes [radio]'s server when Bluetooth turns
 * off. Revoking a permission restarts the app process, so it needs no watching.
 */
internal class RequirementsWatcher(
    private val requirements: AndroidRequirements,
    private val radio: AndroidRadio?,
) : BroadcastReceiver(),
    Application.ActivityLifecycleCallbacks {
    /** Starts watching from [context]'s application. */
    fun register(context: Context) {
        val app = context.applicationContext
        // ACTION_STATE_CHANGED is a protected system broadcast, so it needs no export flag.
        app.registerReceiver(this, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        (app as? Application)?.registerActivityLifecycleCallbacks(this)
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_OFF) {
            radio?.bluetoothOff()
        }
        requirements.refresh()
    }

    override fun onActivityResumed(activity: Activity) = requirements.refresh()

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
