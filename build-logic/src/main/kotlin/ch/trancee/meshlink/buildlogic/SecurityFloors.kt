package ch.trancee.meshlink.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension

/** The lowest version of [group]:[name] any configuration may resolve: the newest patched release in the line build tooling uses. */
internal data class SecurityFloor(
    val group: String,
    val name: String,
    val version: String,
)

/**
 * Raises vulnerable transitive versions that build tooling (AGP lint and UTP, Dokka, Kover, KGP swift-export) resolves
 * in its own configurations up to the `floor-*` libraries of the version catalog (ADR 0005, security floors). A request
 * at or above its floor is kept, so a plugin upgrade that moves past a floor wins without a catalog edit.
 */
internal object SecurityFloors {
    const val ALIAS_PREFIX = "floor."

    fun apply(project: Project) =
        apply(project, fromCatalog(project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")))

    fun apply(
        project: Project,
        floors: List<SecurityFloor>,
    ) {
        project.configurations.configureEach {
            resolutionStrategy.eachDependency {
                val floor = floors.firstOrNull { it.group == requested.group && it.name == requested.name }
                val version = requested.version
                if (floor != null && version != null && isBelow(version, floor.version)) {
                    useVersion(floor.version)
                    because("security floor (ADR 0005)")
                }
            }
        }
    }

    fun fromCatalog(catalog: VersionCatalog): List<SecurityFloor> =
        catalog.libraryAliases
            .filter { it.startsWith(ALIAS_PREFIX) }
            .map { catalog.findLibrary(it).get().get() }
            .map { SecurityFloor(it.module.group, it.module.name, it.versionConstraint.requiredVersion) }

    /** Compares the leading numeric segments only; a range or a version without them is never below a floor. */
    fun isBelow(
        requested: String,
        floor: String,
    ): Boolean {
        val left = numericSegments(requested)
        val right = numericSegments(floor)
        if (left.isEmpty()) return false
        val difference =
            (0 until maxOf(left.size, right.size))
                .map { left.getOrElse(it) { 0 }.compareTo(right.getOrElse(it) { 0 }) }
                .firstOrNull { it != 0 }
        return difference != null && difference < 0
    }

    private fun numericSegments(version: String): List<Int> =
        version
            .split('.', '-')
            .takeWhile { segment -> segment.isNotEmpty() && segment.all { it.isDigit() } }
            .map { it.toInt() }
}
