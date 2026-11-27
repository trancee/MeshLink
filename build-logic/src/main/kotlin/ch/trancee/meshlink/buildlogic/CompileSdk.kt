package ch.trancee.meshlink.buildlogic

/**
 * The Android SDK release every module compiles against: the catalog's `android-compileSdk`, an API level with an
 * optional minor API level such as `37.2` (ADR 0005).
 */
internal data class CompileSdk(
    val apiLevel: Int,
    val minorApiLevel: Int?,
) {
    companion object {
        fun parse(version: String): CompileSdk {
            val parts = version.split(".").map(String::toInt)
            return CompileSdk(parts[0], parts.getOrNull(1))
        }
    }
}
