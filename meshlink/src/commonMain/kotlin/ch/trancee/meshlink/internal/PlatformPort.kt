@file:OptIn(ExperimentalObjCRefinement::class)

package ch.trancee.meshlink.internal

import ch.trancee.meshlink.Requirement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

/**
 * What a platform gives a node: one per platform radio, shared by every node on it. The port's
 * identity is the radio's: at most one node holds a port at a time.
 */
@HiddenFromObjC
public interface PlatformPort {
    /** What the radio is missing now; empty when it can run. */
    public val requirements: StateFlow<Set<Requirement>>

    /** Where nodes on this platform do their work; it runs one task at a time. */
    public val dispatcher: CoroutineDispatcher

    /** Where the installation identity is kept. */
    public val identityStore: IdentityStore

    /** The platform radio; a node uses it only while it holds the port. */
    public val radio: RadioPort

    /** The platform clocks. */
    public val clock: Clock
}

/** Which node holds each platform radio; a node runs only while it holds its radio. */
internal object RadioRegistry {
    private val mutableHolders = MutableStateFlow<Map<PlatformPort, Any>>(emptyMap())

    /** The holder of each held radio. */
    val holders: StateFlow<Map<PlatformPort, Any>> = mutableHolders

    /**
     * Makes [owner] the holder of [port], which is free in [seen], if [holders] is still [seen];
     * returns whether it did. On false, [holders] has changed and its new value tells the caller more.
     */
    fun tryAcquire(
        port: PlatformPort,
        owner: Any,
        seen: Map<PlatformPort, Any>,
    ): Boolean = mutableHolders.compareAndSet(seen, seen + (port to owner))

    /** Frees [port] if [owner] holds it. */
    fun release(
        port: PlatformPort,
        owner: Any,
    ) {
        mutableHolders.update { if (it[port] === owner) it - port else it }
    }
}
