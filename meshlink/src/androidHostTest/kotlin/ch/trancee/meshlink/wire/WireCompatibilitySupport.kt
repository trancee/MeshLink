package ch.trancee.meshlink.wire

internal actual object WireCompatibilitySupport {
    actual fun resourceTextOrNull(fileName: String): String? {
        val classLoader = WireCompatibilitySupport::class.java.classLoader ?: return null
        return classLoader.getResourceAsStream(fileName)?.bufferedReader()?.use { reader ->
            reader.readText()
        }
    }
}
