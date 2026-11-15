package ch.trancee.lab

import android.content.Context
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.android
import java.io.File
import java.security.MessageDigest
import kotlin.time.TimeSource

/**
 * A lab session for [args], the comma-separated `key=value` tokens of the `args` launch extra. It
 * writes its results to `files/results/` in the app container.
 */
public fun labSession(
    context: Context,
    args: String,
): LabSession =
    LabSession(
        MeshPlatform.android(context),
        File(context.filesDir, "results").path,
        args.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        TimeSource.Monotonic::markNow,
    )

internal actual fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

internal actual fun appendLine(
    directory: String,
    name: String,
    line: String,
) {
    val folder = File(directory)
    folder.mkdirs()
    File(folder, name).appendText(line + "\n")
}
