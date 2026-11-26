package ch.trancee.meshlink.internal

import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.Limitation
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.Requirement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * A [MeshPlatform] whose radio is [radio] on a [VirtualAir] and whose clock and randomness are the
 * air's. Nodes do their work on [dispatcher], which a test sets to a dispatcher of its virtual-time
 * scheduler.
 */
internal class VirtualPlatform(
    override val radio: VirtualRadio,
    override val clock: Clock,
    override val random: RandomSource,
    override val dispatcher: CoroutineDispatcher,
) : PlatformPort {
    override val requirements: Flow<Set<Requirement>> = radio.requirements.filterNotNull()

    /** What a node on this platform runs without; tests set it. */
    override val limitations: MutableStateFlow<Set<Limitation>> = MutableStateFlow(emptySet())

    override val identityStore: IdentityStore = InMemoryIdentityStore()

    /** The lines written to [log], each `severity|shown|hidden`. */
    val logged: MutableList<String> = mutableListOf()

    override val log: PlatformLog = PlatformLog { severity, shown, hidden -> logged += "$severity|$shown|$hidden" }

    @OptIn(InternalMeshLinkApi::class)
    val meshPlatform: MeshPlatform = MeshPlatform.of(this)
}
