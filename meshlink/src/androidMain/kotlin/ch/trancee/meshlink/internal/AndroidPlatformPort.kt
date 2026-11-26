package ch.trancee.meshlink.internal

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import ch.trancee.meshlink.Limitation
import ch.trancee.meshlink.Requirement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

/**
 * The Android platform: the Bluetooth LE radio of [context]'s app, or a radio that refuses every
 * operation on a device without Bluetooth. The identity's seed persists in [KeystoreSlot], sealed by an
 * Android Keystore key.
 */
internal class AndroidPlatformPort(
    context: Context,
    override val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : PlatformPort {
    private val app = context.applicationContext
    private val manager = app.getSystemService(BluetoothManager::class.java)
    private val adapter = manager?.adapter
    private val checks = AndroidRequirements(app, adapter)
    private val androidRadio =
        adapter?.let { AndroidRadio(app, manager, it, CoroutineScope(SupervisorJob() + dispatcher)) }

    override val requirements: StateFlow<Set<Requirement>> = checks.requirements

    override val limitations: StateFlow<Set<Limitation>> = checks.limitations

    override val identityStore: IdentityStore = PersistentIdentityStore(KeystoreSlot.of(app))

    override val radio: RadioPort = androidRadio ?: UnsupportedRadio

    override val clock: Clock = SystemClock

    override val random: RandomSource = SystemRandom

    override val log: PlatformLog = AndroidLog

    override val platformName: String = "Android"

    override val osVersion: String = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    init {
        RequirementsWatcher(checks, androidRadio).register(app)
    }
}
