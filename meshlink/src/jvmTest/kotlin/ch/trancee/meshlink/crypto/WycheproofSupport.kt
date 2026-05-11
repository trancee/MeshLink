package ch.trancee.meshlink.crypto

internal actual object WycheproofSupport {
    actual fun providerOrNull(): CryptoProvider? = JvmCryptoProvider()

    actual fun resourceLinesOrNull(fileName: String): List<String>? {
        return WycheproofSupport::class
            .java
            .classLoader
            .getResourceAsStream("wycheproof/$fileName")
            ?.bufferedReader()
            ?.use { reader -> reader.readLines() }
    }
}
