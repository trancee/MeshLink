package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope

/** A clock on [scheduler]'s virtual time; the wall clock starts at [WALL_START_MILLIS]. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SchedulerClock(
    private val scheduler: TestCoroutineScheduler,
) : Clock {
    override fun monotonicMillis(): Long = scheduler.currentTime

    override fun wallClockMillis(): Long = WALL_START_MILLIS + scheduler.currentTime

    companion object {
        /** 2026-01-01T00:00:00Z, the start of an epoch. */
        const val WALL_START_MILLIS: Long = 1_767_225_600_000
    }
}

/** The frames now waiting in [peer]'s lane, taken in order. */
internal suspend fun Lanes.drain(peer: NodeId): List<Parcel> =
    coroutineScope {
        val taken = mutableListOf<Parcel>()
        while (true) {
            val next = async(start = CoroutineStart.UNDISPATCHED) { next(peer) }
            if (!next.isCompleted) {
                next.cancel()
                break
            }
            taken += next.await()
        }
        taken
    }

/** A NodeId of 32 bytes [n]. */
internal fun nodeId(n: Int): NodeId = NodeId(ByteArray(32) { n.toByte() })

/** The Ed25519 identity from a seed of 32 bytes [n]. */
internal fun identityKey(n: Int): Ed25519KeyPair = SodiumCryptoProvider.ed25519KeyPair(ByteArray(32) { n.toByte() })

/**
 * A begun broadcast codec, signing if [secured], of the node with [identityKey] 1 and [nodeId]: by
 * default the NodeId of that key, as a secured node needs.
 */
internal fun broadcastCodec(
    secured: Boolean = false,
    nodeId: ByteArray = Derivations(SodiumCryptoProvider).nodeId(identityKey(1).publicKey),
    random: (Int) -> ByteArray = SodiumCryptoProvider::randomBytes,
): BroadcastCodec {
    val codec = BroadcastCodec(SodiumCryptoProvider, secured, random)
    codec.begin(InstallationIdentity(identityKey(1), nodeId))
    return codec
}

/** A tag of 8 bytes [n]. */
internal fun tag(n: Int): ByteArray = ByteArray(8) { n.toByte() }

/** A message_id of 12 bytes [n]. */
internal fun messageId(n: Int): ByteArray = ByteArray(12) { n.toByte() }

/** The bytes of a unicast frame of [type] with [body]. */
internal fun unicast(
    type: Int,
    hopLimit: Int,
    id: ByteArray,
    expiry: Long,
    destination: ByteArray,
    source: ByteArray,
    body: ByteArray = byteArrayOf(),
): ByteArray = UnicastFrame(type, UnicastHeader(hopLimit, id, expiry.toUInt(), destination, source), body).encode()

/** The body of a `MESSAGE` frame with [container]: a zero commitment, then the container. */
internal fun messageBody(container: Container): ByteArray = ByteArray(16) + container.encode()

/** A broadcast tree on [lanes] in the low class, which floods; its grafts run on [scope] and [clock]. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun lowTree(
    lanes: Lanes,
    scope: TestScope = TestScope(),
    clock: Clock = SchedulerClock(scope.testScheduler),
): Plumtree = Plumtree(lanes, clock, scope) { DensityClass.LOW }

/**
 * The Classical first-message keys of the node with [identityKey] [seed]: a fresh static key under a
 * certificate valid for [validity] from [clock]'s now.
 */
internal fun securedKeys(
    clock: Clock,
    seed: Int,
    validity: kotlin.time.Duration = kotlin.time.Duration.parse("24h"),
): SecuredKeys {
    val crypto = SodiumCryptoProvider
    val identity = identityKey(seed)
    val static = crypto.x25519KeyPair()
    val certificate = IdentityCertificate.issue(crypto, clock, identity, StaticKeys(static.publicKey, null), validity)
    return SecuredKeys(Derivations(crypto).nodeId(identity.publicKey), static, null, certificate)
}
