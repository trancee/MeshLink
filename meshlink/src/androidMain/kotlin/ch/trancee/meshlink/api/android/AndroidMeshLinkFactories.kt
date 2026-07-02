package ch.trancee.meshlink.api.android

import android.content.Context
import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.api.MeshLinkBootstrap
import ch.trancee.meshlink.api.meshLink
import ch.trancee.meshlink.config.MeshLinkConfig

/**
 * Returns an Android bootstrap handle backed by the application context.
 *
 * Prefer the single-step [meshLink] overload below for typical integrations. Use this helper only
 * when the bootstrap handle must be constructed ahead of [meshLink] (for example, in
 * dependency-injection setups).
 */
public fun meshLinkBootstrap(context: Context): MeshLinkBootstrap {
    return AndroidMeshLinkBootstrap(context.applicationContext)
}

/**
 * Creates a MeshLink runtime directly from an Android application context.
 *
 * This is the recommended entry point for Android integrators; it is equivalent to
 * `meshLink(config, meshLinkBootstrap(context))`.
 */
public fun meshLink(config: MeshLinkConfig, context: Context): MeshLink {
    return meshLink(config = config, bootstrap = meshLinkBootstrap(context))
}
