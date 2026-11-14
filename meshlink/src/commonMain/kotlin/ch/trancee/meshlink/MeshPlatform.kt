@file:OptIn(ExperimentalObjCRefinement::class)

package ch.trancee.meshlink

import ch.trancee.meshlink.internal.PlatformPort
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

/**
 * Marks MeshLink API that only MeshLink's own modules may use. It can change or disappear in any
 * release.
 */
@RequiresOptIn(
    message = "MeshLink-internal API: only MeshLink's own modules may use it.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
public annotation class InternalMeshLinkApi

/**
 * The radio and system services a [MeshLink] node runs on. Each platform has a factory, such as
 * `MeshPlatform.ios()`; `meshlink-testing` supplies virtual platforms for tests.
 *
 * At most one node runs on one platform radio at a time; see [Requirement.RadioInUse].
 */
public class MeshPlatform internal constructor(
    internal val port: PlatformPort,
) {
    /** Holds the platform factories, such as `MeshPlatform.ios()`. */
    public companion object {
        /** A platform around [port]; for `meshlink-testing`. */
        @InternalMeshLinkApi
        @HiddenFromObjC
        public fun of(port: PlatformPort): MeshPlatform = MeshPlatform(port)
    }
}
