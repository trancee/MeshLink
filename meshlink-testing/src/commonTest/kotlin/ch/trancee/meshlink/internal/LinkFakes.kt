package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DiagnosticEvent
import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshLinkConfigBuilder
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import ch.trancee.meshlink.meshLinkConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlin.test.assertIs

/** The mesh ID of the nodes in these tests. */
internal const val MESH = "Festival"

/** Discovery, connect, capability read and both hellos: a handful of air latencies. */
internal const val SETTLE_MILLIS = 1_000L

/** The credit window a MeshLink central grants on open (spec "Flow control"). */
internal const val CREDIT_WINDOW = 8

/** The grant a MeshLink central sends per half-window of chunks it receives. */
internal const val CREDIT_HALF = 4

/** The NodeId a fake peer claims in its hello. */
internal val FAKE_ID = ByteArray(32) { 0x7F }

/**
 * Air whose clock is the test's virtual time, with latencies drawn from [seed] and, if set, chunks
 * taking their airtime at [airtimeBytesPerSecond].
 */
internal fun TestScope.air(
    seed: Long = 1,
    airtimeBytesPerSecond: Int? = null,
): VirtualAir {
    val clock = TestClock(testScheduler)
    val air = VirtualAir(backgroundScope, clock, SeededRandom(seed))
    air.airtimeBytesPerSecond = airtimeBytesPerSecond
    return air
}

/**
 * A stopped `unsecured` node of [MESH] on [platform] with debug diagnostics on, with any further settings
 * from [configure].
 */
@OptIn(InternalMeshLinkApi::class)
internal fun node(
    platform: PlatformPort,
    configure: MeshLinkConfigBuilder.() -> Unit = {},
): MeshLink =
    MeshLink.create(
        meshLinkConfig(Preset.OfflineChat, SecurityProfile.Unsecured) {
            meshId = MESH
            diagnostics.debug = true
            configure()
        }.build(),
        MeshPlatform.of(platform),
    )

/** [virtual] with its radio replaced by [radio]. */
internal fun platformWith(
    virtual: VirtualPlatform,
    radio: RadioPort,
): PlatformPort =
    object : PlatformPort by virtual {
        override val radio: RadioPort = radio
    }

/** The NodeId of the node that started on [platform]. */
internal fun nodeId(platform: VirtualPlatform): ByteArray =
    Derivations(SodiumCryptoProvider).nodeId(platform.identityStore.loadOrCreate(NoNewKeyPairs)!!.publicKey)

/** A [CryptoProvider] that fails when asked for a new key pair: for reading an identity that must exist. */
internal object NoNewKeyPairs : CryptoProvider by SodiumCryptoProvider {
    override fun ed25519KeyPair(): Ed25519KeyPair = error("no identity")
}

/** A raw radio that connects to [target] as central and waits for the link to open. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun TestScope.connectFake(
    air: VirtualAir,
    target: VirtualPlatform,
    maxAttMtu: Int = VirtualAir.DEFAULT_ATT_MTU,
): CentralLink {
    val link = (air.radio(maxAttMtu).connect(target.radio.handle) as RadioResult.Ok).value
    advanceTimeBy(1)
    return link
}

/**
 * Makes [fake], a central linked to the node's open [end], grant credits as a MeshLink central does: the window
 * at once, then half of it after every half-window of chunks the node writes from now on. Consumes the fake's
 * incoming stream.
 */
internal fun TestScope.grantCredits(
    fake: RadioLink,
    end: VirtualLinkEnd,
) {
    val before = end.chunks
    backgroundScope.launch {
        fake.write(record(LinkFrame.Credit(CREDIT_WINDOW)))
        fake.incoming.drop(before).collectIndexed { index, _ ->
            if ((index + 1) % CREDIT_HALF == 0) fake.write(record(LinkFrame.Credit(CREDIT_HALF)))
        }
    }
}

/** Every diagnostic but the lifecycle ones (`ML1xxx`) [node] reports from now on. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.diagnostics(node: MeshLink): List<DiagnosticEvent> {
    val seen = mutableListOf<DiagnosticEvent>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        node.diagnostics.collect { if (!it.code.startsWith(LIFECYCLE)) seen += it }
    }
    return seen
}

private const val LIFECYCLE = "ML1"

/** The MeshIdDigest of [meshId] in the `unsecured` profile. */
internal fun digest(meshId: String): ByteArray =
    Derivations(SodiumCryptoProvider).meshIdDigest(meshId.encodeToByteArray(), WireProfile.UNSECURED)

/** [frame] as one link record. */
internal fun record(frame: LinkFrame): ByteArray = LinkRecord.encode(frame.encode())

/** [bytes] as one link record. */
internal fun record(bytes: ByteArray): ByteArray = LinkRecord.encode(bytes)

/** A raw radio that advertises [MESH] and serves a capability record listing [versions]. */
internal fun advertiseFake(
    air: VirtualAir,
    versions: List<Int>,
): VirtualRadio {
    val record = CapabilityRecord.of(versions, ByteArray(RadioPort.ADVERTISEMENT_LENGTH))
    return advertiseFake(air, record.encode())
}

/**
 * A raw radio that advertises [MESH] in [profile] and serves [capability], or no capability characteristic if
 * `null`; it supports L2CAP CoC if [coc].
 */
internal fun advertiseFake(
    air: VirtualAir,
    capability: ByteArray?,
    coc: Boolean = false,
    profile: WireProfile = WireProfile.UNSECURED,
): VirtualRadio {
    val radio = air.radio(coc = coc)
    val discovery = Discovery(SodiumCryptoProvider, MESH.encodeToByteArray(), profile)
    val payload = discovery.advertisement(Derivations.epoch(TestClock.WALL_CLOCK_START_MILLIS / MILLIS_PER_SECOND))
    capability?.let { radio.serveCapability(it) }
    radio.startAdvertising(payload)
    return radio
}

/** The link-local frames [end] wrote, skipping routed ones. */
internal fun frames(end: VirtualLinkEnd): List<LinkFrame> = frames(end.written)

/** The link-local frames in the records [written], skipping routed ones. */
internal fun frames(written: ByteArray): List<LinkFrame> {
    val bodies = assertIs<Decoded.Valid<List<ByteArray>>>(RecordReader().read(written)).value
    return bodies
        .filter { it[0].toInt() and 0xFF < ROUTED_MIN }
        .map { assertIs<Decoded.Valid<LinkFrame>>(LinkFrame.decode(it)).value }
}

/** The reasons of the `CLOSE` frames [end] wrote. */
internal fun closes(end: VirtualLinkEnd): List<CloseReason> {
    val closes = frames(end).filterIsInstance<LinkFrame.Close>()
    return closes.map { it.reason }
}

// Routed frame types start at 0x40.
private const val ROUTED_MIN = 0x40
private const val MILLIS_PER_SECOND = 1_000L

/** The last gap, in ms, between a scan window of [platform]'s radio and the next: its scan off time. */
internal fun lastGap(platform: VirtualPlatform): Long? =
    platform.radio.scanLog
        .zipWithNext()
        .lastOrNull { (stop, start) -> !stop.second && start.second }
        ?.let { (stop, start) -> start.first - stop.first }
