package ch.trancee.meshlink.reference.session

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** File-backed document store for retained history and export artifacts. */
public class OkioReferenceDocumentStore(
    private val baseDirectory: String,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : ReferenceDocumentStore {
    override suspend fun readText(path: String): String? {
        val resolved = resolve(path)
        if (!fileSystem.exists(resolved)) {
            return null
        }
        return fileSystem.read(resolved) { readUtf8() }
    }

    override suspend fun writeText(path: String, content: String): Unit {
        val resolved = resolve(path)
        resolved.parent?.let { parent -> fileSystem.createDirectories(parent) }
        fileSystem.write(resolved) { writeUtf8(content) }
    }

    override suspend fun delete(path: String): Unit {
        val resolved = resolve(path)
        if (fileSystem.exists(resolved)) {
            fileSystem.delete(resolved)
        }
    }

    private fun resolve(path: String): Path {
        return "$baseDirectory/$path".toPath(normalize = true)
    }
}
