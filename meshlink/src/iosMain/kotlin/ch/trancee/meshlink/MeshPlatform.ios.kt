package ch.trancee.meshlink

import ch.trancee.meshlink.internal.IdentityStore
import ch.trancee.meshlink.internal.InMemoryIdentityStore
import ch.trancee.meshlink.internal.PlatformPort
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The platform of this iOS installation. Nodes on it share its one Bluetooth radio, so one of them
 * runs at a time.
 *
 * The iOS simulator has no Bluetooth: nodes there stay [MeshState.Blocked] by
 * [Requirement.Unsupported] (ADR 0005). Until the Core Bluetooth radio lands, a device reports the
 * same.
 */
public fun MeshPlatform.Companion.ios(): MeshPlatform = IosPlatform.meshPlatform

private object IosPlatform : PlatformPort {
    override val requirements: StateFlow<Set<Requirement>> = MutableStateFlow(setOf(Requirement.Unsupported))

    override val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    override val identityStore: IdentityStore = InMemoryIdentityStore()

    val meshPlatform = MeshPlatform(this)
}
