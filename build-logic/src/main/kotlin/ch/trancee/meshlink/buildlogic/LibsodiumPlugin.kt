package ch.trancee.meshlink.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import javax.inject.Inject

/**
 * Builds static libsodium archives from the pinned upstream release (ADR 0004, ADR 0005 "libsodium build").
 *
 * `libsodium.properties` in the project directory is the only place that pins the version, the
 * SHA-256 and the upstream minisign public key. `downloadLibsodium` fetches the tarball and its
 * signature, `verifyLibsodium` checks both and only then copies the tarball to `verified/`, and each
 * `buildLibsodium<Target>` extracts only that verified copy and runs `native/build-libsodium.sh`.
 */
class LibsodiumPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val pin = Properties().apply { load(project.file(PIN_FILE).reader()) }
        val version = pin.require("version")
        val tarballName = "libsodium-$version.tar.gz"
        val baseUrl = pin.require("baseUrl").removeSuffix("/")
        val root = project.layout.buildDirectory.dir("libsodium")
        val download =
            project.tasks.register("downloadLibsodium", DownloadLibsodium::class.java) {
                group = GROUP
                description = "Downloads the pinned libsodium release tarball and its minisign signature."
                tarballUrl.set("$baseUrl/$tarballName")
                tarball.set(root.map { it.file("download/$tarballName") })
                signature.set(root.map { it.file("download/$tarballName.minisig") })
            }
        val verify =
            project.tasks.register("verifyLibsodium", VerifyLibsodium::class.java) {
                group = GROUP
                description = "Checks the libsodium tarball's SHA-256 and minisign signature before anything extracts it."
                tarball.set(download.flatMap { it.tarball })
                signature.set(download.flatMap { it.signature })
                sha256.set(pin.require("sha256"))
                publicKey.set(pin.require("minisignPublicKey"))
                minisign.set(project.providers.gradleProperty("meshlink.minisign").orElse("minisign"))
                verified.set(root.map { it.file("verified/$tarballName") })
            }
        registerBuilds(project, verify, root)
    }

    private fun registerBuilds(
        project: Project,
        verify: org.gradle.api.tasks.TaskProvider<VerifyLibsodium>,
        root: org.gradle.api.provider.Provider<org.gradle.api.file.Directory>,
    ) {
        val builds =
            LibsodiumTarget.entries.associateWith { target ->
                project.tasks.register("buildLibsodium${target.taskSuffix}", BuildLibsodium::class.java) {
                    group = GROUP
                    description = "Builds static libsodium for ${target.id}${if (target.testOnly) " (host, test-only)" else ""}."
                    tarball.set(verify.flatMap { it.verified })
                    script.set(project.layout.projectDirectory.file(SCRIPT))
                    this.target.set(target.id)
                    if (target.family == LibsodiumTarget.Family.ANDROID) {
                        val catalog = project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
                        val ndk = catalog.findVersion("android-ndk").get().requiredVersion
                        val minSdk = catalog.findVersion("android-minSdk").get().requiredVersion
                        environment.put("NDK_VERSION", ndk)
                        environment.put("NDK_PLATFORM", "android-$minSdk")
                    }
                    output.set(root.map { it.dir("out/${target.id}") })
                }
            }
        LibsodiumTarget.Family.entries.forEach { family ->
            project.tasks.register("buildLibsodium${family.taskSuffix}") {
                group = GROUP
                description = "Builds static libsodium for every ${family.taskSuffix} target."
                dependsOn(builds.filterKeys { it.family == family }.values)
            }
        }
    }

    private fun Properties.require(key: String): String = getProperty(key) ?: throw GradleException("$PIN_FILE is missing the key '$key'")

    private companion object {
        const val GROUP = "libsodium"
        const val PIN_FILE = "libsodium.properties"
        const val SCRIPT = "native/build-libsodium.sh"
    }
}

/** A MeshLink libsodium build target; [family] selects the host that can build it. */
internal enum class LibsodiumTarget(
    val id: String,
    val family: Family,
    val testOnly: Boolean = false,
) {
    ANDROID_ARM64_V8A("android-arm64-v8a", Family.ANDROID),
    ANDROID_ARMEABI_V7A("android-armeabi-v7a", Family.ANDROID),
    ANDROID_X86_64("android-x86_64", Family.ANDROID),
    IOS_ARM64("ios-arm64", Family.APPLE),
    IOS_ARM64_SIMULATOR("ios-arm64-simulator", Family.APPLE),
    MACOS_ARM64("macos-arm64", Family.HOST_MACOS, testOnly = true),
    LINUX_X86_64("linux-x86_64", Family.HOST_LINUX, testOnly = true),
    ;

    val taskSuffix: String = id.split('-', '_').joinToString("") { part -> part.replaceFirstChar(Char::uppercaseChar) }

    enum class Family(
        val taskSuffix: String,
    ) {
        ANDROID("Android"),
        APPLE("Apple"),
        HOST_MACOS("HostMacos"),
        HOST_LINUX("HostLinux"),
    }
}

@DisableCachingByDefault(because = "Downloading is not worth caching; the CI cache keeps the file.")
abstract class DownloadLibsodium : DefaultTask() {
    @get:Input abstract val tarballUrl: Property<String>

    @get:OutputFile abstract val tarball: RegularFileProperty

    @get:OutputFile abstract val signature: RegularFileProperty

    init {
        onlyIf("the tarball or its signature is not downloaded yet") { !tarball.get().asFile.exists() || !signature.get().asFile.exists() }
    }

    @TaskAction
    fun download() {
        val url = tarballUrl.get()
        fetch(url, tarball.get().asFile)
        fetch("$url.minisig", signature.get().asFile)
    }

    private fun fetch(
        url: String,
        target: File,
    ) {
        val partial = File(target.parentFile, "${target.name}.part")
        val connection =
            URI(url).toURL().openConnection().apply {
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
            }
        connection.getInputStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
        if (!partial.renameTo(target)) throw GradleException("could not move $partial to $target")
    }

    private companion object {
        const val TIMEOUT_MILLIS = 30_000
    }
}

@DisableCachingByDefault(because = "A cache hit would copy the tarball without verifying it.")
abstract class VerifyLibsodium
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val tarball: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val signature: RegularFileProperty

        @get:Input abstract val sha256: Property<String>

        @get:Input abstract val publicKey: Property<String>

        @get:Input abstract val minisign: Property<String>

        @get:OutputFile abstract val verified: RegularFileProperty

        @TaskAction
        fun verify() {
            val source = tarball.get().asFile
            val target = verified.get().asFile
            target.delete()
            val actual = sha256(source.readBytes())
            if (actual != sha256.get()) {
                throw GradleException("libsodium SHA-256 check failed for ${source.name}: expected ${sha256.get()}, got $actual")
            }
            val output = ByteArrayOutputStream()
            val binary = minisign.get()
            if (!onPath(binary)) {
                throw GradleException(
                    "libsodium minisign check needs the minisign binary ('$binary'): " +
                        "install it with 'brew install minisign' or 'apt-get install minisign'",
                )
            }
            val result =
                exec.exec {
                    commandLine(binary, "-V", "-P", publicKey.get(), "-m", source.absolutePath, "-x", signature.get().asFile.absolutePath)
                    standardOutput = output
                    errorOutput = output
                    isIgnoreExitValue = true
                }
            if (result.exitValue != 0) {
                throw GradleException("libsodium minisign check failed for ${source.name}: ${output.toString(Charsets.UTF_8).trim()}")
            }
            source.copyTo(target)
        }

        private fun onPath(binary: String): Boolean =
            if (binary.contains(File.separatorChar)) {
                File(binary).canExecute()
            } else {
                System
                    .getenv("PATH")
                    .orEmpty()
                    .split(File.pathSeparatorChar)
                    .any { File(it, binary).canExecute() }
            }
    }

/**
 * Runs `native/build-libsodium.sh build <target>` on the verified tarball. The task is skipped when
 * [output] already holds a stamp for the same tarball, script and toolchain, so a restored CI cache
 * skips the build even without Gradle's task history.
 */
@DisableCachingByDefault(because = "The CI cache restores the output directory; the stamp decides whether to rebuild.")
abstract class BuildLibsodium
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val tarball: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        abstract val script: RegularFileProperty

        @get:Input abstract val target: Property<String>

        @get:Input abstract val environment: MapProperty<String, String>

        @get:OutputDirectory abstract val output: DirectoryProperty

        @get:Internal val stamp: File get() = output.get().file(STAMP).asFile

        init {
            onlyIf("the output stamp does not match the tarball, script and toolchain") {
                !stamp.exists() || stamp.readText() != key()
            }
        }

        @TaskAction
        fun build() {
            val out = output.get().asFile
            out.deleteRecursively()
            out.mkdirs()
            run("build", tarball.get().asFile.absolutePath, out.absolutePath)
            stamp.writeText(key())
        }

        private fun key(): String =
            sha256(
                listOf(
                    sha256(tarball.get().asFile.readBytes()),
                    sha256(script.get().asFile.readBytes()),
                    target.get(),
                    run("toolchain"),
                ).joinToString("\n").toByteArray(),
            )

        private fun run(vararg args: String): String {
            val stdout = ByteArrayOutputStream()
            val result =
                exec.exec {
                    commandLine(listOf("sh", script.get().asFile.absolutePath, args.first(), target.get()) + args.drop(1))
                    environment(this@BuildLibsodium.environment.get())
                    standardOutput = if (args.first() == "toolchain") stdout else System.out
                    isIgnoreExitValue = true
                }
            if (result.exitValue !=
                0
            ) {
                throw GradleException("build-libsodium.sh ${args.first()} ${target.get()} failed (exit ${result.exitValue})")
            }
            return stdout.toString(Charsets.UTF_8)
        }

        private companion object {
            const val STAMP = ".meshlink-libsodium-stamp"
        }
    }

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
