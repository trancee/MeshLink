package ch.trancee.meshlink.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.testing.Test

/**
 * Lets the Android host tests of a module that runs MeshLink code load the libsodium JNI shim: the
 * shim directory of `:meshlink-crypto-sodium` already in the host-test runtime classpath goes on
 * `java.library.path`.
 *
 * The directory is the `sodiumHostJniElements` variant of that component, picked by variant
 * reselection (incubating, ADR 0005 holds), so no module declares a new project dependency.
 */
class SodiumHostJniPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val shim =
            project.files(
                project.provider {
                    project.configurations
                        .getByName(RUNTIME_CLASSPATH)
                        .incoming
                        .artifactView {
                            withVariantReselection()
                            attributes.attribute(
                                Usage.USAGE_ATTRIBUTE,
                                project.objects.named(Usage::class.java, LibsodiumBindings.HOST_JNI_USAGE),
                            )
                            componentFilter { (it as? ProjectComponentIdentifier)?.projectPath == SODIUM_PROJECT }
                        }.files
                },
            )
        project.tasks.withType(Test::class.java).matching { it.name == LibsodiumBindings.HOST_TEST_TASK }.configureEach {
            jvmArgumentProviders.add(project.objects.newInstance(LibraryPath::class.java).apply { directories.from(shim) })
        }
    }

    private companion object {
        const val RUNTIME_CLASSPATH = "androidHostTestRuntimeClasspath"
        const val SODIUM_PROJECT = ":meshlink-crypto-sodium"
    }
}
