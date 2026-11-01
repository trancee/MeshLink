package ch.trancee.meshlink

import ch.trancee.meshlink.internal.NodeEngine
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Platform handle passed to [MeshLink.create]. On Android it wraps an explicit `Context`; on iOS
 * it carries no state. In this prototype the only implementation is
 * [ch.trancee.meshlink.fake.FakeHost].
 */
@OptIn(ExperimentalAtomicApi::class)
public abstract class MeshLinkPlatform internal constructor() {
    private val openInstance = AtomicReference<Any?>(null)

    internal abstract fun createEngine(config: MeshLinkConfig): NodeEngine

    internal fun claim(owner: Any): Boolean = openInstance.compareAndSet(null, owner)

    internal fun release(owner: Any) {
        openInstance.compareAndSet(owner, null)
    }
}
