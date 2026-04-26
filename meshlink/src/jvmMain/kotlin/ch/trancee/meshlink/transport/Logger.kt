package ch.trancee.meshlink.transport

internal actual object Logger {
    actual fun d(tag: String, msg: String) {
        println("[$tag] $msg")
    }
}
