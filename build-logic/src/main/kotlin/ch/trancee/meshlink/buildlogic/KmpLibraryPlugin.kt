package ch.trancee.meshlink.buildlogic

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

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
        }
        val versions = project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
        fun version(alias: String): Int = versions.findVersion(alias).get().requiredVersion.toInt()

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
    }

    internal companion object {
        fun namespaceFor(path: String): String =
            "ch.trancee." + path.removePrefix(":").replace('-', '.')
    }
}
