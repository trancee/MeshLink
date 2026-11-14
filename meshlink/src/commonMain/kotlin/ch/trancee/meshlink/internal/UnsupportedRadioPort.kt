package ch.trancee.meshlink.internal

import ch.trancee.meshlink.Requirement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The port of a platform without a Bluetooth radio MeshLink can use, such as the iOS simulator:
 * every node on it stays blocked by [Requirement.Unsupported].
 */
internal class UnsupportedRadioPort(
    override val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : PlatformPort {
    override val requirements: StateFlow<Set<Requirement>> = MutableStateFlow(setOf(Requirement.Unsupported))

    override val identityStore: IdentityStore = InMemoryIdentityStore()
}
