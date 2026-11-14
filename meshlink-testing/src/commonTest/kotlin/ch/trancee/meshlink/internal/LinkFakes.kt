package ch.trancee.meshlink.internal

import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import ch.trancee.meshlink.meshLinkConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy

/** The mesh ID of the nodes in these tests. */
internal const val MESH = "Festival"

/** Discovery, connect, capability read and both hellos: a handful of air latencies. */
internal const val SETTLE_MILLIS = 1_000L

/** The NodeId a fake peer claims in its hello. */
internal val FAKE_ID = ByteArray(32) { 0x7F }

/** Air whose clock is the test's virtual time, with latencies drawn from [seed]. */
internal fun TestScope.air(seed: Long = 1): VirtualAir {
    val clock = TestClock(testScheduler)
    return VirtualAir(backgroundScope, clock, SeededRandom(seed))
}

/** A stopped `unsecured` node of [MESH] on [platform]. */
@OptIn(InternalMeshLinkApi::class)
internal fun node(platform: PlatformPort): MeshLink =
    MeshLink.create(
        meshLinkConfig(Preset.OfflineChat, SecurityProfile.Unsecured) { meshId = MESH }.build(),
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
    Derivations(SodiumCryptoProvider).nodeId(platform.identityStore.loadOrCreate { error("no identity") }.publicKey)

/** A raw radio that connects to [target] as central and waits for the link to open. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun TestScope.connectFake(
    air: VirtualAir,
    target: VirtualPlatform,
): CentralLink {
    val link = (air.radio().connect(target.radio.handle) as RadioResult.Ok).value
    advanceTimeBy(1)
    return link
}

/** The MeshIdDigest of [meshId] in the `unsecured` profile. */
internal fun digest(meshId: String): ByteArray =
    Derivations(SodiumCryptoProvider).meshIdDigest(meshId.encodeToByteArray(), WireProfile.UNSECURED)

/** [frame] as one link record. */
internal fun record(frame: LinkFrame): ByteArray = LinkRecord.encode(frame.encode())

/** [bytes] as one link record. */
internal fun record(bytes: ByteArray): ByteArray = LinkRecord.encode(bytes)
