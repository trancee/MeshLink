package ch.trancee.meshlink.wire

internal actual object WireCompatibilitySupport {
    actual fun resourceTextOrNull(fileName: String): String? {
        return WireCompatibilitySupport::class
            .java
            .classLoader
            .getResourceAsStream(fileName)
            ?.bufferedReader()
            ?.use { reader -> reader.readText() }
    }
}
