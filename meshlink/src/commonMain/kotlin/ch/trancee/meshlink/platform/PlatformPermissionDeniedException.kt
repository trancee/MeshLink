package ch.trancee.meshlink.platform

internal class PlatformPermissionDeniedException
internal constructor(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
