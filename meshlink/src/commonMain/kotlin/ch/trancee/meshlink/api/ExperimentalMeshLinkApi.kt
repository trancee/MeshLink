package ch.trancee.meshlink.api

/**
 * Marks a MeshLink API element as experimental.
 *
 * Experimental APIs may change without notice in future releases. Opt in via
 * `@OptIn(ExperimentalMeshLinkApi::class)` or `@ExperimentalMeshLinkApi` on a call site.
 */
@RequiresOptIn(
    message = "This MeshLink API is experimental and may change without notice in future releases.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.ANNOTATION_CLASS,
)
public annotation class ExperimentalMeshLinkApi
