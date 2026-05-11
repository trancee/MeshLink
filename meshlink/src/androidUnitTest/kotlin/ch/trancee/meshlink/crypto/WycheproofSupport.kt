package ch.trancee.meshlink.crypto

internal actual object WycheproofSupport {
    actual fun providerOrNull(): CryptoProvider? = null

    actual fun resourceLinesOrNull(fileName: String): List<String>? = null
}
