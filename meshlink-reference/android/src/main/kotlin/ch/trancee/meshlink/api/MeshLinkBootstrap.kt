package ch.trancee.meshlink.api

/**
 * Typed platform bootstrap handle accepted by [meshLink] when a runtime needs platform-specific
 * construction input.
 *
 * This local copy keeps the reference Android app self-contained at runtime.
 */
public abstract class MeshLinkBootstrap internal constructor()
