@file:OptIn(ExperimentalForeignApi::class)

package ch.trancee.lab

import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.ios
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import kotlin.time.TimeSource

/**
 * A lab session for [arguments], the process arguments; it reads the `key=value` ones. It writes its
 * results to `Documents/results/` in the app container.
 */
public fun labSession(arguments: List<String>): LabSession {
    val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
    return LabSession(MeshPlatform.ios(), "$documents/results", arguments, TimeSource.Monotonic::markNow)
}

internal actual fun sha256(bytes: ByteArray): ByteArray {
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
    bytes.usePinned { input ->
        digest.usePinned { output ->
            val data = if (bytes.isEmpty()) null else input.addressOf(0)
            CC_SHA256(data, bytes.size.convert(), output.addressOf(0).reinterpret())
        }
    }
    return digest
}

internal actual fun appendLine(
    directory: String,
    name: String,
    line: String,
) {
    NSFileManager.defaultManager.createDirectoryAtPath(
        directory,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    val file = fopen("$directory/$name", "a") ?: return
    fputs(line + "\n", file)
    fclose(file)
}
