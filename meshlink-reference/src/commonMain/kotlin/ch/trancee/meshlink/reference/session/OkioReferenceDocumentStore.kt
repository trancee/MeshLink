package ch.trancee.meshlink.reference.session

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** File-backed document store for retained history and export artifacts. */
internal class OkioReferenceDocumentStore(
    private val baseDirectory: String,
    private val fileSystem: FileSystem,
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

/**
 * Creates a filesystem-backed [ReferenceDocumentStore] rooted at [baseDirectory], using the system
 * filesystem. Exposed as a factory (rather than making [OkioReferenceDocumentStore] public) so
 * consumers outside this module -- e.g. the Android application module -- don't need a direct
 * dependency on okio's types.
 */
public fun createFileDocumentStore(baseDirectory: String): ReferenceDocumentStore {
    return OkioReferenceDocumentStore(baseDirectory, FileSystem.SYSTEM)
}
