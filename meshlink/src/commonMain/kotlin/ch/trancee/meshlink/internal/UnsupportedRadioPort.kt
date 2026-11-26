package ch.trancee.meshlink.internal

import ch.trancee.meshlink.Limitation
import ch.trancee.meshlink.Requirement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/**
 * The port of a platform without a Bluetooth radio MeshLink can use, such as the iOS simulator:
 * every node on it stays blocked by [Requirement.Unsupported].
 */
internal class UnsupportedRadioPort(
    override val log: PlatformLog,
    override val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : PlatformPort {
    override val requirements: StateFlow<Set<Requirement>> = MutableStateFlow(setOf(Requirement.Unsupported))

    override val limitations: Flow<Set<Limitation>> = flowOf(emptySet())

    override val identityStore: IdentityStore = InMemoryIdentityStore()

    override val radio: RadioPort = UnsupportedRadio

    override val clock: Clock = SystemClock

    override val random: RandomSource = SystemRandom
}

/** A radio that refuses every operation with [RadioFailure.UNSUPPORTED] and observes nothing. */
internal object UnsupportedRadio : RadioPort {
    private val unsupported = RadioResult.Failed(RadioFailure.UNSUPPORTED)

    override val peripheralLinkCap: Int? = null

    override val supportsLe2MPhy: Boolean = false

    override val events: Flow<RadioEvent> = emptyFlow()

    override fun startAdvertising(payload: ByteArray): RadioResult<Unit> = unsupported

    override fun stopAdvertising() = Unit

    override fun startScanning(): RadioResult<Unit> = unsupported

    override fun stopScanning() = Unit

    override fun serveCapability(record: ByteArray): RadioResult<Unit> = unsupported

    override suspend fun connect(peer: PeerHandle): RadioResult<CentralLink> = unsupported

    override suspend fun listenForChannels(): RadioResult<Int> = unsupported

    override fun stopListening() = Unit
}
