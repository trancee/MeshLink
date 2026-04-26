package ch.trancee.meshlink.transport

/** Platform-agnostic debug logger. Implementations delegate to the native log facility. */
expect object Logger {
    fun d(tag: String, msg: String)
}
