package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DensityClass
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
    attempt: Int = 0,
): ByteArray {
    val header = UnicastHeader(hopLimit, id, expiry.toUInt(), destination, source, attempt)
    return UnicastFrame(type, header, body).encode()
}

/** The body of a `MESSAGE` frame with [container]: a zero commitment, then the container. */
internal fun messageBody(
    container: Container,
    commitment: ByteArray = ByteArray(16),
): ByteArray = commitment + container.encode()

/** `trunc_16(H("tombstone", k))`, as a node's floods verify tombstones with. */
internal fun tombstoneCommitment(k: ByteArray): ByteArray = Derivations(SodiumCryptoProvider).tombstoneCommitment(k)

/** A broadcast tree on [lanes] in the low class, which floods; its grafts run on [scope] and [clock]. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun lowTree(
    lanes: Lanes,
    scope: TestScope = TestScope(),
    clock: Clock = SchedulerClock(scope.testScheduler),
): Plumtree = Plumtree(lanes, clock, scope) { DensityClass.Low }

/**
 * The first-message keys of the node with [identityKey] [seed]: a fresh static key, and an X-Wing key if
 * [postQuantum], under a certificate valid for [validity] from [clock]'s now.
 */
internal fun securedKeys(
    clock: Clock,
    seed: Int,
    validity: kotlin.time.Duration = kotlin.time.Duration.parse("24h"),
    postQuantum: Boolean = false,
): SecuredKeys {
    val crypto = SodiumCryptoProvider
    val identity = identityKey(seed)
    val static = crypto.x25519KeyPair()
    val xWing = if (postQuantum) crypto.xWingKeyPair() else null
    val keys = StaticKeys(static.publicKey, xWing?.publicKey)
    val certificate = IdentityCertificate.issue(crypto, clock, identity, keys, validity)
    return SecuredKeys(Derivations(crypto).nodeId(identity.publicKey), static, xWing, certificate)
}

/**
 * [certificate], issued by [identity], re-signed with `not_after` one second past `certificate-validity-max`
 * (86,400 s) after its `not_before`: a certificate every validation must reject.
 */
internal fun overlong(
    certificate: IdentityCertificate,
    identity: Ed25519KeyPair,
): ByteArray {
    val bytes = certificate.encode()
    val body = bytes.copyOf(bytes.size - SIGNATURE_LENGTH)
    val notAfter = certificate.notBefore + VALIDITY_MAX_SECONDS + 1u
    val width = UInt.SIZE_BYTES
    ByteArray(width) { (notAfter shr (Byte.SIZE_BITS * (width - 1 - it))).toByte() }.copyInto(body, body.size - width)
    return body + SodiumCryptoProvider.ed25519Sign(identity.seed, IdentityCertificate.signingInput(body))
}

private const val SIGNATURE_LENGTH = 64
private const val VALIDITY_MAX_SECONDS = 86_400u
