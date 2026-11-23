@file:OptIn(ExperimentalObjCRefinement::class)

package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

/**
 * A secured node's certificate [lookups] (spec "Certificate lookup"), for `meshlink-testing` until
 * end-to-end messages look up their receivers. Each lookup lasts one message lifetime of
 * [lifetimeSeconds]; the lookups run on [scope], whose serial [dispatcher] is the node's.
 */
@HiddenFromObjC
public class CertificateLookups internal constructor(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val clock: Clock,
    private val lifetimeSeconds: Int,
    private val lookups: Lookups,
) {
    /** Looks [nodeId]'s certificate up across the mesh unless this node holds it; a running node only. */
    public fun lookUp(nodeId: NodeId) {
        scope.launch { lookups.find(nodeId, clock.wallClockMillis() + lifetimeSeconds * MILLIS_PER_SECOND) }
    }

    /** The certificate this node holds for [nodeId], from a link or a lookup, or `null`. */
    public suspend fun certificateOf(nodeId: NodeId): IdentityCertificate? =
        withContext(dispatcher) {
            lookups.certificate(nodeId)
        }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
