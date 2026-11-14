package ch.trancee.meshlink.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.io.StringWriter
import java.util.Properties

/**
 * Runs Android host tests under Robolectric without network access (ADR 0017): every catalog library
 * whose alias starts with `robolectric-android-all-` is resolved by Gradle in its own configuration, written to
 * `build/robolectric/robolectric-deps.properties`, and handed to Robolectric through the
 * `robolectric-deps.properties` system property, which Robolectric consults before any Maven fetch.
 */
class RobolectricPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(KmpLibraryPlugin::class.java)
        val catalog = project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
        // One configuration per SDK: the pins share a module, so a single configuration would keep only the newest.
        val androidAll =
            catalog.libraryAliases
                .filter { it.startsWith(ANDROID_ALL_ALIAS_PREFIX) }
                .map { alias ->
                    val name = "robolectric" + alias.removePrefix(ANDROID_ALL_ALIAS_PREFIX).replaceFirstChar(Char::uppercase)
                    project.configurations.register(name) {
                        isCanBeConsumed = false
                        isTransitive = false
                        dependencies.addLater(catalog.findLibrary(alias).get())
                    }
                }
        val writeDeps =
            project.tasks.register("writeRobolectricDeps", WriteRobolectricDeps::class.java) {
                description = "Writes the Robolectric android-all jar locations for offline host tests."
                androidAll.forEach { configuration ->
                    jars.from(configuration)
                    coordinates.putAll(
                        configuration.flatMap {
                            it.incoming.artifacts.resolvedArtifacts.map { artifacts ->
                                artifacts.associate { artifact ->
                                    val id = artifact.id.componentIdentifier as ModuleComponentIdentifier
                                    "${id.group}:${id.module}:${id.version}" to artifact.file.absolutePath
                                }
                            }
                        },
                    )
                }
                output.set(project.layout.buildDirectory.file("robolectric/robolectric-deps.properties"))
            }
        project.tasks.withType(Test::class.java).configureEach {
            jvmArgumentProviders.add(RobolectricDepsArgument(writeDeps.flatMap { it.output }))
        }
        project.extensions.configure(KotlinMultiplatformExtension::class.java) {
            sourceSets.getByName("androidHostTest").dependencies {
                listOf("robolectric", "androidx-test-core", "junit4", "kotlin-test-junit").forEach {
                    implementation(catalog.findLibrary(it).get())
                }
            }
        }
    }

    private companion object {
        const val ANDROID_ALL_ALIAS_PREFIX = "robolectric.android.all."
    }
}

abstract class WriteRobolectricDeps : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val jars: ConfigurableFileCollection

    @get:Input abstract val coordinates: MapProperty<String, String>

    @get:OutputFile abstract val output: RegularFileProperty

    @TaskAction
    fun write() {
        val text =
            coordinates
                .get()
                .toSortedMap()
                .map { (coordinate, path) -> propertyLine(coordinate, path) }
                .joinToString(separator = "\n", postfix = "\n")
        output.get().asFile.writeText(text)
    }

    internal companion object {
        /** Formats one entry with `java.util.Properties` escaping, minus its timestamp comment. */
        fun propertyLine(
            key: String,
            value: String,
        ): String {
            val writer = StringWriter()
            Properties().apply { setProperty(key, value) }.store(writer, null)
            return writer.toString().lineSequence().single { it.isNotEmpty() && !it.startsWith("#") }
        }
    }
}

private class RobolectricDepsArgument(
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    val depsFile: org.gradle.api.provider.Provider<org.gradle.api.file.RegularFile>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("-Drobolectric-deps.properties=${depsFile.get().asFile.absolutePath}")
}
