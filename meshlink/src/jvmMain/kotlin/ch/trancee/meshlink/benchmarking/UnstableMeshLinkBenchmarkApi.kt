package ch.trancee.meshlink.benchmarking

/**
 * Marks JVM-only MeshLink benchmarking bridge APIs.
 *
 * These declarations exist solely so the separate `:benchmarks` module can exercise MeshLink
 * internals without relying on invisible-reference compiler behavior. They are not part of the
 * stable production SDK surface and may change or be removed at any time.
 */
@MustBeDocumented
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message =
        "This API is only intended for MeshLink benchmark harnesses and is not stable for production use.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
)
public annotation class UnstableMeshLinkBenchmarkApi
