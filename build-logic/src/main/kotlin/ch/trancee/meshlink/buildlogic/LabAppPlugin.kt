package ch.trancee.meshlink.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * Configures the Android lab app (ADR 0017 decision 7): a Compose application with AGP's built-in
 * Kotlin, the JDK 21 toolchain and the library's SDK levels. It runs detekt like the libraries, and Android lint on its own sources only; it is never
 * published and stays out of Kover, ABI validation and Dokka.
 */
class LabAppPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project.pluginManager) {
            apply("com.android.application")
            apply("org.jetbrains.kotlin.plugin.compose")
            apply(DependencyDirectionPlugin::class.java)
            apply("dev.detekt")
        }
        SecurityFloors.apply(project)
        val versions = project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

        fun version(alias: String): Int =
            versions
                .findVersion(alias)
                .get()
                .requiredVersion
                .toInt()

        project.extensions.configure(ApplicationExtension::class.java) {
            compileSdk = version("android-compileSdk")
            defaultConfig {
                minSdk = version("android-minSdk")
                targetSdk = version("android-compileSdk")
            }
            buildFeatures.compose = true
            // Android lint checks this app only; the library modules aren't lint-gated (ADR 0005).
            lint.checkDependencies = false
        }
        project.extensions.configure(KotlinAndroidProjectExtension::class.java) {
            jvmToolchain(version("jdk-toolchain"))
            compilerOptions { allWarningsAsErrors.set(true) }
        }
        project.extensions.configure(DetektExtension::class.java) {
            buildUponDefaultConfig.set(true)
            config.from(
                project.rootProject.layout.projectDirectory
                    .file("config/detekt/detekt.yml"),
            )
        }
        // Typed rules need the compilation classpath, which only the variant tasks have.
        project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn("detektMain") }
    }
}
