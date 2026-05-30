package ch.trancee.meshlink.wire

private object WireCompatibilitySupportLoaderAnchor

internal actual fun wireCompatibilityResourceTextOrNull(fileName: String): String? {
    val classLoader = WireCompatibilitySupportLoaderAnchor::class.java.classLoader ?: return null
    return classLoader.getResourceAsStream(fileName)?.bufferedReader()?.use { reader ->
        reader.readText()
    }
}
