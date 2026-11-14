package ch.trancee.meshlink.buildlogic

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.time.Duration

/**
 * Configures a MeshLink module: the Android KMP library target with host tests, `iosArm64`,
 * `iosSimulatorArm64`, the JDK 21 toolchain, strict explicit API and the dependency-direction check.
 *
 * The Android namespace derives from the module path, so `:meshlink-crypto-sodium` becomes
 * `ch.trancee.meshlink.crypto.sodium` and `:meshlink` becomes `ch.trancee.meshlink`.
 */
class KmpLibraryPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project.pluginManager) {
            apply("org.jetbrains.kotlin.multiplatform")
            apply("com.android.kotlin.multiplatform.library")
            apply(DependencyDirectionPlugin::class.java)
            apply("org.jetbrains.kotlinx.kover")
            apply("dev.detekt")
        }
        val versions = project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

        fun version(alias: String): Int =
            versions
                .findVersion(alias)
                .get()
                .requiredVersion
                .toInt()

        project.extensions.configure(KotlinMultiplatformExtension::class.java) {
            jvmToolchain(version("jdk-toolchain"))
            explicitApi()
            compilerOptions { allWarningsAsErrors.set(true) }
            extensions.configure(KotlinMultiplatformAndroidLibraryExtension::class.java) {
                namespace = namespaceFor(project.path)
                compileSdk = version("android-compileSdk")
                minSdk = version("android-minSdk")
                withHostTest {}
            }
            iosArm64()
            iosSimulatorArm64()
        }
        project.extensions.configure(DetektExtension::class.java) {
            buildUponDefaultConfig.set(true)
            config.from(
                project.rootProject.layout.projectDirectory
                    .file("config/detekt/detekt.yml"),
            )
        }
        val detektSourceSets = project.tasks.withType(Detekt::class.java).matching { it.name.endsWith("SourceSet") }
        project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(detektSourceSets) }
        project.tasks.withType(AbstractTestTask::class.java).configureEach { timeout.set(TEST_TASK_TIMEOUT) }
    }

    internal companion object {
        val TEST_TASK_TIMEOUT: Duration = Duration.ofMinutes(10)

        fun namespaceFor(path: String): String = "ch.trancee." + path.removePrefix(":").replace('-', '.')
    }
}
