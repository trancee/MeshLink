package ch.trancee.meshlink.internal

import ch.trancee.meshlink.Limitation
import ch.trancee.meshlink.Requirement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The port of an iPhone or iPad: the CoreBluetooth radio [AppleRadio] over [bluetooth]. The
 * [dispatcher] runs on the serial queue CoreBluetooth calls back on, so the radio's state needs no
 * lock. The identity's seed lives in the item of [keychain]. [osVersion] is `major.minor.patch`.
 * [lifecycle] tells when the app moves to the background and how long iOS suspended it.
 *
 * The host app must declare `NSBluetoothAlwaysUsageDescription` in its `Info.plist`; iOS shows the
 * Bluetooth prompt when a node first starts, never on `create()`.
 */
internal class ApplePlatformPort(
    bluetooth: CoreBluetooth,
    keychain: Keychain,
    override val dispatcher: CoroutineDispatcher,
    override val osVersion: String,
    lifecycle: AppLifecycle,
    osLog: OsLog,
) : PlatformPort {
    private val apple = AppleRadio(bluetooth)

    override val requirements: Flow<Set<Requirement>> = apple.requirements

    override val limitations: Flow<Set<Limitation>> = flowOf(emptySet())

    override val background: Flow<Boolean> = lifecycle.background

    override val doze: Flow<Boolean> = flowOf(false)

    override val suspensions: Flow<Long> = suspensions(lifecycle::continuousMillis)

    override val identityStore: IdentityStore = PersistentIdentityStore(KeychainSlot(keychain))

    override val radio: RadioPort = apple

    override val clock: Clock = SystemClock

    override val random: RandomSource = SystemRandom

    override val log: PlatformLog = AppleLog(osLog)

    override val platformName: String = "iOS"
}

/** What iOS tells of the app's lifecycle. */
internal interface AppLifecycle {
    /** Whether the app runs in the background, now and on each change. */
    val background: Flow<Boolean>

    /** Milliseconds on a clock that keeps counting while the app is suspended and the device sleeps. */
    fun continuousMillis(): Long
}
