package ch.trancee.meshlink.crypto

private object WycheproofSupportLoaderAnchor

internal actual fun wycheproofProviderOrNull(): CryptoProvider? = JvmCryptoProvider()

internal actual fun wycheproofResourceLinesOrNull(fileName: String): List<String>? {
    val classLoader = WycheproofSupportLoaderAnchor::class.java.classLoader ?: return null
    return classLoader.getResourceAsStream("wycheproof/$fileName")?.bufferedReader()?.use { reader
        ->
        reader.readLines()
    }
}
