package ch.trancee.meshlink.transport

import android.app.Notification
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.crypto.Identity
import ch.trancee.meshlink.power.PowerTier
import ch.trancee.meshlink.storage.InMemorySecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Abstract Android foreground service that owns the [AndroidBleTransport] lifecycle.
 *
 * Subclasses implement [createBleTransportConfig], [createCryptoProvider], and
 * [createForegroundNotification]. The service declares foreground type
 * [ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE] on Android 14+ (API 34); on API 29–33 it
 * calls the two-argument [startForeground] overload.
 *
 * Key storage uses [InMemorySecureStorage] in S02 — EncryptedSharedPreferences persistence is wired
 * in S05.
 *
 * The concrete subclass must be declared in the consuming app's AndroidManifest.xml. No `<service>`
 * element is declared in the library manifest.
 */
abstract class MeshLinkService : Service() {

    // ── Abstract factory methods ─────────────────────────────────────────────

    /** Returns the [BleTransportConfig] used to construct [AndroidBleTransport]. */
    abstract fun createBleTransportConfig(): BleTransportConfig

    /** Returns a [CryptoProvider] implementation (e.g. AndroidCryptoProvider). */
    abstract fun createCryptoProvider(): CryptoProvider

    /**
     * Returns the [Notification] for the foreground service. The notification channel must be
     * created by the subclass before this method is invoked during [onCreate].
     */
    abstract fun createForegroundNotification(): Notification

    // ── State ────────────────────────────────────────────────────────────────

    private lateinit var transport: AndroidBleTransport
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var powerTierFlow: MutableStateFlow<PowerTier>

    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var memoryCallbacks: ComponentCallbacks2? = null

    companion object {
        private const val TAG = "MeshLinkService"

        /** Notification ID used when calling [startForeground]. */
        const val NOTIFICATION_ID = 1001
    }

    // ── Binder ───────────────────────────────────────────────────────────────

    inner class LocalBinder : Binder() {
        /**
         * Returns the running [BleTransport]. Only safe to call after the service has been started
         * and [onCreate] has completed.
         */
        fun getTransport(): BleTransport = transport
    }

    override fun onBind(intent: Intent): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()

        val crypto = createCryptoProvider()
        val config = createBleTransportConfig()
        // S02: InMemorySecureStorage — identity is ephemeral per process lifetime.
        // S05 will wire EncryptedSharedPreferences for persistent identity.
        val identity = Identity.loadOrGenerate(crypto, InMemorySecureStorage())
        powerTierFlow = MutableStateFlow(PowerTier.PERFORMANCE)

        transport =
            AndroidBleTransport(
                context = this,
                config = config,
                cryptoProvider = crypto,
                identity = identity,
                scope = serviceScope,
                powerTierFlow = powerTierFlow,
            )

        // startForeground must be called promptly (within ~5 s on Android 12+).
        val notification = createForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        serviceScope.launch {
            runCatching { transport.startAdvertisingAndScanning() }
                .onFailure { e ->
                    Log.e(TAG, "BLE transport start failed: ${e.message} — stopping service")
                    stopSelf()
                }
        }

        registerThermalListener()
        registerMemoryCallbacks()

        Log.d(TAG, "MeshLinkService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MeshLinkService destroying")

        unregisterThermalListener()
        unregisterMemoryCallbacks()

        if (::transport.isInitialized) {
            // stopAll() has an internal 5 s timeout — runBlocking is bounded here.
            runBlocking { transport.stopAll() }
        }
        serviceScope.cancel()
    }

    // ── Thermal listener (API 29+, always true given minSdk=29) ─────────────

    private fun registerThermalListener() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            if (
                status == PowerManager.THERMAL_STATUS_SEVERE ||
                    status == PowerManager.THERMAL_STATUS_CRITICAL
            ) {
                Log.w(TAG, "Thermal status=$status → POWER_SAVER")
                powerTierFlow.value = PowerTier.POWER_SAVER
            }
        }
        thermalListener = listener
        pm.addThermalStatusListener(listener)
    }

    private fun unregisterThermalListener() {
        thermalListener?.let { listener ->
            runCatching {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                pm.removeThermalStatusListener(listener)
            }
            thermalListener = null
        }
    }

    // ── Memory callbacks ─────────────────────────────────────────────────────

    private fun registerMemoryCallbacks() {
        val callbacks =
            object : ComponentCallbacks2 {
                @Suppress("DEPRECATION")
                override fun onTrimMemory(level: Int) {
                    if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
                        Log.w(TAG, "Memory trim RUNNING_CRITICAL → POWER_SAVER")
                        powerTierFlow.value = PowerTier.POWER_SAVER
                    }
                }

                @Suppress("OverridingDeprecatedMember")
                override fun onConfigurationChanged(newConfig: Configuration) {
                    /* no-op */
                }

                override fun onLowMemory() {
                    /* no-op */
                }
            }
        memoryCallbacks = callbacks
        applicationContext.registerComponentCallbacks(callbacks)
    }

    private fun unregisterMemoryCallbacks() {
        memoryCallbacks?.let { callbacks ->
            runCatching { applicationContext.unregisterComponentCallbacks(callbacks) }
            memoryCallbacks = null
        }
    }
}
