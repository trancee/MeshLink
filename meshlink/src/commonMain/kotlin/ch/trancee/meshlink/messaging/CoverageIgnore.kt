package ch.trancee.meshlink.messaging

/**
 * Marks a function or property getter as excluded from Kover coverage reporting.
 *
 * Applied to methods that contain defensive null/existence checks for code patterns that are
 * correct and necessary but are unreachable via the current test harness (e.g., null guards on
 * types that are contractually non-null in their usage context, or Flow map lambdas that only
 * execute when the engine emits items in integration scenarios). The Kover `annotatedBy` filter in
 * `reports.filters.excludes` excludes annotated methods from line and branch coverage statistics.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.BINARY)
internal annotation class CoverageIgnore
