package ch.trancee.meshlink.api

/**
 * Typed platform bootstrap handle accepted by [meshLink] when a runtime needs platform-specific
 * construction input.
 *
 * Obtain instances from platform helpers rather than creating custom subclasses directly.
 */
public abstract class MeshLinkBootstrap internal constructor()

internal object AndroidFactoryTestMeshLinkBootstrap : MeshLinkBootstrap()
