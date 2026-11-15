package ch.trancee.meshlink.internal

import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.Requirement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull

/**
 * A [MeshPlatform] whose radio is [radio] on a [VirtualAir] and whose clock is the air's. Nodes do
 * their work on [dispatcher], which a test sets to a dispatcher of its virtual-time scheduler.
 */
internal class VirtualPlatform(
    override val radio: VirtualRadio,
    override val clock: Clock,
    override val dispatcher: CoroutineDispatcher,
) : PlatformPort {
    override val requirements: Flow<Set<Requirement>> = radio.requirements.filterNotNull()

    override val identityStore: IdentityStore = InMemoryIdentityStore()

    @OptIn(InternalMeshLinkApi::class)
    val meshPlatform: MeshPlatform = MeshPlatform.of(this)
}
