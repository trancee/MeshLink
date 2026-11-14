package ch.trancee.meshlink.buildlogic

import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.HostManager
import java.io.File
import javax.inject.Inject

/**
 * Binds the static libsodium archives to Kotlin (ADR 0004 "Binding"):
 *
 * - `buildSodiumJni<Target>` links `native/meshlink_sodium_jni.c` with libsodium into a shared
 *   library exporting only `JNI_OnLoad`, for each Android ABI and the two test hosts.
 * - The Android shims become generated `jniLibs`, so the AAR carries them.
 * - `testAndroidHostTest` loads the shim of the machine it runs on through `java.library.path`.
 * - Each iOS target gets a `sodium` cinterop over that target's archive. Only a Mac builds Apple
 *   targets, so only a Mac wires the cinterop to the archive's build task.
 */
internal object LibsodiumBindings {
    private const val HOST_TEST_TASK = "testAndroidHostTest"

    fun register(
        project: Project,
        builds: Map<LibsodiumTarget, TaskProvider<BuildLibsodium>>,
    ) {
        val catalog = project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

        fun version(alias: String): String =
            catalog
                .findVersion(alias)
                .get()
                .requiredVersion
        val jniInclude =
            project.extensions
                .getByType(JavaToolchainService::class.java)
                .launcherFor { languageVersion.set(JavaLanguageVersion.of(version("jdk-toolchain"))) }
                .map {
                    it.metadata.installationPath
                        .dir("include")
                        .asFile.absolutePath
                }
        val shims =
            builds.filterKeys { it.shimFile != null }.mapValues { (target, build) ->
                project.tasks.register("buildSodiumJni${target.taskSuffix}", BuildSodiumJni::class.java) {
                    group = LibsodiumPlugin.GROUP
                    description = "Links the libsodium JNI shim for ${target.id}."
                    libsodium.set(build.flatMap { it.output })
                    native.set(project.layout.projectDirectory.dir("native"))
                    this.target.set(target.id)
                    shimFile.set(target.shimFile)
                    if (target.family == LibsodiumTarget.Family.ANDROID) {
                        jni.set("")
                        environment.put("NDK_VERSION", version("android-ndk"))
                        environment.put("NDK_PLATFORM", "android-${version("android-minSdk")}")
                    } else {
                        jni.set(jniInclude)
                    }
                    output.set(project.layout.buildDirectory.dir("libsodium/jni/${target.id}"))
                }
            }
        project.extensions.configure(KotlinMultiplatformAndroidComponentsExtension::class.java) {
            onVariants { variant ->
                shims.filterKeys { it.family == LibsodiumTarget.Family.ANDROID }.values.forEach { shim ->
                    variant.sources.jniLibs?.addGeneratedSourceDirectory(shim, BuildSodiumJni::output)
                }
            }
        }
        val hostShim = hostTarget()?.let(shims::getValue)
        project.tasks.withType(Test::class.java).matching { it.name == HOST_TEST_TASK }.configureEach {
            if (hostShim == null) {
                doFirst {
                    throw GradleException(
                        "no libsodium JNI shim for host ${System.getProperty("os.name")} ${System.getProperty("os.arch")}",
                    )
                }
            } else {
                jvmArgumentProviders.add(
                    project.objects.newInstance(LibraryPath::class.java).apply { directory.set(hostShim.flatMap { it.output }) },
                )
            }
        }
        registerCinterops(project, builds)
        registerGates(project, builds, shims, version("android-ndk"))
    }

    /**
     * The native gates of ADR 0004 "Binding": `check` runs the Android shim gates; the Apple archive
     * gate needs `xcrun`, so the macOS CI job runs it by name.
     */
    private fun registerGates(
        project: Project,
        builds: Map<LibsodiumTarget, TaskProvider<BuildLibsodium>>,
        shims: Map<LibsodiumTarget, TaskProvider<BuildSodiumJni>>,
        ndkVersion: String,
    ) {
        val androidShims =
            project.files(
                shims.filterKeys { it.family == LibsodiumTarget.Family.ANDROID }.map { (target, shim) ->
                    shim.flatMap { it.output.file(target.shimFile!!) }
                },
            )
        val symbols =
            project.tasks.register("checkSodiumJniSymbols", CheckNativeSymbols::class.java) {
                group = LifecycleBasePlugin.VERIFICATION_GROUP
                description = "Fails if an Android JNI shim exports libsodium or touches AES, AEGIS or IPCrypt symbols."
                binaries.from(androidShims)
                nm.set(llvmNm(project, ndkVersion).map { listOf(it, "-D") })
                report.set(project.layout.buildDirectory.file("reports/native/jni-symbols.txt"))
            }
        val size =
            project.tasks.register("checkSodiumJniSize", CheckNativeSize::class.java) {
                group = LifecycleBasePlugin.VERIFICATION_GROUP
                description = "Fails if an Android JNI shim is over ${NativeGates.SIZE_LIMIT_BYTES} bytes."
                binaries.from(androidShims)
                measure.set(NativeSizeMeasure.FILE)
                report.set(project.layout.buildDirectory.file("reports/native/jni-size.txt"))
            }
        project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(symbols, size) }
        project.tasks.register("checkSodiumAppleSize", CheckNativeSize::class.java) {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Fails if the libsodium archive of an iOS slice has over ${NativeGates.SIZE_LIMIT_BYTES} bytes of segments."
            builds.filterKeys { it.family == LibsodiumTarget.Family.APPLE }.values.forEach { build ->
                binaries.from(build.flatMap { it.output.file("lib/libsodium.a") })
            }
            measure.set(NativeSizeMeasure.DARWIN_SEGMENTS)
            report.set(project.layout.buildDirectory.file("reports/native/apple-size.txt"))
        }
    }

    /** The pinned NDK's `llvm-nm`, found like `build-libsodium.sh` finds the NDK. */
    private fun llvmNm(
        project: Project,
        ndkVersion: String,
    ) = project.providers
        .environmentVariable("ANDROID_HOME")
        .orElse(project.providers.environmentVariable("ANDROID_SDK_ROOT"))
        .map { sdk ->
            val prebuilt = File(sdk, "ndk/$ndkVersion/toolchains/llvm/prebuilt")
            val host = prebuilt.listFiles()?.singleOrNull() ?: throw GradleException("no NDK $ndkVersion host toolchain in $prebuilt")
            host.resolve("bin/llvm-nm").absolutePath
        }

    private fun registerCinterops(
        project: Project,
        builds: Map<LibsodiumTarget, TaskProvider<BuildLibsodium>>,
    ) {
        val iosTargets = mapOf("iosArm64" to LibsodiumTarget.IOS_ARM64, "iosSimulatorArm64" to LibsodiumTarget.IOS_ARM64_SIMULATOR)
        project.extensions.configure(KotlinMultiplatformExtension::class.java) {
            targets.withType(KotlinNativeTarget::class.java).configureEach {
                val target = iosTargets.getValue(name)
                val build = builds.getValue(target)
                // The fixed output path, not the task's output provider: cinterop reads these paths
                // while Gradle resolves task dependencies, before the build task has run.
                val out =
                    project.layout.buildDirectory
                        .dir("${LibsodiumPlugin.OUTPUT}/${target.id}")
                        .get()
                        .asFile
                compilations.getByName("main").cinterops.create("sodium") {
                    definitionFile.set(project.layout.projectDirectory.file("src/nativeInterop/cinterop/sodium.def"))
                    includeDirs(out.resolve("include"))
                    extraOpts("-libraryPath", out.resolve("lib").absolutePath)
                    if (HostManager.hostIsMac) {
                        project.tasks.named(interopProcessingTaskName) { dependsOn(build) }
                    }
                }
            }
        }
    }

    /** The test-only libsodium target of the machine Gradle runs on, or `null` if there is none. */
    private fun hostTarget(): LibsodiumTarget? {
        val os = System.getProperty("os.name")
        val arch = System.getProperty("os.arch")
        return when {
            os.startsWith("Mac") && arch == "aarch64" -> LibsodiumTarget.MACOS_ARM64
            os.startsWith("Linux") && arch == "amd64" -> LibsodiumTarget.LINUX_X86_64
            else -> null
        }
    }
}

/** Puts [directory] on `java.library.path`, so `System.loadLibrary` finds the host JNI shim. */
abstract class LibraryPath : CommandLineArgumentProvider {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val directory: DirectoryProperty

    override fun asArguments(): Iterable<String> = listOf("-Djava.library.path=${directory.get().asFile.absolutePath}")
}

/** Runs `native/build-libsodium.sh shim <target>`, writing [shimFile] under [output]. */
@DisableCachingByDefault(because = "Links with the local C toolchain, which is not a tracked input.")
abstract class BuildSodiumJni
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        /** The output directory of the target's `buildLibsodium` task. */
        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val libsodium: DirectoryProperty

        /** `native/`: the build script, the shim source and its export lists. */
        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val native: DirectoryProperty

        @get:Input abstract val target: Property<String>

        /** The JDK's `include` directory for host shims; empty for Android, whose NDK has `jni.h`. */
        @get:Input abstract val jni: Property<String>

        @get:Input abstract val environment: MapProperty<String, String>

        @get:Internal abstract val shimFile: Property<String>

        @get:OutputDirectory abstract val output: DirectoryProperty

        @TaskAction
        fun link() {
            val out = output.get().asFile
            out.deleteRecursively()
            val script = native.get().file("build-libsodium.sh").asFile
            exec.exec {
                commandLine(
                    "sh",
                    script.absolutePath,
                    "shim",
                    target.get(),
                    libsodium.get().asFile.absolutePath,
                    jni.get(),
                    out.resolve(shimFile.get()).absolutePath,
                )
                environment(this@BuildSodiumJni.environment.get())
            }
        }
    }
