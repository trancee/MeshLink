package ch.trancee.meshlink.wire

internal expect object WireCompatibilitySupport {
    fun resourceTextOrNull(fileName: String): String?
}
