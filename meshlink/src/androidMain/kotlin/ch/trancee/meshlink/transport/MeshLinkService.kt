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
import ch.trancee.meshlink.engine.MeshEngine
import ch.trancee.meshlink.engine.MeshEngineConfig
import ch.trancee.meshlink.power.FixedBatteryMonitor
import ch.trancee.meshlink.power.PowerTier
import ch.trancee.meshlink.storage.AndroidSecureStorage
import ch.trancee.meshlink.storage.InMemorySecureStorage
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Abstract Android foreground service that owns the [AndroidBleTransport] and [MeshEngine]
 * lifecycle.
 *
 * Subclasses implement [createBleTransportConfig], [createCryptoProvider], and
 * [createForegroundNotification]. Optionally override [createMeshEngineConfig] to supply
 * subsystem-specific tuning (e.g. shortened routing timers for test harnesses).
 *
 * The service declares foreground type [ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE] on
 * Android 14+ (API 34); on API 29–33 it calls the two-argument [startForeground] overload.
 *
 * Key storage uses [InMemorySecureStorage] in S02/S04 — EncryptedSharedPreferences persistence is
 * wired in S05.
 *
 * The concrete subclass must be declared in the consuming app's AndroidManifest.xml. No `<service>`
 * element is declared in the library manifest.
 */
internal abstract class MeshLinkService : Service() {

    // ── Abstract factory methods ─────────────────────────────────────────────

    /** Returns the [BleTransportConfig] used to construct [AndroidBleTransport]. */
    internal abstract fun createBleTransportConfig(): BleTransportConfig

    /** Returns a [CryptoProvider] implementation (e.g. AndroidCryptoProvider). */
    internal abstract fun createCryptoProvider(): CryptoProvider

    /**
     * Returns the [Notification] for the foreground service. The notification channel must be
     * created by the subclass before this method is invoked during [onCreate].
     */
    internal abstract fun createForegroundNotification(): Notification

    /**
     * Returns the [MeshEngineConfig] used to construct [MeshEngine].
     *
     * Override in subclasses to supply custom subsystem tuning — e.g. shortened routing timers for
     * integration test harnesses. Defaults to [MeshEngineConfig] with all defaults.
     */
    internal open fun createMeshEngineConfig(): MeshEngineConfig = MeshEngineConfig()

    // ── State ────────────────────────────────────────────────────────────────

    private lateinit var transport: AndroidBleTransport
    private lateinit var meshEngine: MeshEngine
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var powerTierFlow: MutableStateFlow<PowerTier>

    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var memoryCallbacks: ComponentCallbacks2? = null

    companion object {
        private const val TAG = "MeshLinkService"

        /** Notification ID used when calling [startForeground]. */
        internal const val NOTIFICATION_ID = 1001
    }

    // ── Binder ───────────────────────────────────────────────────────────────

    internal inner class LocalBinder : Binder() {
        /**
         * Returns the running [BleTransport]. Only safe to call after the service has been started
         * and [onCreate] has completed.
         */
        internal fun getTransport(): BleTransport = transport

        /**
         * Returns the running [MeshEngine]. Only safe to call after the service has been started
         * and [onCreate] has completed.
         */
        internal fun getEngine(): MeshEngine = meshEngine
    }

    override fun onBind(intent: Intent): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()

        val crypto = createCryptoProvider()
        val config = createBleTransportConfig()

        // S05: Persistent identity storage via EncryptedSharedPreferences.
        // isFirstLaunch is checked BEFORE Identity.loadOrGenerate() writes identity keys.
        val storage = AndroidSecureStorage(this)
        val isFirstLaunch = !storage.contains("meshlink.identity.ed25519.public")
        Log.d(TAG, "MeshLinkService storage ready isFirstLaunch=$isFirstLaunch")

        val identity = Identity.loadOrGenerate(crypto, storage)
        if (isFirstLaunch) {
            Log.d(TAG, "Identity generated and persisted to EncryptedSharedPreferences")
        } else {
            Log.d(TAG, "Identity loaded from EncryptedSharedPreferences")
        }

        // Restore OEM L2CAP probe cache from persistent storage.
        val probeCache = OemL2capProbeCache()
        probeCache.restore(storage)
        Log.d(TAG, "OemL2capProbeCache restored entries=${probeCache.size}")

        // Construct OEM slot tracker backed by persistent storage.
        val oemSlotTracker =
            OemSlotTracker(storage, config.maxConnections) { System.currentTimeMillis() }
        Log.d(TAG, "OemSlotTracker constructed maxConnections=${config.maxConnections}")

        powerTierFlow = MutableStateFlow(PowerTier.PERFORMANCE)

        transport =
            AndroidBleTransport(
                context = this,
                config = config,
                cryptoProvider = crypto,
                identity = identity,
                scope = serviceScope,
                powerTierFlow = powerTierFlow,
                probeCache = probeCache,
                oemSlotTracker = oemSlotTracker,
                bootstrapMode = isFirstLaunch,
            )

        // MeshEngine is created inside :meshlink where internal types (Identity, SecureStorage,
        // AndroidBleTransport) are accessible. MeshEngine.create() is internal.
        // A separate InMemorySecureStorage is used for engine key material so it is independent
        // of the identity storage above.
        val monoOrigin = TimeSource.Monotonic.markNow()
        meshEngine =
            MeshEngine.create(
                identity = identity,
                cryptoProvider = crypto,
                transport = transport,
                storage = InMemorySecureStorage(),
                batteryMonitor = FixedBatteryMonitor(1.0f),
                scope = serviceScope,
                clock = { monoOrigin.elapsedNow().inWholeMilliseconds },
                config = createMeshEngineConfig(),
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

        // MeshEngine.start() handles transport.startAdvertisingAndScanning() internally.
        serviceScope.launch {
            runCatching { meshEngine.start() }
                .onFailure { e ->
                    Log.e(TAG, "MeshEngine start failed: ${e.message} — stopping service")
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

        if (::meshEngine.isInitialized) {
            // meshEngine.stop() cancels the engine scope and calls transport.stopAll().
            // Bounded by the engine's internal 5 s timeout.
            runBlocking { meshEngine.stop() }
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
