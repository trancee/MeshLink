package ch.trancee.meshlink.internal

import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** How long a [FakePeer]'s certificate is valid. */
internal val VALIDITY: Duration = 24.hours

/** An empty payload or message. */
internal val EMPTY = ByteArray(0)

/** The digest of [meshId] in [wire]. */
internal fun digestOf(
    meshId: String,
    wire: WireProfile,
): ByteArray = Derivations(SodiumCryptoProvider).meshIdDigest(meshId.encodeToByteArray(), wire)

/**
 * A scripted peer of [MESH] in profile [wire]: its own identity, static key and, in
 * `PostQuantum`, X-Wing key; the prologue bound to [digest] and [versions]; gated with
 * [meshKey] if set.
 */
internal class FakePeer(
    private val wire: WireProfile,
    clock: Clock,
    private val meshKey: ByteArray? = null,
    private val digest: ByteArray = digestOf(MESH, wire),
    private val versions: List<Int> = listOf(1),
) {
    private val identity = SodiumCryptoProvider.ed25519KeyPair()
    private val static = SodiumCryptoProvider.x25519KeyPair()
    private val xWingKey =
        if (wire == WireProfile.POST_QUANTUM) SodiumCryptoProvider.xWingKeyPair().publicKey else null

    val nodeId: ByteArray = Derivations(SodiumCryptoProvider).nodeId(identity.publicKey)
    val certificate: ByteArray = issue(clock, static, VALIDITY, xWingKey).encode()

    /** The payload of this peer's message 2 or 3. */
    var payload: ByteArray = HandshakePayload.of(certificate).encode()

    /** The node's message 2 payload in the last handshake [initiate] ran. */
    var received: ByteArray = EMPTY

    fun noise(role: NoiseRole): NoiseHandshake {
        val psk = meshKey?.let { Derivations(SodiumCryptoProvider).meshPsk(it, digest) }
        val pattern =
            when {
                wire == WireProfile.CLASSICAL -> if (psk == null) NoisePattern.XX else NoisePattern.XX_PSK3
                else -> if (psk == null) NoisePattern.XX_HFS else NoisePattern.XX_HFS_PSK3
            }
        val prologue = Derivations.linkPrologue(versions, 1, wire, digest)
        return NoiseHandshake(SodiumCryptoProvider, pattern, role, prologue, NoiseKeys(static, psk))
    }

    /** A payload whose certificate [clock] issued for [validity], binding [key] and [xWing]. */
    fun payloadFor(
        clock: Clock,
        validity: Duration,
        key: KeyPair = static,
        xWing: ByteArray? = xWingKey,
    ): ByteArray = HandshakePayload.of(issue(clock, key, validity, xWing).encode()).encode()

    private fun issue(
        clock: Clock,
        key: KeyPair,
        validity: Duration,
        xWing: ByteArray?,
    ): IdentityCertificate {
        val keys = StaticKeys(key.publicKey, xWing)
        return IdentityCertificate.issue(SodiumCryptoProvider, clock, identity, keys, validity)
    }
}

/** Runs [fake]'s initiator side of a full handshake on [link] with the node at [end]. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun TestScope.initiate(
    fake: FakePeer,
    link: RadioLink,
    end: VirtualLinkEnd,
): NoiseTransport {
    val noise = fake.noise(NoiseRole.INITIATOR)
    link.write(handshake(1, ok(noise.writeMessage(EMPTY))))
    advanceTimeBy(SETTLE_MILLIS)
    fake.received = ok(noise.readMessage(handshakeIn(clearFrames(end).single()).noiseMessage))
    link.write(handshake(3, ok(noise.writeMessage(fake.payload))))
    advanceTimeBy(SETTLE_MILLIS)
    return ok(noise.split())
}

internal fun bodies(end: VirtualLinkEnd): List<ByteArray> {
    val read = RecordReader().read(end.written)
    return assertIs<Decoded.Valid<List<ByteArray>>>(read).value
}

/**
 * The first [clear] frames [end] wrote in cleartext, each reassembled from its `FRAGMENT`s,
 * and the record bodies after them, which may be encrypted.
 */
internal fun written(
    end: VirtualLinkEnd,
    clear: Int = Int.MAX_VALUE,
): Pair<List<ByteArray>, List<ByteArray>> {
    val bodies = bodies(end)
    val frames = mutableListOf<ByteArray>()
    val streams = mutableMapOf<Int, ByteArray>()
    var used = 0
    while (frames.size < clear && used < bodies.size) {
        val body = bodies[used++]
        val fragment = (LinkFrame.decode(body) as? Decoded.Valid)?.value as? LinkFrame.Fragment
        val data = fragment?.let { streams.getOrElse(it.streamId) { EMPTY } + it.data }
        when {
            fragment == null -> frames += body
            fragment.last -> frames += data!!.also { streams -= fragment.streamId }
            else -> streams[fragment.streamId] = data!!
        }
    }
    return frames to bodies.drop(used)
}

/** Every frame [end] wrote, all in cleartext. */
internal fun clearFrames(end: VirtualLinkEnd): List<ByteArray> = written(end).first

internal fun handshakeIn(body: ByteArray): LinkFrame.Handshake =
    assertIs<LinkFrame.Handshake>(assertIs<Decoded.Valid<LinkFrame>>(LinkFrame.decode(body)).value)

/** A `HANDSHAKE` frame as one record, or as records of `FRAGMENT`s if longer than a record body. */
internal fun handshake(
    step: Int,
    message: ByteArray,
    version: Int = 1,
): ByteArray {
    val frame = LinkFrame.Handshake(version, step, message).encode()
    val dataMax = LinkRecord.BODY_MAX - LinkFrame.Fragment.HEADER
    val records =
        if (frame.size <=
            LinkRecord.BODY_MAX
        ) {
            listOf(record(frame))
        } else {
            LinkFrame.Fragment.split(0, frame, dataMax).map { record(it) }
        }
    return records.reduce(ByteArray::plus)
}

internal fun seal(
    transport: NoiseTransport,
    frame: ByteArray,
): ByteArray = record(ok(transport.encrypt(frame)))

internal fun open(
    transport: NoiseTransport,
    body: ByteArray,
): ByteArray = ok(transport.decrypt(body))

internal fun <T> ok(result: NoiseResult<T>): T = assertIs<NoiseResult.Ok<T>>(result).value
