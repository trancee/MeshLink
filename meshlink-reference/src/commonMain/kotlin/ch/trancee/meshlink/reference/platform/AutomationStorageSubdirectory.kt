package ch.trancee.meshlink.reference.platform

internal fun normalizeAutomationStorageSubdirectory(
    raw: String?,
    defaultValue: String = "default",
): String {
    val candidate = raw?.trim().orEmpty()
    if (candidate.isBlank()) return defaultValue
    if (candidate == "." || candidate == "..") return defaultValue
    if (candidate.any { it == '/' || it == '\\' || it.isISOControl() }) return defaultValue
    return candidate
}
